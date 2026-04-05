package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AscriptionStatusTransitionRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for {@link AscriptionStatusTransitionEntity}. Owns {@link
 * AscriptionStatusTransitionRepository} exclusively — all transition persistence, queries, and
 * lifecycle orchestration go through this service.
 *
 * <p>Persistence operations (recording and querying transitions) are handled directly. Lifecycle
 * orchestration (state machine validation, referee preconditions, activation hooks, governance
 * convergence, and cascade dispatch) is coordinated here, with pure state machine rules delegated
 * to {@link AscriptionStateMachineService}.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
@Transactional("transactionManager")
public class AscriptionStatusTransitionService implements SmartInitializingSingleton {

  private static final Logger LOG =
      LoggerFactory.getLogger(AscriptionStatusTransitionService.class);

  // ======================================================================
  // Cascade graph (built at startup from subtype declarations)
  // ======================================================================

  private record CascadeTargetEntry(
      AscriptionSubtypeService<?> targetService,
      AscriptionStatusTransitionCascadeType cascadeType) {}

  private final AscriptionStatusTransitionRepository transitionRepo;
  private final AscriptionStateMachineService stateMachine;
  private final EntityManager entityManager;
  private final List<AscriptionSubtypeService<?>> subtypeServices;

  private Map<DefinitionSubjectType, AscriptionSubtypeService<?>> subtypeByType;
  private Map<DefinitionSubjectType, List<CascadeTargetEntry>> cascadeTargetsBySourceType;

  public AscriptionStatusTransitionService(
      AscriptionStatusTransitionRepository transitionRepo,
      AscriptionStateMachineService stateMachine,
      EntityManager entityManager,
      List<AscriptionSubtypeService<?>> subtypeServices) {
    this.transitionRepo = transitionRepo;
    this.stateMachine = stateMachine;
    this.entityManager = entityManager;
    this.subtypeServices = List.copyOf(subtypeServices);
  }

  /** Builds the subtype lookup map and cascade graph after all singleton beans are constructed. */
  @Override
  public void afterSingletonsInstantiated() {
    Map<DefinitionSubjectType, AscriptionSubtypeService<?>> byType =
        new EnumMap<>(DefinitionSubjectType.class);
    for (AscriptionSubtypeService<?> svc : subtypeServices) {
      byType.put(svc.getSubjectType(), svc);
    }
    this.subtypeByType = Map.copyOf(byType);

    Map<DefinitionSubjectType, List<CascadeTargetEntry>> cascadeMap =
        new EnumMap<>(DefinitionSubjectType.class);
    for (AscriptionSubtypeService<?> svc : subtypeServices) {
      for (var entry : svc.getCascadeTargetRoles().entrySet()) {
        cascadeMap
            .computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
            .add(new CascadeTargetEntry(svc, entry.getValue()));
      }
    }
    this.cascadeTargetsBySourceType = Map.copyOf(cascadeMap);
  }

  // ======================================================================
  // Persistence operations
  // ======================================================================

