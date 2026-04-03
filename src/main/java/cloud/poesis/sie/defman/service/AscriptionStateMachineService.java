package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AscriptionStatusTransitionRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionPathType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.RefereeReference;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pure state machine for GSM ascription lifecycle transitions. Owns transition validation,
 * recording, referee preconditions, and audit trail queries. Has NO knowledge of subtype services
 * and therefore NO circular dependencies.
 *
 * <p>All transition rules (valid edges, referee preconditions, cascade scope) are defined
 * declaratively in {@link AscriptionStatusTransitionPathType} — this service applies them.
 *
 * <p>Implements ONLY {@link AscriptionStatusTransitionRuleType} rules.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
@Transactional("transactionManager")
public class AscriptionStateMachineService {

  // ======================================================================
  // Dependencies
  // ======================================================================

  private final AscriptionStatusTransitionRepository transitionRepo;
  private final EntityManager entityManager;

  public AscriptionStateMachineService(
      AscriptionStatusTransitionRepository transitionRepo, EntityManager entityManager) {
    this.transitionRepo = transitionRepo;
    this.entityManager = entityManager;
  }

  // ======================================================================
  // Transition validation
  // ======================================================================

  /**
   * Validates that a status transition is allowed by the state machine.
   *
   * @param ascriptionId the ascription being transitioned (for error reporting)
   * @param from the current status
   * @param to the requested target status
   * @throws RuleViolationException if the transition violates state machine rules
   */
  public void validateTransition(
      UUID ascriptionId, AscriptionStatusType from, AscriptionStatusType to) {
    Set<AscriptionStatusType> allowed = AscriptionStatusTransitionPathType.validTargets(from);
    if (AscriptionStatusTransitionPathType.isTerminal(from)) {
      throw RuleViolationException.of(
          AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_TERMINAL_IMMUTABILITY,
          "Ascription "
              + ascriptionId
              + " is in terminal state "
              + from
              + " and cannot transition to "
              + to,
          "ascriptionId",
          ascriptionId,
          "fromStatus",
          from.name(),
          "toStatus",
          to.name());
    }
    if (!allowed.contains(to)) {
      throw RuleViolationException.of(
          AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_PATH,
          "Invalid transition from " + from + " to " + to + " for ascription " + ascriptionId,
          "ascriptionId",
          ascriptionId,
          "fromStatus",
          from.name(),
          "toStatus",
          to.name());
    }
  }

  // ======================================================================
  // Referee preconditions
  // ======================================================================

  /**
   * Validates referee preconditions for a status transition.
   *
   * @param refs the referee references to validate
   * @param from the pre-transition status (null for creation)
   * @param to the post-transition status
   * @throws RuleViolationException if any referee is in a disallowed status
   */
  public void validateRefereePreconditions(
      List<RefereeReference> refs, AscriptionStatusType from, AscriptionStatusType to) {
    if (refs.isEmpty()) {
      return;
    }

    Set<AscriptionStatusType> allowed =
        AscriptionStatusTransitionPathType.refereeAllowedStatuses(from, to);
    if (allowed == null) {
      return;
    }

    for (RefereeReference ref : refs) {
      AscriptionStatusType refStatus = ref.reference().getStatus();
      if (!allowed.contains(refStatus)) {
        Set<String> allowedNames = new LinkedHashSet<>();
        for (AscriptionStatusType s : allowed) {
          allowedNames.add(s.name());
        }
        throw RuleViolationException.of(
            AscriptionStatusTransitionRuleType
                .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
            "Referee '"
                + ref.label()
                + "' ("
                + ref.reference().getId()
                + ") is "
                + refStatus.name()
                + "; "
                + (from == null ? "creation" : "transition from " + from.name())
                + " requires one of "
                + allowedNames,
            "fromStatus",
            from == null ? null : from.name(),
            "toStatus",
            to.name(),
            "refereeLabel",
            ref.label(),
            "refereeId",
            ref.reference().getId(),
            "refereeStatus",
            refStatus.name(),
            "allowedStatuses",
            allowedNames);
      }
    }
  }

  // ======================================================================
  // Dependent cascade applicability
  // ======================================================================

  /**
   * Checks whether a dependent cascade should fire for a given transition.
   *
   * @param from the pre-transition status
   * @param to the post-transition status
   * @return true if dependent cascades apply to this transition
   */
  public boolean isDependentCascadeApplicable(AscriptionStatusType from, AscriptionStatusType to) {
    return AscriptionStatusTransitionPathType.isDependentCascadeApplicable(from, to);
  }

  // ======================================================================
  // Persistence operations
  // ======================================================================

  /**
   * Persists a transition record, flushes, and returns the refreshed entity.
   *
   * @param entity the ascription being transitioned
   * @param from the pre-transition status
   * @param to the post-transition status
   * @return the persisted and refreshed transition entity
   */
  public AscriptionStatusTransitionEntity recordTransition(
      AscriptionEntity entity, AscriptionStatusType from, AscriptionStatusType to) {
    AscriptionStatusTransitionEntity transition =
        transitionRepo.save(new AscriptionStatusTransitionEntity(entity, from, to));
    entityManager.flush();
    entityManager.detach(transition);
    UUID transitionId = Objects.requireNonNull(transition.getId(), "transition.id");
    return transitionRepo.findById(transitionId).orElseThrow();
  }

  // ======================================================================
  // Transition queries (audit trail)
  // ======================================================================

  /**
   * Returns all recorded transitions for an ascription.
   *
   * @param ascriptionId the ascription UUID
   * @return ordered list of status transition entities
   */
  @Transactional(value = "transactionManager", readOnly = true)
  public List<AscriptionStatusTransitionEntity> getTransitions(UUID ascriptionId) {
    return transitionRepo.findAllByAscriptionIdOrderByTimestampAsc(ascriptionId);
  }

  /**
   * Returns a single transition by its ID, scoped to the given ascription.
   *
   * @param transitionId the transition UUID
   * @param ascriptionId the owning ascription UUID
   * @return the matching transition entity, if present
   */
  @Transactional(value = "transactionManager", readOnly = true)
  public Optional<AscriptionStatusTransitionEntity> getTransition(
      UUID transitionId, UUID ascriptionId) {
    return transitionRepo.findByIdAndAscriptionId(transitionId, ascriptionId);
  }
}
