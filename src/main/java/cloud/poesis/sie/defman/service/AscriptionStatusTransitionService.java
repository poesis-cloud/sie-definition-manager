package cloud.poesis.sie.defman.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import jakarta.persistence.EntityManager;

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
  private static final String INSTRUMENTATION_SCOPE = "cloud.poesis.sie.defman.observability";
  private static final String TRANSITION_SPAN_NAME = "gsm.ascription.transition";
  private static final String ATTR_ASCRIPTION_ID = "gsm.ascription.id";
  private static final String ATTR_STATE_FROM = "gsm.ascription.state.from";
  private static final String ATTR_STATE_TO = "gsm.ascription.state.to";
  private static final String ATTR_TENANT_ID = "gsm.tenant.id";
  private static final String ATTR_COMPONENT = "sie.component";
  private static final String COMPONENT_DEFINITION_MANAGER = "definition-manager";
  private static final String ATTR_CASCADE_TYPE = "gsm.ascription.cascade.type";
  private static final String ATTR_CASCADE_TARGET_ID = "gsm.ascription.cascade.target.id";
  private static final String ATTR_CASCADE_TARGET_TYPE = "gsm.ascription.cascade.target.type";
  private static final String ATTR_CASCADE_REASON = "gsm.ascription.cascade.reason";
  private static final String EVENT_HOOK_CASCADE = "gsm.ascription.hook.cascade";
  private static final String EVENT_HOOK_CASCADE_SKIP = "gsm.ascription.hook.cascade-skip";
  private static final String EVENT_HOOK_PERSISTENCE = "gsm.ascription.hook.persistence";
  private static final String EVENT_HOOK_ACTIVATION_HANDOFF =
      "gsm.ascription.hook.activation-handoff";
  private static final String EVENT_HOOK_APPROVAL_CONVERGENCE =
      "gsm.ascription.hook.approval-convergence";
  private static final String OUTCOME_SUCCESS = "success";
  private static final String OUTCOME_SKIPPED = "skipped";
  private static final String OUTCOME_FAILURE = "failure";

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
  private final Tracer tracer;
  private final io.opentelemetry.api.logs.Logger otelLogger;

  private Map<DefinitionSubjectType, AscriptionSubtypeService<?>> subtypeByType;
  private Map<DefinitionSubjectType, List<CascadeTargetEntry>> cascadeTargetsBySourceType;

  @Autowired
  public AscriptionStatusTransitionService(
      AscriptionStatusTransitionRepository transitionRepo,
      AscriptionStateMachineService stateMachine,
      EntityManager entityManager,
      List<AscriptionSubtypeService<?>> subtypeServices) {
    this(
        transitionRepo,
        stateMachine,
        entityManager,
        subtypeServices,
        GlobalOpenTelemetry.getTracer(INSTRUMENTATION_SCOPE, "1"));
  }

  AscriptionStatusTransitionService(
      AscriptionStatusTransitionRepository transitionRepo,
      AscriptionStateMachineService stateMachine,
      EntityManager entityManager,
      List<AscriptionSubtypeService<?>> subtypeServices,
      Tracer tracer) {
    this.transitionRepo = transitionRepo;
    this.stateMachine = stateMachine;
    this.entityManager = entityManager;
    this.subtypeServices = List.copyOf(subtypeServices);
    this.tracer = tracer;
    this.otelLogger =
        GlobalOpenTelemetry.get()
            .getLogsBridge()
            .loggerBuilder(INSTRUMENTATION_SCOPE)
            .setInstrumentationVersion("1")
            .build();
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
    try {
      AscriptionStatusTransitionEntity transition =
          transitionRepo.save(new AscriptionStatusTransitionEntity(entity, from, to));
      entityManager.flush();
      entityManager.detach(transition);
      UUID transitionId = Objects.requireNonNull(transition.getId(), "transition.id");
      AscriptionStatusTransitionEntity recorded =
          transitionRepo.findById(transitionId).orElseThrow();
      emitLifecycleEvent(
          EVENT_HOOK_PERSISTENCE,
          OUTCOME_SUCCESS,
          Attributes.builder()
              .put(ATTR_ASCRIPTION_ID, String.valueOf(entity.getId()))
              .put(ATTR_STATE_FROM, from.name())
              .put(ATTR_STATE_TO, to.name())
              .build());
      return recorded;
    } catch (RuntimeException ex) {
      emitLifecycleEvent(
          EVENT_HOOK_PERSISTENCE,
          OUTCOME_FAILURE,
          Attributes.builder()
              .put(ATTR_ASCRIPTION_ID, String.valueOf(entity.getId()))
              .put(ATTR_STATE_FROM, from.name())
              .put(ATTR_STATE_TO, to.name())
              .build());
      throw ex;
    }
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

    enrichTransitionSpan(Span.current(), ascriptionId, currentStatus, targetStatusType);

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
        emitLifecycleEvent(
            EVENT_HOOK_CASCADE_SKIP,
            OUTCOME_SKIPPED,
            Attributes.builder()
                .put(ATTR_CASCADE_TYPE, entry.cascadeType().name())
                .put(ATTR_CASCADE_TARGET_TYPE, entry.targetService().getSubjectType().name())
                .put(ATTR_CASCADE_REASON, "dependent-not-applicable")
                .build());
        continue;
      }

      List<? extends AscriptionEntity> targets =
          entry.targetService().findCascadeTargetsFrom(sourceType, source.getId());

      for (AscriptionEntity target : targets) {
        if (target.getStatus() != fromStatus) {
          emitLifecycleEvent(
            EVENT_HOOK_CASCADE_SKIP,
            OUTCOME_SKIPPED,
            Attributes.builder()
                .put(ATTR_CASCADE_TYPE, entry.cascadeType().name())
                .put(ATTR_CASCADE_TARGET_ID, String.valueOf(target.getId()))
                .put(ATTR_CASCADE_TARGET_TYPE, entry.targetService().getSubjectType().name())
                .put(ATTR_CASCADE_REASON, "status-mismatch")
                .build());
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
          continue;
        }

        DefinitionSubjectType targetType = entry.targetService().getSubjectType();
        try {
          List<Map.Entry<AscriptionEntity, String>> refs =
              entry.targetService().getRefereeReferences(target);
          stateMachine.validateRefereePreconditions(refs, fromStatus, toStatus);
        } catch (RuleViolationException e) {
          emitLifecycleEvent(
            EVENT_HOOK_CASCADE_SKIP,
            OUTCOME_SKIPPED,
            Attributes.builder()
                .put(ATTR_CASCADE_TYPE, entry.cascadeType().name())
                .put(ATTR_CASCADE_TARGET_ID, String.valueOf(target.getId()))
                .put(ATTR_CASCADE_TARGET_TYPE, entry.targetService().getSubjectType().name())
                .put(ATTR_CASCADE_REASON, "referee-precondition")
                .build());
          if (entry.cascadeType() == AscriptionStatusTransitionCascadeType.CONSTITUTIVE) {
            throw RuleViolationException.of(
                AscriptionStatusTransitionRuleType
                    .ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_CONSTITUENTS,
                "Constitutive cascade blocked: " + e.getMessage(),
                e,
                "cascadeType",
                entry.cascadeType().name());
          }
          continue;
        }

        transitionCascadeTarget(target, targetType, entry.cascadeType(), fromStatus, toStatus);
      }
    }
  }

  private static void enrichTransitionSpan(
      Span span, UUID ascriptionId, AscriptionStatusType fromStatus, AscriptionStatusType toStatus) {
    if (span == null) {
      return;
    }

    span.setAttribute(ATTR_ASCRIPTION_ID, ascriptionId.toString());
    span.setAttribute(ATTR_STATE_FROM, fromStatus.name());
    span.setAttribute(ATTR_STATE_TO, toStatus.name());
    span.setAttribute(ATTR_COMPONENT, COMPONENT_DEFINITION_MANAGER);

    String tenantId = MDC.get(ATTR_TENANT_ID);
    if (tenantId != null && !tenantId.isBlank()) {
      span.setAttribute(ATTR_TENANT_ID, tenantId);
    }
  }

  private void transitionCascadeTarget(
      AscriptionEntity target,
      DefinitionSubjectType targetType,
      AscriptionStatusTransitionCascadeType cascadeType,
      AscriptionStatusType fromStatus,
      AscriptionStatusType toStatus) {
    UUID targetId = Objects.requireNonNull(target.getId(), "cascade.target.id");
    Span cascadeSpan = Span.current();

    emitLifecycleEvent(
        cascadeSpan,
        EVENT_HOOK_CASCADE,
        OUTCOME_SUCCESS,
        Attributes.builder()
            .put(ATTR_CASCADE_TYPE, cascadeType.name())
            .put(ATTR_CASCADE_TARGET_ID, targetId.toString())
            .put(ATTR_CASCADE_TARGET_TYPE, targetType.name())
            .build());
    recordTransition(target, fromStatus, toStatus);
    executeCascades(target, targetType, fromStatus, toStatus);
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
      emitLifecycleEvent(
          EVENT_HOOK_APPROVAL_CONVERGENCE,
          OUTCOME_SUCCESS,
          Attributes.builder()
              .put(ATTR_ASCRIPTION_ID, String.valueOf(sibling.getId()))
              .put(ATTR_STATE_FROM, siblingStatus.name())
              .put(ATTR_STATE_TO, terminalStatus.name())
              .build());
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
      emitLifecycleEvent(
          EVENT_HOOK_ACTIVATION_HANDOFF,
          OUTCOME_SUCCESS,
          Attributes.builder()
              .put(ATTR_ASCRIPTION_ID, String.valueOf(prev.getId()))
              .put(ATTR_STATE_FROM, AscriptionStatusType.ACTIVE.name())
              .put(ATTR_STATE_TO, AscriptionStatusType.DEPRECATED.name())
              .build());
      recordTransition(prev, AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);
    }
  }

  private void emitLifecycleEvent(String eventName, String eventOutcome, Attributes attributes) {
    Span.current().addEvent(eventName, attributes);
    emitCorrelatedLifecycleLog(eventName, eventOutcome);
  }

  private void emitLifecycleEvent(
      Span span, String eventName, String eventOutcome, Attributes attributes) {
    span.addEvent(eventName, attributes);
    emitCorrelatedLifecycleLog(span, eventName, eventOutcome);
  }

  private void emitCorrelatedLifecycleLog(String eventName, String eventOutcome) {
    emitCorrelatedLifecycleLog(Span.current(), eventName, eventOutcome);
  }

  private void emitCorrelatedLifecycleLog(Span span, String eventName, String eventOutcome) {
    if (OUTCOME_SUCCESS.equals(eventOutcome)) {
      return;
    }

    Severity severity = OUTCOME_FAILURE.equals(eventOutcome) ? Severity.ERROR : Severity.WARN;
    emitOtelLifecycleLogRecord(span, eventName, eventOutcome, severity);
  }

  private void emitOtelLifecycleLogRecord(
      Span span,
      String eventName,
      String eventOutcome,
      Severity severity) {
    var builder =
        otelLogger
            .logRecordBuilder()
            .setContext(Context.current())
            .setSeverity(severity)
            .setSeverityText(severity.name())
            .setAttribute("event.name", eventName)
            .setAttribute("event.outcome", eventOutcome)
            .setAttribute("sie.component", COMPONENT_DEFINITION_MANAGER);

    String tenantId = MDC.get(ATTR_TENANT_ID);
    if (tenantId != null && !tenantId.isBlank()) {
      builder.setAttribute("gsm.tenant.id", tenantId);
    }

    if (severity != Severity.INFO) {
      builder.setBody(
          String.format(
              "Ascription lifecycle milestone %s with outcome %s", eventName, eventOutcome));
    }

    builder.emit();
  }
}
