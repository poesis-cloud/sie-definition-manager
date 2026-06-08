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
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
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
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
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
  private static final String INSTRUMENTATION_SCOPE = "cloud.poesis.sie.defman.observability";
  private static final String TRANSITION_SPAN_NAME = "gsm.ascription.transition";
  private static final String ATTR_ASCRIPTION_ID = "gsm.ascription.id";
  private static final String ATTR_STATE_FROM = "gsm.ascription.state.from";
  private static final String ATTR_STATE_TO = "gsm.ascription.state.to";
  private static final String ATTR_TENANT_ID = "gsm.tenant.id";
  private static final String ATTR_COMPONENT = "sie.component";
  private static final String COMPONENT_DEFINITION_MANAGER = "definition-manager";
  private static final String ATTR_SUBJECT_TYPE = "gsm.ascription.subject.type";
  private static final String ATTR_CASCADE_TYPE = "gsm.ascription.cascade.type";
  private static final String ATTR_CASCADE_TARGET_ID = "gsm.ascription.cascade.target.id";
  private static final String ATTR_CASCADE_TARGET_TYPE = "gsm.ascription.cascade.target.type";
  private static final String ATTR_CASCADE_REASON = "gsm.ascription.cascade.reason";
  private static final String EVENT_HOOK_ACTIVATION = "gsm.ascription.hook.activation";
  private static final String EVENT_HOOK_DEACTIVATION = "gsm.ascription.hook.deactivation";
  private static final String EVENT_HOOK_APPROVAL = "gsm.ascription.hook.approval";
  private static final String EVENT_HOOK_REJECTED = "gsm.ascription.hook.rejected";
  private static final String EVENT_HOOK_CASCADE = "gsm.ascription.hook.cascade";
  private static final String EVENT_HOOK_CASCADE_SKIP = "gsm.ascription.hook.cascade-skip";
  private static final String EVENT_HOOK_ERROR = "gsm.ascription.hook.error";
  private static final String OUTCOME_SUCCESS = "success";
  private static final String OUTCOME_SKIPPED = "skipped";
  private static final String OUTCOME_FAILURE = "failure";
  private static final String MDC_TRACE_ID = "trace_id";
  private static final String MDC_SPAN_ID = "span_id";

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
      GlobalOpenTelemetry
        .get()
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

    Span transitionSpan = startTransitionSpan(ascriptionId, currentStatus, targetStatusType);

    try (Scope ignored = transitionSpan.makeCurrent()) {
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
          transitionSpan.addEvent(
              EVENT_HOOK_ACTIVATION,
              Attributes.of(
                  AttributeKey.stringKey(ATTR_SUBJECT_TYPE),
                  type.name()));
          emitCorrelatedLifecycleLog(EVENT_HOOK_ACTIVATION, OUTCOME_SUCCESS);
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
          transitionSpan.addEvent(
              EVENT_HOOK_DEACTIVATION,
              Attributes.of(
                  AttributeKey.stringKey(ATTR_SUBJECT_TYPE),
                  type.name()));
          emitCorrelatedLifecycleLog(EVENT_HOOK_DEACTIVATION, OUTCOME_SUCCESS);
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
          transitionSpan.addEvent(
              EVENT_HOOK_APPROVAL,
              Attributes.of(
                  AttributeKey.stringKey(ATTR_SUBJECT_TYPE),
                  type.name()));
          emitCorrelatedLifecycleLog(EVENT_HOOK_APPROVAL, OUTCOME_SUCCESS);
          handleApproval(type, entity);
        }

        if (targetStatusType == AscriptionStatusType.REJECTED) {
          transitionSpan.addEvent(EVENT_HOOK_REJECTED);
          emitCorrelatedLifecycleLog(EVENT_HOOK_REJECTED, OUTCOME_SUCCESS);
        }

        // 7. Execute cascades
        executeCascades(entity, type, currentStatus, targetStatusType);

        return saved;
      } catch (DataIntegrityViolationException ex) {
        throw PersistenceExceptionTranslationService.translate(ex);
      }
    } catch (RuntimeException ex) {
      transitionSpan.addEvent(EVENT_HOOK_ERROR);
      emitCorrelatedLifecycleLog(EVENT_HOOK_ERROR, OUTCOME_FAILURE);
      transitionSpan.recordException(ex);
      throw ex;
    } finally {
      transitionSpan.end();
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
        Span.current()
            .addEvent(
                EVENT_HOOK_CASCADE_SKIP,
                Attributes.builder()
                    .put(ATTR_CASCADE_TYPE, entry.cascadeType().name())
                    .put(ATTR_CASCADE_TARGET_TYPE, entry.targetService().getSubjectType().name())
                    .put(ATTR_CASCADE_REASON, "dependent-not-applicable")
                    .build());
        emitCorrelatedLifecycleLog(EVENT_HOOK_CASCADE_SKIP, OUTCOME_SKIPPED);
        continue;
      }

      List<? extends AscriptionEntity> targets =
          entry.targetService().findCascadeTargetsFrom(sourceType, source.getId());

      for (AscriptionEntity target : targets) {
        if (target.getStatus() != fromStatus) {
          Span.current()
              .addEvent(
                  EVENT_HOOK_CASCADE_SKIP,
                  Attributes.builder()
                      .put(ATTR_CASCADE_TYPE, entry.cascadeType().name())
                      .put(ATTR_CASCADE_TARGET_ID, String.valueOf(target.getId()))
                      .put(ATTR_CASCADE_TARGET_TYPE, entry.targetService().getSubjectType().name())
                      .put(ATTR_CASCADE_REASON, "status-mismatch")
                      .build());
            emitCorrelatedLifecycleLog(EVENT_HOOK_CASCADE_SKIP, OUTCOME_SKIPPED);
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
          Span.current()
              .addEvent(
                  EVENT_HOOK_CASCADE_SKIP,
                  Attributes.builder()
                      .put(ATTR_CASCADE_TYPE, entry.cascadeType().name())
                      .put(ATTR_CASCADE_TARGET_ID, String.valueOf(target.getId()))
                      .put(ATTR_CASCADE_TARGET_TYPE, entry.targetService().getSubjectType().name())
                      .put(ATTR_CASCADE_REASON, "referee-precondition")
                      .build());
            emitCorrelatedLifecycleLog(EVENT_HOOK_CASCADE_SKIP, OUTCOME_SKIPPED);
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

        Span.current()
            .addEvent(
                EVENT_HOOK_CASCADE,
                Attributes.builder()
                    .put(ATTR_CASCADE_TYPE, entry.cascadeType().name())
                    .put(ATTR_CASCADE_TARGET_ID, String.valueOf(target.getId()))
                    .put(ATTR_CASCADE_TARGET_TYPE, targetType.name())
                    .build());
        emitCorrelatedLifecycleLog(EVENT_HOOK_CASCADE, OUTCOME_SUCCESS);

        transitionCascadeTarget(target, targetType, fromStatus, toStatus);
      }
    }
  }

  private Span startTransitionSpan(
      UUID ascriptionId, AscriptionStatusType fromStatus, AscriptionStatusType toStatus) {
    Span transitionSpan =
        tracer
            .spanBuilder(TRANSITION_SPAN_NAME)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_ASCRIPTION_ID, ascriptionId.toString())
            .setAttribute(ATTR_STATE_FROM, fromStatus.name())
            .setAttribute(ATTR_STATE_TO, toStatus.name())
            .setAttribute(ATTR_COMPONENT, COMPONENT_DEFINITION_MANAGER)
            .startSpan();

    String tenantId = MDC.get(ATTR_TENANT_ID);
    if (tenantId != null && !tenantId.isBlank()) {
      transitionSpan.setAttribute(ATTR_TENANT_ID, tenantId);
    }

    return transitionSpan;
  }

  private void transitionCascadeTarget(
      AscriptionEntity target,
      DefinitionSubjectType targetType,
      AscriptionStatusType fromStatus,
      AscriptionStatusType toStatus) {
    UUID targetId = Objects.requireNonNull(target.getId(), "cascade.target.id");
    Span cascadeSpan = startTransitionSpan(targetId, fromStatus, toStatus);

    try (Scope ignored = cascadeSpan.makeCurrent()) {
      recordTransition(target, fromStatus, toStatus);
      executeCascades(target, targetType, fromStatus, toStatus);
    } catch (RuntimeException ex) {
      cascadeSpan.recordException(ex);
      throw ex;
    } finally {
      cascadeSpan.end();
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

  private void emitCorrelatedLifecycleLog(String eventName, String eventOutcome) {
    Span currentSpan = Span.current();
    SpanContext spanContext = currentSpan.getSpanContext();
    String traceId = spanContext.isValid() ? spanContext.getTraceId() : MDC.get(MDC_TRACE_ID);
    String spanId = spanContext.isValid() ? spanContext.getSpanId() : MDC.get(MDC_SPAN_ID);
    String tenantId = MDC.get(ATTR_TENANT_ID);

    if (OUTCOME_FAILURE.equals(eventOutcome)) {
      emitOtelLifecycleLogRecord(eventName, eventOutcome, traceId, spanId, tenantId, Severity.ERROR);
      return;
    }

    if (OUTCOME_SKIPPED.equals(eventOutcome)) {
      emitOtelLifecycleLogRecord(eventName, eventOutcome, traceId, spanId, tenantId, Severity.WARN);
      return;
    }
    emitOtelLifecycleLogRecord(eventName, eventOutcome, traceId, spanId, tenantId, Severity.INFO);
    }

    private void emitOtelLifecycleLogRecord(
      String eventName,
      String eventOutcome,
      String traceId,
      String spanId,
      String tenantId,
      Severity severity) {
    otelLogger
      .logRecordBuilder()
      .setContext(Context.current())
      .setSeverity(severity)
      .setSeverityText(severity.name())
      .setBody(
        String.format(
          "event.correlation event.name=%s event.outcome=%s sie.component=%s",
          eventName, eventOutcome, COMPONENT_DEFINITION_MANAGER))
      .setAttribute("event.name", eventName)
      .setAttribute("event.outcome", eventOutcome)
      .setAttribute("sie.component", COMPONENT_DEFINITION_MANAGER)
      .setAttribute("trace_id", traceId == null ? "" : traceId)
      .setAttribute("span_id", spanId == null ? "" : spanId)
      .setAttribute("gsm.tenant.id", tenantId == null ? "" : tenantId)
      .emit();
  }
}
