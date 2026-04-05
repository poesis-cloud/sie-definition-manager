package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
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
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle orchestrator for GSM ascription transitions. Coordinates subtype services for referee
 * resolution, cascade dispatch, governance convergence, activation handoff, and lifecycle hooks.
 * Delegates state machine validation and transition recording to {@link
 * AscriptionStateMachineService}.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
@Transactional("transactionManager")
public class AscriptionLifecycleOrchestrationService implements SmartInitializingSingleton {

  private static final Logger LOG =
      LoggerFactory.getLogger(AscriptionLifecycleOrchestrationService.class);

  // ======================================================================
  // Cascade graph (built at startup from subtype declarations)
  // ======================================================================

  private record CascadeTargetEntry(
      SubtypeHandler<?> targetService, AscriptionStatusTransitionCascadeType cascadeType) {}

  private final AscriptionStateMachineService stateMachine;
  private final EntityManager entityManager;
  private final List<SubtypeHandler<?>> subtypeServices;

  private Map<DefinitionSubjectType, SubtypeHandler<?>> subtypeByType;
  private Map<DefinitionSubjectType, List<CascadeTargetEntry>> cascadeTargetsBySourceType;

  public AscriptionLifecycleOrchestrationService(
      AscriptionStateMachineService stateMachine,
      EntityManager entityManager,
      List<SubtypeHandler<?>> subtypeServices) {
    this.stateMachine = stateMachine;
    this.entityManager = entityManager;
    this.subtypeServices = List.copyOf(subtypeServices);
  }

  /** Builds the subtype lookup map and cascade graph after all singleton beans are constructed. */
  @Override
  public void afterSingletonsInstantiated() {
    Map<DefinitionSubjectType, SubtypeHandler<?>> byType =
        new EnumMap<>(DefinitionSubjectType.class);
    for (SubtypeHandler<?> svc : subtypeServices) {
      byType.put(svc.getSubjectType(), svc);
    }
    this.subtypeByType = Map.copyOf(byType);

    Map<DefinitionSubjectType, List<CascadeTargetEntry>> cascadeMap =
        new EnumMap<>(DefinitionSubjectType.class);
    for (SubtypeHandler<?> svc : subtypeServices) {
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
   * Executes a lifecycle transition with full lifecycle governance: validation, referee
   * preconditions, cascades, convergence.
   *
   * @param ascriptionId the ascription to transition
   * @param targetStatus the requested target status
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
      SubtypeHandler<?> svc = subtypeByType.get(type);
      if (svc != null) {
        svc.onActivation(entity);
      }
    }

    // 3c. Subtype-specific deactivation hook (leaving in-effect)
    if (EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)
            .contains(currentStatus)
        && !EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)
            .contains(targetStatusType)) {
      SubtypeHandler<?> svc = subtypeByType.get(type);
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
          stateMachine.recordTransition(entity, currentStatus, targetStatusType);

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
    SubtypeHandler<?> subtypeService = subtypeByType.get(type);
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

    SubtypeHandler<?> subtypeService = subtypeByType.get(type);
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

        stateMachine.recordTransition(target, fromStatus, toStatus);
        executeCascades(target, targetType, fromStatus, toStatus);
      }
    }
  }

  // ======================================================================
  // Governance convergence
  // ======================================================================

  private void handleApproval(DefinitionSubjectType type, AscriptionEntity approved) {
    UUID definitionId = approved.getDefinition().getId();
    SubtypeHandler<?> svc = subtypeByType.get(type);
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
      stateMachine.recordTransition(sibling, siblingStatus, terminalStatus);
    }
  }

  private void handleActivation(DefinitionSubjectType type, AscriptionEntity activating) {
    UUID definitionId = activating.getDefinition().getId();
    SubtypeHandler<?> svc = subtypeByType.get(type);
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
      stateMachine.recordTransition(
          prev, AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);
    }
  }
}
