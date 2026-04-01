package cloud.poesis.sie.defman.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AscriptionStatusTransitionRepository;
import cloud.poesis.sie.defman.service.AbstractAscriptionService.RefereeReference;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;

/**
 * Ascription status transition service. Implements ONLY
 * {@link AscriptionStatusTransitionRuleType}
 * rules.
 *
 * <p>
 * Orchestrates GSM lifecycle transitions with referee preconditions,
 * inter-transition cascades,
 * governance convergence, and identity-bound validation. Owns {@link
 * AscriptionStatusTransitionRepository}.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
@Transactional("transactionManager")
public class AscriptionStatusTransitionService {

  private static final Logger LOG = LoggerFactory.getLogger(AscriptionStatusTransitionService.class);

  // ======================================================================
  // State machine: valid transitions
  // ======================================================================

  private static final Map<AscriptionStatusType, Set<AscriptionStatusType>> VALID_TRANSITIONS;

  static {
    VALID_TRANSITIONS = new EnumMap<>(AscriptionStatusType.class);
    VALID_TRANSITIONS.put(
        AscriptionStatusType.DRAFT,
        EnumSet.of(AscriptionStatusType.PROPOSED, AscriptionStatusType.ABANDONED));
    VALID_TRANSITIONS.put(
        AscriptionStatusType.PROPOSED,
        EnumSet.of(AscriptionStatusType.APPROVED, AscriptionStatusType.REJECTED));
    VALID_TRANSITIONS.put(AscriptionStatusType.APPROVED, EnumSet.of(AscriptionStatusType.ACTIVE));
    VALID_TRANSITIONS.put(
        AscriptionStatusType.ACTIVE,
        EnumSet.of(AscriptionStatusType.SUSPENDED, AscriptionStatusType.DEPRECATED));
    VALID_TRANSITIONS.put(
        AscriptionStatusType.SUSPENDED,
        EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED));
    VALID_TRANSITIONS.put(
        AscriptionStatusType.DEPRECATED,
        EnumSet.of(AscriptionStatusType.SUSPENDED, AscriptionStatusType.RETIRED));
    VALID_TRANSITIONS.put(AscriptionStatusType.RETIRED, EnumSet.noneOf(AscriptionStatusType.class));
    VALID_TRANSITIONS.put(
        AscriptionStatusType.ABANDONED, EnumSet.noneOf(AscriptionStatusType.class));
    VALID_TRANSITIONS.put(
        AscriptionStatusType.REJECTED, EnumSet.noneOf(AscriptionStatusType.class));
  }

  // ======================================================================
  // Referee precondition: allowed reference statuses per transition
  // ======================================================================

  private static final Set<AscriptionStatusType> IN_EFFECT_OR_SUSPENDED = EnumSet.of(
      AscriptionStatusType.ACTIVE,
      AscriptionStatusType.SUSPENDED,
      AscriptionStatusType.DEPRECATED);

  private static final Set<AscriptionStatusType> IN_EFFECT_OR_SUSPENDED_OR_RETIRED = EnumSet.of(
      AscriptionStatusType.ACTIVE, AscriptionStatusType.SUSPENDED,
      AscriptionStatusType.DEPRECATED, AscriptionStatusType.RETIRED);

  private static final Set<AscriptionStatusType> ANY_STATUS = EnumSet.allOf(AscriptionStatusType.class);

  /**
   * Composite key for REFEREE_ALLOWED map — replaces String-based "from->to" key.
   */
  private record TransitionKey(AscriptionStatusType from, AscriptionStatusType to) {
  }

  /**
   * Per-transition referee precondition: which statuses each reference must be
   * in.
   */
  private static final Map<TransitionKey, Set<AscriptionStatusType>> REFEREE_ALLOWED;

  static {
    REFEREE_ALLOWED = new HashMap<>();
    REFEREE_ALLOWED.put(
        new TransitionKey(null, AscriptionStatusType.DRAFT),
        EnumSet.of(
            AscriptionStatusType.DRAFT,
            AscriptionStatusType.PROPOSED,
            AscriptionStatusType.APPROVED,
            AscriptionStatusType.ACTIVE));
    REFEREE_ALLOWED.put(
        new TransitionKey(AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED),
        EnumSet.of(
            AscriptionStatusType.PROPOSED,
            AscriptionStatusType.APPROVED,
            AscriptionStatusType.ACTIVE));
    REFEREE_ALLOWED.put(
        new TransitionKey(AscriptionStatusType.PROPOSED, AscriptionStatusType.APPROVED),
        EnumSet.of(AscriptionStatusType.APPROVED, AscriptionStatusType.ACTIVE));
    REFEREE_ALLOWED.put(
        new TransitionKey(AscriptionStatusType.APPROVED, AscriptionStatusType.ACTIVE),
        EnumSet.of(AscriptionStatusType.ACTIVE));
    REFEREE_ALLOWED.put(
        new TransitionKey(AscriptionStatusType.ACTIVE, AscriptionStatusType.SUSPENDED),
        IN_EFFECT_OR_SUSPENDED);
    REFEREE_ALLOWED.put(
        new TransitionKey(AscriptionStatusType.SUSPENDED, AscriptionStatusType.ACTIVE),
        AscriptionStatusType.IN_EFFECT);
    REFEREE_ALLOWED.put(
        new TransitionKey(AscriptionStatusType.SUSPENDED, AscriptionStatusType.DEPRECATED),
        IN_EFFECT_OR_SUSPENDED);
    REFEREE_ALLOWED.put(
        new TransitionKey(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED),
        IN_EFFECT_OR_SUSPENDED);
    REFEREE_ALLOWED.put(
        new TransitionKey(AscriptionStatusType.DEPRECATED, AscriptionStatusType.SUSPENDED),
        IN_EFFECT_OR_SUSPENDED);
    REFEREE_ALLOWED.put(
        new TransitionKey(AscriptionStatusType.DEPRECATED, AscriptionStatusType.RETIRED),
        IN_EFFECT_OR_SUSPENDED_OR_RETIRED);
    REFEREE_ALLOWED.put(
        new TransitionKey(AscriptionStatusType.DRAFT, AscriptionStatusType.ABANDONED), ANY_STATUS);
    // PROPOSED→REJECTED: all statuses except DRAFT (SC-23: DRAFT blocks rejection)
    REFEREE_ALLOWED.put(
        new TransitionKey(AscriptionStatusType.PROPOSED, AscriptionStatusType.REJECTED),
        EnumSet.of(
            AscriptionStatusType.PROPOSED,
            AscriptionStatusType.APPROVED,
            AscriptionStatusType.ACTIVE,
            AscriptionStatusType.SUSPENDED,
            AscriptionStatusType.DEPRECATED,
            AscriptionStatusType.ABANDONED,
            AscriptionStatusType.REJECTED,
            AscriptionStatusType.RETIRED));
  }

  // ======================================================================
  // Dependent cascade scope: only these transitions trigger dependent cascades
  // ======================================================================

  private static final Map<AscriptionStatusType, Set<AscriptionStatusType>> DEPENDENT_CASCADE_SCOPE;

  static {
    DEPENDENT_CASCADE_SCOPE = new EnumMap<>(AscriptionStatusType.class);
    DEPENDENT_CASCADE_SCOPE.put(
        AscriptionStatusType.ACTIVE,
        EnumSet.of(AscriptionStatusType.SUSPENDED, AscriptionStatusType.DEPRECATED));
    DEPENDENT_CASCADE_SCOPE.put(
        AscriptionStatusType.SUSPENDED, EnumSet.of(AscriptionStatusType.DEPRECATED));
    DEPENDENT_CASCADE_SCOPE.put(
        AscriptionStatusType.DEPRECATED,
        EnumSet.of(AscriptionStatusType.SUSPENDED, AscriptionStatusType.RETIRED));
    DEPENDENT_CASCADE_SCOPE.put(
        AscriptionStatusType.DRAFT, EnumSet.of(AscriptionStatusType.ABANDONED));
    DEPENDENT_CASCADE_SCOPE.put(
        AscriptionStatusType.PROPOSED, EnumSet.of(AscriptionStatusType.REJECTED));
  }

  // ======================================================================
  // Cascade graph (built at startup from subtype declarations)
  // ======================================================================

  private record CascadeTargetEntry(
      AbstractAscriptionService<?> targetService,
      AscriptionStatusTransitionCascadeType cascadeType) {
  }

  private final AscriptionStatusTransitionRepository transitionRepo;
  private final EntityManager entityManager;
  private final List<AbstractAscriptionService<?>> subtypeServices;

  private Map<DefinitionSubjectType, AbstractAscriptionService<?>> subtypeByType;
  private Map<DefinitionSubjectType, List<CascadeTargetEntry>> cascadeTargetsBySourceType;

  /**
   * Constructs the service with its required dependencies.
   *
   * @param transitionRepo  the status transition repository
   * @param entityManager   the JPA entity manager
   * @param subtypeServices all ascription subtype services (lazy to break
   *                        circular dependency with
   *                        AbstractAscriptionService)
   */
  public AscriptionStatusTransitionService(
      AscriptionStatusTransitionRepository transitionRepo,
      EntityManager entityManager,
      @Lazy List<AbstractAscriptionService<?>> subtypeServices) {
    this.transitionRepo = transitionRepo;
    this.entityManager = entityManager;
    this.subtypeServices = subtypeServices;
  }

  /**
   * Builds the subtype lookup map and cascade graph after all beans are
   * constructed.
   */
  @PostConstruct
  void initCascadeGraph() {
    Map<DefinitionSubjectType, AbstractAscriptionService<?>> byType = new EnumMap<>(DefinitionSubjectType.class);
    for (AbstractAscriptionService<?> svc : subtypeServices) {
      byType.put(svc.getSubjectType(), svc);
    }
    this.subtypeByType = Map.copyOf(byType);

    Map<DefinitionSubjectType, List<CascadeTargetEntry>> cascadeMap = new EnumMap<>(DefinitionSubjectType.class);
    for (AbstractAscriptionService<?> svc : subtypeServices) {
      for (var entry : svc.getCascadeTargetRoles().entrySet()) {
        cascadeMap
            .computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
            .add(new CascadeTargetEntry(svc, entry.getValue()));
      }
    }
    this.cascadeTargetsBySourceType = Map.copyOf(cascadeMap);
  }

  // ======================================================================
  // TRANSITION (main orchestrator)
  // ======================================================================

  /**
   * Executes a lifecycle transition with full lifecycle governance: validation,
   * referee
   * preconditions, cascades, convergence.
   *
   * @param ascriptionId the ascription to transition
   * @param targetStatus the requested target status
   * @return the persisted transition record
   * @throws ResourceNotFoundException if the ascription does not exist
   * @throws RuleViolationException    if the transition violates state machine,
   *                                   referee, or cascade
   *                                   constraints
   */
  public AscriptionStatusTransitionEntity transition(UUID ascriptionId, String targetStatus) {
    AscriptionStatusType targetStatusType = AscriptionStatusType.valueOf(targetStatus);

    AscriptionEntity entity = entityManager.find(AscriptionEntity.class, ascriptionId);
    if (entity == null) {
      throw new ResourceNotFoundException(PrimitiveType.ASCRIPTION, ascriptionId);
    }

    DefinitionSubjectType type = entity.getDefinition().getSubjectType();
    AscriptionStatusType currentStatus = entity.getStatus();

    // 1. Validate state machine transition
    validateTransition(ascriptionId, currentStatus, targetStatusType);

    // 2. Check referee preconditions
    validateRefereePreconditions(entity, type, currentStatus, targetStatusType);

    // 3. Activation uniqueness (Structure purpose, Mechanism function, Archetype
    // title)
    if (targetStatusType == AscriptionStatusType.ACTIVE) {
      validateActivationUniqueness(entity, type);
    }

    // 3b. Subtype-specific activation hook
    if (targetStatusType == AscriptionStatusType.ACTIVE) {
      AbstractAscriptionService<?> svc = subtypeByType.get(type);
      if (svc != null) {
        svc.onActivation(entity);
      }
    }

    // 3c. Subtype-specific deactivation hook (leaving in-effect)
    if (AscriptionStatusType.IN_EFFECT.contains(currentStatus)
        && !AscriptionStatusType.IN_EFFECT.contains(targetStatusType)) {
      AbstractAscriptionService<?> svc = subtypeByType.get(type);
      if (svc != null) {
        svc.onDeactivation(entity);
      }
    }

    // 4–7. Persistence operations (translate DB constraint violations to domain
    // exceptions)
    try {
      // 4. Activation handoff (ACTIVE → previous ACTIVE to DEPRECATED)
      if (targetStatusType == AscriptionStatusType.ACTIVE) {
        handleActivation(type, entity);
      }

      // 5. Record transition (DB trigger updates entity status/version)
      AscriptionStatusTransitionEntity saved = recordTransition(entity, currentStatus, targetStatusType);

      // 6. Governance convergence (APPROVED → sibling termination)
      if (targetStatusType == AscriptionStatusType.APPROVED) {
        handleApproval(type, entity);
      }

      // 7. Execute cascades
      executeCascades(entity, type, currentStatus, targetStatusType);

      return saved;
    } catch (DataIntegrityViolationException ex) {
      throw AbstractAscriptionService.translatePersistenceException(ex);
    }
  }

  // ======================================================================
  // TRANSITION QUERIES
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

  // ======================================================================
  // Persistence operations
  // ======================================================================

  /**
   * Persists a transition record, flushes, and returns the refreshed entity.
   *
   * @param entity the ascription being transitioned
   * @param from   the pre-transition status
   * @param to     the post-transition status
   * @return the persisted and refreshed transition entity
   */
  public AscriptionStatusTransitionEntity recordTransition(
      AscriptionEntity entity, AscriptionStatusType from, AscriptionStatusType to) {
    AscriptionStatusTransitionEntity transition = transitionRepo
        .save(new AscriptionStatusTransitionEntity(entity, from, to));
    entityManager.flush();
    entityManager.detach(transition);
    UUID transitionId = Objects.requireNonNull(transition.getId(), "transition.id");
    return transitionRepo.findById(transitionId).orElseThrow();
  }

  // ======================================================================
  // Referee preconditions (public API for creation-time checks)
  // ======================================================================

  /**
   * Validates referee preconditions for a status transition.
   *
   * @param refs the referee references to validate
   * @param from the pre-transition status (null for creation)
   * @param to   the post-transition status
   * @throws RuleViolationException if any referee is in a disallowed status
   */
  public void validateRefereePreconditions(
      List<RefereeReference> refs, AscriptionStatusType from, AscriptionStatusType to) {
    if (refs.isEmpty()) {
      return;
    }

    TransitionKey key = new TransitionKey(from, to);
    Set<AscriptionStatusType> allowed = REFEREE_ALLOWED.get(key);
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
            AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
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
  // Activation uniqueness validation
  // ======================================================================

  private void validateActivationUniqueness(AscriptionEntity entity, DefinitionSubjectType type) {
    AbstractAscriptionService<?> subtypeService = subtypeByType.get(type);
    if (subtypeService != null) {
      subtypeService.validateActivationUniqueness(entity);
    }
  }

  // ======================================================================
  // Transition validation
  // ======================================================================

  private void validateTransition(
      UUID ascriptionId, AscriptionStatusType from, AscriptionStatusType to) {
    Set<AscriptionStatusType> allowed = VALID_TRANSITIONS.getOrDefault(from,
        EnumSet.noneOf(AscriptionStatusType.class));
    if (allowed.isEmpty()) {
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
  // Referee preconditions (private — transition-time)
  // ======================================================================

  private void validateRefereePreconditions(
      AscriptionEntity entity,
      DefinitionSubjectType type,
      AscriptionStatusType from,
      AscriptionStatusType to) {

    AbstractAscriptionService<?> subtypeService = subtypeByType.get(type);
    if (subtypeService == null) {
      return;
    }

    List<RefereeReference> refs = subtypeService.getRefereeReferences(entity);
    validateRefereePreconditions(refs, from, to);
  }

  // ======================================================================
  // Cascade execution
  // ======================================================================

  private void executeCascades(
      AscriptionEntity source,
      DefinitionSubjectType sourceType,
      AscriptionStatusType fromStatus,
      AscriptionStatusType toStatus) {

    List<CascadeTargetEntry> targetEntries = cascadeTargetsBySourceType.get(sourceType);
    if (targetEntries == null) {
      return;
    }

    for (CascadeTargetEntry entry : targetEntries) {
      if (entry.cascadeType() == AscriptionStatusTransitionCascadeType.DEPENDENT
          && !isDependentCascadeApplicable(fromStatus, toStatus)) {
        continue;
      }

      List<? extends AscriptionEntity> targets = entry.targetService().findCascadeTargetsFrom(sourceType,
          source.getId());

      for (AscriptionEntity target : targets) {
        if (target.getStatus() != fromStatus) {
          if (entry.cascadeType() == AscriptionStatusTransitionCascadeType.CONSTITUTIVE) {
            throw RuleViolationException.of(
                AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_CONSTITUENTS,
                "Constitutive cascade failed: target "
                    + target.getId()
                    + " ("
                    + entry.targetService().getSubjectType().name()
                    + ") is "
                    + target.getStatus().name()
                    + ", expected "
                    + fromStatus.name(),
                "targetId",
                target.getId(),
                "targetType",
                entry.targetService().getSubjectType().name(),
                "targetStatus",
                target.getStatus().name(),
                "expectedStatus",
                fromStatus.name(),
                "cascadeType",
                entry.cascadeType().name());
          }
          if (entry.cascadeType() == AscriptionStatusTransitionCascadeType.GOVERNING) {
            LOG.debug(
                "[{}] Governing cascade skipped: target {} ({}) is {}, expected {}",
                AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_SUBJECTS
                    .getType(),
                target.getId(),
                entry.targetService().getSubjectType().name(),
                target.getStatus().name(),
                fromStatus.name());
          } else if (entry.cascadeType() == AscriptionStatusTransitionCascadeType.DEPENDENT) {
            LOG.debug(
                "[{}] Dependent cascade skipped: target {} ({}) is {}, expected {}",
                AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_DEPENDENTS
                    .getType(),
                target.getId(),
                entry.targetService().getSubjectType().name(),
                target.getStatus().name(),
                fromStatus.name());
          }
          continue;
        }

        DefinitionSubjectType targetType = entry.targetService().getSubjectType();
        try {
          validateRefereePreconditions(target, targetType, fromStatus, toStatus);
        } catch (RuleViolationException e) {
          if (entry.cascadeType() == AscriptionStatusTransitionCascadeType.CONSTITUTIVE) {
            throw RuleViolationException.of(
                AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_CONSTITUENTS,
                "Constitutive cascade blocked: " + e.getMessage(),
                e,
                "cascadeType",
                entry.cascadeType().name());
          }
          if (entry.cascadeType() == AscriptionStatusTransitionCascadeType.GOVERNING) {
            LOG.debug(
                "[{}] Governing cascade skipped (referee precondition): target {} — {}",
                AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_SUBJECTS
                    .getType(),
                target.getId(),
                e.getMessage());
          } else if (entry.cascadeType() == AscriptionStatusTransitionCascadeType.DEPENDENT) {
            LOG.debug(
                "[{}] Dependent cascade skipped (referee precondition): target {} — {}",
                AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_DEPENDENTS
                    .getType(),
                target.getId(),
                e.getMessage());
          }
          continue;
        }

        recordTransition(target, fromStatus, toStatus);
        executeCascades(target, targetType, fromStatus, toStatus);
      }
    }
  }

  private boolean isDependentCascadeApplicable(AscriptionStatusType from, AscriptionStatusType to) {
    Set<AscriptionStatusType> targets = DEPENDENT_CASCADE_SCOPE.get(from);
    return targets != null && targets.contains(to);
  }

  // ======================================================================
  // Governance convergence
  // ======================================================================

  private void handleApproval(DefinitionSubjectType type, AscriptionEntity approved) {
    UUID definitionId = approved.getDefinition().getId();
    AbstractAscriptionService<?> svc = subtypeByType.get(type);
    if (svc == null)
      return;

    List<? extends AscriptionEntity> allAscriptions = svc.findAllByDefinitionId(definitionId);
    for (AscriptionEntity sibling : allAscriptions) {
      if (sibling.getId().equals(approved.getId()))
        continue;
      AscriptionStatusType siblingStatus = sibling.getStatus();
      AscriptionStatusType terminalStatus;
      if (siblingStatus == AscriptionStatusType.DRAFT) {
        terminalStatus = AscriptionStatusType.ABANDONED;
      } else if (siblingStatus == AscriptionStatusType.PROPOSED) {
        terminalStatus = AscriptionStatusType.REJECTED;
      } else {
        continue;
      }
      LOG.debug(
          "[{}] Governance convergence: sibling {} ({}) → {}",
          AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_APPROVAL_CONVERGENCE
              .getType(),
          sibling.getId(),
          siblingStatus.name(),
          terminalStatus.name());
      recordTransition(sibling, siblingStatus, terminalStatus);
    }
  }

  private void handleActivation(DefinitionSubjectType type, AscriptionEntity activating) {
    UUID definitionId = activating.getDefinition().getId();
    AbstractAscriptionService<?> svc = subtypeByType.get(type);
    if (svc == null)
      return;

    List<? extends AscriptionEntity> activeAscriptions = svc.findAllByDefinitionIdAndStatus(definitionId,
        List.of(AscriptionStatusType.ACTIVE));
    for (AscriptionEntity prev : activeAscriptions) {
      if (prev.getId().equals(activating.getId()))
        continue;
      LOG.debug(
          "[{}] Activation handoff: predecessor {} (ACTIVE → DEPRECATED)",
          AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_ACTIVATION_HANDOFF
              .getType(),
          prev.getId());
      recordTransition(prev, AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);
    }
  }
}