  private AscriptionStatusTransitionEntity recordTransition(
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

  // ======================================================================
  // TRANSITION (lifecycle orchestrator)
  // ======================================================================

  /**
   * Executes a lifecycle transition with full lifecycle governance: state machine validation,
   * referee preconditions, activation hooks, governance convergence, and cascade dispatch.
   *
   * @param ascriptionId the ascription to transition
   * @param targetStatus the requested target status name
   * @return the persisted transition record
   * @throws ResourceNotFoundException if the ascription does not exist
   * @throws RuleViolationException if the transition violates state machine, referee, or cascade
   *     constraints
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
    stateMachine.validateTransition(ascriptionId, currentStatus, targetStatusType);

    // 2. Check referee preconditions
    validateRefereePreconditions(entity, type, currentStatus, targetStatusType);

    // 3. Activation uniqueness (Structure purpose, Mechanism function, Archetype
    // title)
    if (targetStatusType == AscriptionStatusType.ACTIVE) {
      validateActivationUniqueness(entity, type);
    }

    // 3b. Subtype-specific activation hook
    if (targetStatusType == AscriptionStatusType.ACTIVE) {
      AscriptionSubtypeService<?> svc = subtypeByType.get(type);
      if (svc != null) {
        svc.onActivation(entity);
      }
    }

    // 3c. Subtype-specific deactivation hook (leaving in-effect)
    if (EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)
            .contains(currentStatus)
        && !EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)
            .contains(targetStatusType)) {
      AscriptionSubtypeService<?> svc = subtypeByType.get(type);
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
      AscriptionStatusTransitionEntity saved =
          recordTransition(entity, currentStatus, targetStatusType);

      // 6. Governance convergence (APPROVED → sibling termination)
      if (targetStatusType == AscriptionStatusType.APPROVED) {
        handleApproval(type, entity);
      }

      // 7. Execute cascades
      executeCascades(entity, type, currentStatus, targetStatusType);

      return saved;
    } catch (DataIntegrityViolationException ex) {
      throw PersistenceExceptionTranslationService.translate(ex);
    }
  }

  // ======================================================================
  // Activation uniqueness validation
  // ======================================================================

  private void validateActivationUniqueness(AscriptionEntity entity, DefinitionSubjectType type) {
    AscriptionSubtypeService<?> subtypeService = subtypeByType.get(type);
    if (subtypeService != null) {
      subtypeService.validateActivationUniqueness(entity);
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

    AscriptionSubtypeService<?> subtypeService = subtypeByType.get(type);
    if (subtypeService == null) {
      return;
    }

    List<Map.Entry<AscriptionEntity, String>> refs = subtypeService.getRefereeReferences(entity);
    stateMachine.validateRefereePreconditions(refs, from, to);
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
          && !stateMachine.isDependentCascadeApplicable(fromStatus, toStatus)) {
        continue;
      }

      List<? extends AscriptionEntity> targets =
          entry.targetService().findCascadeTargetsFrom(sourceType, source.getId());

      for (AscriptionEntity target : targets) {
        if (target.getStatus() != fromStatus) {
          if (entry.cascadeType() == AscriptionStatusTransitionCascadeType.CONSTITUTIVE) {
            throw RuleViolationException.of(
                AscriptionStatusTransitionRuleType
                    .ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_CONSTITUENTS,
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
                AscriptionStatusTransitionRuleType
                    .ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_DEPENDENTS
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
          List<Map.Entry<AscriptionEntity, String>> refs =
              entry.targetService().getRefereeReferences(target);
          stateMachine.validateRefereePreconditions(refs, fromStatus, toStatus);
        } catch (RuleViolationException e) {
          if (entry.cascadeType() == AscriptionStatusTransitionCascadeType.CONSTITUTIVE) {
            throw RuleViolationException.of(
                AscriptionStatusTransitionRuleType
                    .ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_CONSTITUENTS,
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
                AscriptionStatusTransitionRuleType
                    .ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_DEPENDENTS
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

  // ======================================================================
  // Governance convergence
  // ======================================================================

  private void handleApproval(DefinitionSubjectType type, AscriptionEntity approved) {
    UUID definitionId = approved.getDefinition().getId();
    AscriptionSubtypeService<?> svc = subtypeByType.get(type);
    if (svc == null) return;

    List<? extends AscriptionEntity> allAscriptions = svc.findAllByDefinitionId(definitionId);
    for (AscriptionEntity sibling : allAscriptions) {
      if (sibling.getId().equals(approved.getId())) continue;
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
    AscriptionSubtypeService<?> svc = subtypeByType.get(type);
    if (svc == null) return;

    List<? extends AscriptionEntity> activeAscriptions =
        svc.findAllByDefinitionIdAndStatus(definitionId, List.of(AscriptionStatusType.ACTIVE));
    for (AscriptionEntity prev : activeAscriptions) {
      if (prev.getId().equals(activating.getId())) continue;
      LOG.debug(
          "[{}] Activation handoff: predecessor {} (ACTIVE → DEPRECATED)",
          AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_ACTIVATION_HANDOFF
              .getType(),
          prev.getId());
      recordTransition(prev, AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);
    }
  }
}
