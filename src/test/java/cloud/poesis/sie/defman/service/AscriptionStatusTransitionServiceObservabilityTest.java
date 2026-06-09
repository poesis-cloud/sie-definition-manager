package cloud.poesis.sie.defman.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AscriptionStatusTransitionRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class AscriptionStatusTransitionServiceObservabilityTest {

  private static final String TRANSITION_SPAN_NAME = "gsm.ascription.transition";
  private static final String EVENT_HOOK_ACTIVATION = "gsm.ascription.hook.activation";
  private static final String EVENT_HOOK_DEACTIVATION = "gsm.ascription.hook.deactivation";
  private static final String EVENT_HOOK_APPROVAL = "gsm.ascription.hook.approval";
  private static final String EVENT_HOOK_CASCADE = "gsm.ascription.hook.cascade";
  private static final String EVENT_HOOK_CASCADE_SKIP = "gsm.ascription.hook.cascade-skip";
  private static final String EVENT_HOOK_PERSISTENCE = "gsm.ascription.hook.persistence";
  private static final String EVENT_HOOK_ACTIVATION_HANDOFF =
      "gsm.ascription.hook.activation-handoff";
  private static final String EVENT_HOOK_APPROVAL_CONVERGENCE =
      "gsm.ascription.hook.approval-convergence";
  private static final String EVENT_HOOK_ERROR = "gsm.ascription.hook.error";
  private static final String OUTCOME_SUCCESS = "success";
  private static final String OUTCOME_SKIPPED = "skipped";
  private static final String OUTCOME_FAILURE = "failure";

  @RegisterExtension static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

  @Mock private AscriptionStatusTransitionRepository transitionRepo;
  @Mock private EntityManager entityManager;
  @Mock private AscriptionStateMachineService stateMachine;

  private AscriptionStatusTransitionService service;

  @BeforeEach
  void setUp() {
    service = createService(List.of());
  }

  @Test
  void acceptedTransition_emitsChildSpanWithLifecycleAttributes() {
    UUID ascriptionId = UUID.randomUUID();
    AscriptionEntity entity =
        stubEntity(ascriptionId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT);
    when(entityManager.find(AscriptionEntity.class, ascriptionId)).thenReturn(entity);
    stubRepoSave();

    Tracer tracer = otel.getOpenTelemetry().getTracer("test.ascription.transition", "1");
    Span parent = tracer.spanBuilder("active-flow").startSpan();
    MDC.put("gsm.tenant.id", "tenant-alpha");
    try (Scope ignored = parent.makeCurrent()) {
      service.transition(ascriptionId, "PROPOSED");
    } finally {
      MDC.remove("gsm.tenant.id");
      parent.end();
    }

    SpanData parentSpan =
        otel.getSpans().stream()
            .filter(s -> s.getName().equals("active-flow"))
            .findFirst()
            .orElseThrow();
    SpanData transitionSpan = findTransitionSpanByAscription(ascriptionId);

    assertThat(transitionSpan.getParentSpanId()).isEqualTo(parentSpan.getSpanId());
    assertThat(transitionSpan.getAttributes().get(AttributeKey.stringKey("gsm.ascription.id")))
        .isEqualTo(ascriptionId.toString());
    assertThat(
            transitionSpan.getAttributes().get(AttributeKey.stringKey("gsm.ascription.state.from")))
        .isEqualTo("DRAFT");
    assertThat(
            transitionSpan.getAttributes().get(AttributeKey.stringKey("gsm.ascription.state.to")))
        .isEqualTo("PROPOSED");
    assertThat(transitionSpan.getAttributes().get(AttributeKey.stringKey("gsm.tenant.id")))
        .isEqualTo("tenant-alpha");
    assertThat(transitionSpan.getAttributes().get(AttributeKey.stringKey("sie.component")))
        .isEqualTo("definition-manager");
  }

  @Test
  void acceptedTransition_emitsPersistenceEventAndCorrelatedInfoLog() {
    UUID ascriptionId = UUID.randomUUID();
    AscriptionEntity entity =
        stubEntity(ascriptionId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT);
    when(entityManager.find(AscriptionEntity.class, ascriptionId)).thenReturn(entity);
    stubRepoSave();

    service.transition(ascriptionId, "PROPOSED");

    SpanData transitionSpan = findTransitionSpanByAscription(ascriptionId);
    assertThat(transitionSpan.getEvents())
        .anyMatch(
            event ->
                event.getName().equals(EVENT_HOOK_PERSISTENCE)
                    && "DRAFT"
                        .equals(
                            event
                                .getAttributes()
                                .get(AttributeKey.stringKey("gsm.ascription.state.from")))
                    && "PROPOSED"
                        .equals(
                            event
                                .getAttributes()
                                .get(AttributeKey.stringKey("gsm.ascription.state.to"))));

    LogRecordData lifecycleLog = findLifecycleLogRecord(EVENT_HOOK_PERSISTENCE, OUTCOME_SUCCESS);
    assertThat(lifecycleLog.getSeverity()).isEqualTo(Severity.INFO);
    assertThat(hasNoBody(lifecycleLog)).isTrue();
    assertThat(lifecycleLog.getAttributes().get(AttributeKey.stringKey("gsm.tenant.id")))
        .isNull();
  }

  @Test
  void activationTransition_emitsActivationHookEventOnParentTransitionSpan() {
    UUID ascriptionId = UUID.randomUUID();
    AscriptionEntity entity =
        stubEntity(ascriptionId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.APPROVED);
    when(entityManager.find(AscriptionEntity.class, ascriptionId)).thenReturn(entity);

    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> structureSubtype =
        mock(AscriptionSubtypeService.class);
    when(structureSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    when(structureSubtype.getCascadeTargetRoles()).thenReturn(Map.of());
    when(structureSubtype.findAllByDefinitionIdAndStatus(any(), any())).thenReturn(List.of());

    service = createService(List.of(structureSubtype));
    stubRepoSave();

    service.transition(ascriptionId, "ACTIVE");

    SpanData transitionSpan = findTransitionSpanByAscription(ascriptionId);
    assertThat(transitionSpan.getEvents())
        .anyMatch(event -> event.getName().equals(EVENT_HOOK_ACTIVATION));
    assertThat(
            otel.getSpans().stream()
                .filter(span -> span.getName().equals(TRANSITION_SPAN_NAME))
                .count())
        .isEqualTo(1L);
  }

  @Test
  void deactivationTransition_emitsDeactivationEventAndInfoLogWithoutBody() {
    UUID ascriptionId = UUID.randomUUID();
    AscriptionEntity entity =
        stubEntity(ascriptionId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE);
    when(entityManager.find(AscriptionEntity.class, ascriptionId)).thenReturn(entity);

    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> structureSubtype =
        mock(AscriptionSubtypeService.class);
    when(structureSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    when(structureSubtype.getCascadeTargetRoles()).thenReturn(Map.of());

    service = createService(List.of(structureSubtype));
    stubRepoSave();

    service.transition(ascriptionId, "SUSPENDED");

    SpanData transitionSpan = findTransitionSpanByAscription(ascriptionId);
    assertThat(transitionSpan.getEvents())
        .anyMatch(event -> event.getName().equals(EVENT_HOOK_DEACTIVATION));

    LogRecordData lifecycleLog = findLifecycleLogRecord(EVENT_HOOK_DEACTIVATION, OUTCOME_SUCCESS);
    assertThat(lifecycleLog.getSeverity()).isEqualTo(Severity.INFO);
    assertThat(hasNoBody(lifecycleLog)).isTrue();
  }

  @Test
  void approvedTransition_emitsApprovalHookEventOnParentTransitionSpan() {
    UUID ascriptionId = UUID.randomUUID();
    AscriptionEntity entity =
        stubEntity(ascriptionId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.PROPOSED);
    when(entityManager.find(AscriptionEntity.class, ascriptionId)).thenReturn(entity);

    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> structureSubtype =
        mock(AscriptionSubtypeService.class);
    when(structureSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    when(structureSubtype.getCascadeTargetRoles()).thenReturn(Map.of());
    when(structureSubtype.findAllByDefinitionId(any())).thenReturn(List.of(entity));

    service = createService(List.of(structureSubtype));
    stubRepoSave();

    service.transition(ascriptionId, "APPROVED");

    SpanData transitionSpan = findTransitionSpanByAscription(ascriptionId);
    assertThat(transitionSpan.getEvents())
        .anyMatch(event -> event.getName().equals(EVENT_HOOK_APPROVAL));
  }

  @Test
  void activationHandoff_emitsGovernanceEventForPreviousActiveSibling() {
    UUID activatingId = UUID.randomUUID();
    UUID previousActiveId = UUID.randomUUID();
    AscriptionEntity activating =
        stubEntity(activatingId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.APPROVED);
    AscriptionEntity previousActive =
        stubEntity(previousActiveId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE);
    when(entityManager.find(AscriptionEntity.class, activatingId)).thenReturn(activating);

    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> structureSubtype =
        mock(AscriptionSubtypeService.class);
    when(structureSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    when(structureSubtype.getCascadeTargetRoles()).thenReturn(Map.of());
    when(structureSubtype.findAllByDefinitionIdAndStatus(any(), any()))
        .thenReturn(List.of(activating, previousActive));

    service = createService(List.of(structureSubtype));
    stubRepoSave();

    service.transition(activatingId, "ACTIVE");

    SpanData transitionSpan = findTransitionSpanByAscription(activatingId);
    assertThat(transitionSpan.getEvents())
        .anyMatch(
            event ->
                event.getName().equals(EVENT_HOOK_ACTIVATION_HANDOFF)
                    && previousActiveId
                        .toString()
                        .equals(
                            event.getAttributes().get(AttributeKey.stringKey("gsm.ascription.id")))
                    && "ACTIVE"
                        .equals(
                            event
                                .getAttributes()
                                .get(AttributeKey.stringKey("gsm.ascription.state.from")))
                    && "DEPRECATED"
                        .equals(
                            event
                                .getAttributes()
                                .get(AttributeKey.stringKey("gsm.ascription.state.to"))));

    LogRecordData lifecycleLog =
        findLifecycleLogRecord(EVENT_HOOK_ACTIVATION_HANDOFF, OUTCOME_SUCCESS);
    assertThat(lifecycleLog.getSeverity()).isEqualTo(Severity.INFO);
  }

  @Test
  void approvalConvergence_emitsGovernanceEventsForTerminatedSiblings() {
    UUID approvedId = UUID.randomUUID();
    UUID draftSiblingId = UUID.randomUUID();
    UUID proposedSiblingId = UUID.randomUUID();
    AscriptionEntity approved =
        stubEntity(approvedId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.PROPOSED);
    AscriptionEntity draftSibling =
        stubEntity(draftSiblingId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT);
    AscriptionEntity proposedSibling =
        stubEntity(
            proposedSiblingId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.PROPOSED);
    when(entityManager.find(AscriptionEntity.class, approvedId)).thenReturn(approved);

    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> structureSubtype =
        mock(AscriptionSubtypeService.class);
    when(structureSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    when(structureSubtype.getCascadeTargetRoles()).thenReturn(Map.of());
    when(structureSubtype.findAllByDefinitionId(any()))
        .thenReturn(List.of(approved, draftSibling, proposedSibling));

    service = createService(List.of(structureSubtype));
    stubRepoSave();

    service.transition(approvedId, "APPROVED");

    SpanData transitionSpan = findTransitionSpanByAscription(approvedId);
    assertThat(transitionSpan.getEvents())
        .anyMatch(
            event ->
                event.getName().equals(EVENT_HOOK_APPROVAL_CONVERGENCE)
                    && draftSiblingId
                        .toString()
                        .equals(
                            event.getAttributes().get(AttributeKey.stringKey("gsm.ascription.id")))
                    && "DRAFT"
                        .equals(
                            event
                                .getAttributes()
                                .get(AttributeKey.stringKey("gsm.ascription.state.from")))
                    && "ABANDONED"
                        .equals(
                            event
                                .getAttributes()
                                .get(AttributeKey.stringKey("gsm.ascription.state.to"))))
        .anyMatch(
            event ->
                event.getName().equals(EVENT_HOOK_APPROVAL_CONVERGENCE)
                    && proposedSiblingId
                        .toString()
                        .equals(
                            event.getAttributes().get(AttributeKey.stringKey("gsm.ascription.id")))
                    && "PROPOSED"
                        .equals(
                            event
                                .getAttributes()
                                .get(AttributeKey.stringKey("gsm.ascription.state.from")))
                    && "REJECTED"
                        .equals(
                            event
                                .getAttributes()
                                .get(AttributeKey.stringKey("gsm.ascription.state.to"))));

    LogRecordData lifecycleLog =
        findLifecycleLogRecord(EVENT_HOOK_APPROVAL_CONVERGENCE, OUTCOME_SUCCESS);
    assertThat(lifecycleLog.getSeverity()).isEqualTo(Severity.INFO);
  }

  @Test
  void cascadedTransitions_preserveParentChildLineage() {
    UUID sourceId = UUID.randomUUID();
    UUID mechanismId = UUID.randomUUID();
    UUID effectorId = UUID.randomUUID();

    AscriptionEntity source =
        stubEntity(sourceId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT);
    AscriptionEntity mechanism =
        stubEntity(mechanismId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.DRAFT);
    AscriptionEntity effector =
        stubEntity(effectorId, DefinitionSubjectType.EFFECTOR, AscriptionStatusType.DRAFT);

    when(entityManager.find(AscriptionEntity.class, sourceId)).thenReturn(source);

    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> structureSubtype =
        mock(AscriptionSubtypeService.class);
    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> mechanismSubtype =
        mock(AscriptionSubtypeService.class);
    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> effectorSubtype =
        mock(AscriptionSubtypeService.class);

    when(structureSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    when(structureSubtype.getCascadeTargetRoles()).thenReturn(Map.of());

    when(mechanismSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
    when(mechanismSubtype.getCascadeTargetRoles())
        .thenReturn(
            Map.of(
                DefinitionSubjectType.STRUCTURE, AscriptionStatusTransitionCascadeType.GOVERNING));
    doReturn(List.of(mechanism))
        .when(mechanismSubtype)
        .findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, sourceId);
    when(mechanismSubtype.getRefereeReferences(mechanism)).thenReturn(List.of());

    when(effectorSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.EFFECTOR);
    when(effectorSubtype.getCascadeTargetRoles())
        .thenReturn(
            Map.of(
                DefinitionSubjectType.MECHANISM,
                AscriptionStatusTransitionCascadeType.CONSTITUTIVE));
    doReturn(List.of(effector))
        .when(effectorSubtype)
        .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechanismId);
    when(effectorSubtype.getRefereeReferences(effector)).thenReturn(List.of());

    service = createService(List.of(structureSubtype, mechanismSubtype, effectorSubtype));
    stubRepoSave();

    Tracer tracer = otel.getOpenTelemetry().getTracer("test.ascription.transition", "1");
    Span parent = tracer.spanBuilder("active-flow").startSpan();
    try (Scope ignored = parent.makeCurrent()) {
      service.transition(sourceId, "PROPOSED");
    } finally {
      parent.end();
    }

    SpanData parentSpan =
        otel.getSpans().stream()
            .filter(s -> s.getName().equals("active-flow"))
            .findFirst()
            .orElseThrow();
    SpanData sourceTransition = findTransitionSpanByAscription(sourceId);
    SpanData mechanismTransition = findTransitionSpanByAscription(mechanismId);
    SpanData effectorTransition = findTransitionSpanByAscription(effectorId);

    assertThat(sourceTransition.getParentSpanId()).isEqualTo(parentSpan.getSpanId());
    assertThat(mechanismTransition.getParentSpanId()).isEqualTo(sourceTransition.getSpanId());
    assertThat(effectorTransition.getParentSpanId()).isEqualTo(mechanismTransition.getSpanId());
    assertThat(sourceTransition.getEvents())
        .anyMatch(event -> event.getName().equals(EVENT_HOOK_CASCADE));
    assertThat(mechanismTransition.getEvents())
        .anyMatch(event -> event.getName().equals(EVENT_HOOK_CASCADE));
  }

  @Test
  void cascadeSkip_emitsSkipEventWithoutCreatingCascadeTransitionSpan() {
    UUID sourceId = UUID.randomUUID();
    UUID mechanismId = UUID.randomUUID();

    AscriptionEntity source =
        stubEntity(sourceId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT);
    AscriptionEntity mechanism =
        stubEntity(mechanismId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.PROPOSED);

    when(entityManager.find(AscriptionEntity.class, sourceId)).thenReturn(source);

    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> mechanismSubtype =
        mock(AscriptionSubtypeService.class);

    when(mechanismSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
    when(mechanismSubtype.getCascadeTargetRoles())
        .thenReturn(
            Map.of(
                DefinitionSubjectType.STRUCTURE, AscriptionStatusTransitionCascadeType.GOVERNING));
    doReturn(List.of(mechanism))
        .when(mechanismSubtype)
        .findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, sourceId);

    service = createService(List.of(mechanismSubtype));
    stubRepoSave();

    service.transition(sourceId, "PROPOSED");

    SpanData sourceTransition = findTransitionSpanByAscription(sourceId);
    assertThat(sourceTransition.getEvents())
        .anyMatch(event -> event.getName().equals(EVENT_HOOK_CASCADE_SKIP));
    assertThat(
            otel.getSpans().stream()
                .filter(span -> span.getName().equals(TRANSITION_SPAN_NAME))
                .count())
        .isEqualTo(1L);
  }

  @Test
  void dependentCascadeNotApplicable_emitsReasonAndWarnLogWithBody() {
    UUID sourceId = UUID.randomUUID();
    AscriptionEntity source =
        stubEntity(sourceId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.PROPOSED);
    when(entityManager.find(AscriptionEntity.class, sourceId)).thenReturn(source);

    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> mechanismSubtype =
        mock(AscriptionSubtypeService.class);
    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> effectorSubtype =
        mock(AscriptionSubtypeService.class);

    when(mechanismSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
    when(mechanismSubtype.getCascadeTargetRoles()).thenReturn(Map.of());

    when(effectorSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.EFFECTOR);
    when(effectorSubtype.getCascadeTargetRoles())
        .thenReturn(
            Map.of(
                DefinitionSubjectType.MECHANISM, AscriptionStatusTransitionCascadeType.DEPENDENT));

    service = createService(List.of(mechanismSubtype, effectorSubtype));
    stubRepoSave();

    service.transition(sourceId, "APPROVED");

    SpanData transitionSpan = findTransitionSpanByAscription(sourceId);
    assertThat(transitionSpan.getEvents())
        .anyMatch(
            event ->
                event.getName().equals(EVENT_HOOK_CASCADE_SKIP)
                    && "dependent-not-applicable"
                        .equals(
                            event
                                .getAttributes()
                                .get(AttributeKey.stringKey("gsm.ascription.cascade.reason"))));

    LogRecordData lifecycleLog = findLifecycleLogRecord(EVENT_HOOK_CASCADE_SKIP, OUTCOME_SKIPPED);
    assertThat(lifecycleLog.getSeverity()).isEqualTo(Severity.WARN);
    assertThat(lifecycleLog.getBodyValue()).isNotNull();
    assertThat(lifecycleLog.getBodyValue().asString()).contains(EVENT_HOOK_CASCADE_SKIP);
  }

  @Test
  void cascadeStatusMismatch_emitsReasonAndWarnLogWithBody() {
    UUID sourceId = UUID.randomUUID();
    UUID mechanismId = UUID.randomUUID();

    AscriptionEntity source =
        stubEntity(sourceId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT);
    AscriptionEntity mechanism =
        stubEntity(mechanismId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.PROPOSED);

    when(entityManager.find(AscriptionEntity.class, sourceId)).thenReturn(source);

    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> structureSubtype =
        mock(AscriptionSubtypeService.class);
    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> mechanismSubtype =
        mock(AscriptionSubtypeService.class);

    when(structureSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    when(structureSubtype.getCascadeTargetRoles()).thenReturn(Map.of());

    when(mechanismSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
    when(mechanismSubtype.getCascadeTargetRoles())
        .thenReturn(
            Map.of(
                DefinitionSubjectType.STRUCTURE, AscriptionStatusTransitionCascadeType.GOVERNING));
    doReturn(List.of(mechanism))
        .when(mechanismSubtype)
        .findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, sourceId);

    service = createService(List.of(structureSubtype, mechanismSubtype));
    stubRepoSave();

    service.transition(sourceId, "PROPOSED");

    SpanData sourceTransition = findTransitionSpanByAscription(sourceId);
    assertThat(sourceTransition.getEvents())
        .anyMatch(
            event ->
                event.getName().equals(EVENT_HOOK_CASCADE_SKIP)
                    && "status-mismatch"
                        .equals(
                            event
                                .getAttributes()
                                .get(AttributeKey.stringKey("gsm.ascription.cascade.reason"))));

    LogRecordData lifecycleLog = findLifecycleLogRecord(EVENT_HOOK_CASCADE_SKIP, OUTCOME_SKIPPED);
    assertThat(lifecycleLog.getSeverity()).isEqualTo(Severity.WARN);
    assertThat(lifecycleLog.getBodyValue()).isNotNull();
    assertThat(lifecycleLog.getBodyValue().asString()).contains(EVENT_HOOK_CASCADE_SKIP);
  }

  @Test
  void cascadeRefereePreconditionFailure_emitsReasonAndWarnLogWithBody() {
    UUID structureId = UUID.randomUUID();
    AscriptionEntity structure =
        stubEntity(structureId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE);
    when(entityManager.find(AscriptionEntity.class, structureId)).thenReturn(structure);

    UUID mechanismId = UUID.randomUUID();
    AscriptionEntity mechanism =
        stubEntity(mechanismId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE);

    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> structureSubtype =
        mock(AscriptionSubtypeService.class);
    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> mechanismSubtype =
        mock(AscriptionSubtypeService.class);

    when(structureSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    when(structureSubtype.getCascadeTargetRoles()).thenReturn(Map.of());

    when(mechanismSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
    when(mechanismSubtype.getCascadeTargetRoles())
        .thenReturn(
            Map.of(
                DefinitionSubjectType.STRUCTURE, AscriptionStatusTransitionCascadeType.GOVERNING));
    doReturn(List.of(mechanism))
        .when(mechanismSubtype)
        .findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, structureId);
    AtomicInteger refereeChecks = new AtomicInteger(0);
    doAnswer(
            invocation -> {
              if (refereeChecks.incrementAndGet() > 1) {
                throw RuleViolationException.of(
                    AscriptionStatusTransitionRuleType
                        .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
                    "Referee precondition failed");
              }
              return null;
            })
        .when(stateMachine)
        .validateRefereePreconditions(any(), any(), any());

    service = createService(List.of(structureSubtype, mechanismSubtype));
    stubRepoSave();

    service.transition(structureId, "DEPRECATED");

    SpanData sourceTransition = findTransitionSpanByAscription(structureId);
    assertThat(sourceTransition.getEvents())
        .anyMatch(
            event ->
                event.getName().equals(EVENT_HOOK_CASCADE_SKIP)
                    && "referee-precondition"
                        .equals(
                            event
                                .getAttributes()
                                .get(AttributeKey.stringKey("gsm.ascription.cascade.reason"))));

    LogRecordData lifecycleLog = findLifecycleLogRecord(EVENT_HOOK_CASCADE_SKIP, OUTCOME_SKIPPED);
    assertThat(lifecycleLog.getSeverity()).isEqualTo(Severity.WARN);
    assertThat(lifecycleLog.getBodyValue()).isNotNull();
    assertThat(lifecycleLog.getBodyValue().asString()).contains(EVENT_HOOK_CASCADE_SKIP);
  }

  @Test
  void constitutiveStatusMismatch_emitsFailureEventBeforeThrow() {
    UUID sourceId = UUID.randomUUID();
    UUID mechanismId = UUID.randomUUID();

    AscriptionEntity source =
        stubEntity(sourceId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT);
    AscriptionEntity mechanism =
        stubEntity(mechanismId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.PROPOSED);

    when(entityManager.find(AscriptionEntity.class, sourceId)).thenReturn(source);

    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> structureSubtype =
        mock(AscriptionSubtypeService.class);
    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> mechanismSubtype =
        mock(AscriptionSubtypeService.class);

    when(structureSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    when(structureSubtype.getCascadeTargetRoles()).thenReturn(Map.of());

    when(mechanismSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
    when(mechanismSubtype.getCascadeTargetRoles())
        .thenReturn(
            Map.of(
                DefinitionSubjectType.STRUCTURE,
                AscriptionStatusTransitionCascadeType.CONSTITUTIVE));
    doReturn(List.of(mechanism))
        .when(mechanismSubtype)
        .findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, sourceId);

    service = createService(List.of(structureSubtype, mechanismSubtype));
    stubRepoSave();

    assertThrows(RuleViolationException.class, () -> service.transition(sourceId, "PROPOSED"));

    SpanData sourceTransition = findTransitionSpanByAscription(sourceId);
    assertThat(sourceTransition.getEvents())
        .anyMatch(
            event ->
                event.getName().equals(EVENT_HOOK_ERROR)
                    && "status-mismatch"
                        .equals(
                            event
                                .getAttributes()
                                .get(AttributeKey.stringKey("gsm.ascription.cascade.reason"))));

    LogRecordData lifecycleLog = findLifecycleLogRecord(EVENT_HOOK_ERROR, OUTCOME_FAILURE);
    assertThat(lifecycleLog.getSeverity()).isEqualTo(Severity.ERROR);
  }

  @Test
  void cascadeTargetFailure_correlatesErrorLogWithCascadeSpan() {
    UUID sourceId = UUID.randomUUID();
    UUID mechanismId = UUID.randomUUID();

    AscriptionEntity source =
        stubEntity(sourceId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT);
    AscriptionEntity mechanism =
        stubEntity(mechanismId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.DRAFT);

    when(entityManager.find(AscriptionEntity.class, sourceId)).thenReturn(source);

    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> structureSubtype =
        mock(AscriptionSubtypeService.class);
    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> mechanismSubtype =
        mock(AscriptionSubtypeService.class);

    when(structureSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    when(structureSubtype.getCascadeTargetRoles()).thenReturn(Map.of());

    when(mechanismSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
    when(mechanismSubtype.getCascadeTargetRoles())
        .thenReturn(
            Map.of(
                DefinitionSubjectType.STRUCTURE, AscriptionStatusTransitionCascadeType.GOVERNING));
    doReturn(List.of(mechanism))
        .when(mechanismSubtype)
        .findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, sourceId);
    when(mechanismSubtype.getRefereeReferences(mechanism)).thenReturn(List.of());

    service = createService(List.of(structureSubtype, mechanismSubtype));
    AtomicInteger saves = new AtomicInteger(0);
    when(transitionRepo.save(any(AscriptionStatusTransitionEntity.class)))
        .thenAnswer(
            invocation -> {
              if (saves.incrementAndGet() > 1) {
                throw new IllegalStateException("cascade persistence failed");
              }
              AscriptionStatusTransitionEntity saved = mock(AscriptionStatusTransitionEntity.class);
              UUID transitionId = UUID.randomUUID();
              when(saved.getId()).thenReturn(transitionId);
              when(transitionRepo.findById(transitionId)).thenReturn(Optional.of(saved));
              return saved;
            });

    assertThrows(IllegalStateException.class, () -> service.transition(sourceId, "PROPOSED"));

    SpanData cascadeSpan = findTransitionSpanByAscription(mechanismId);
    assertThat(cascadeSpan.getEvents())
        .anyMatch(event -> event.getName().equals(EVENT_HOOK_ERROR));
    assertThat(otel.getLogRecords())
        .anyMatch(
            record ->
                EVENT_HOOK_ERROR.equals(
                        record.getAttributes().get(AttributeKey.stringKey("event.name")))
                    && OUTCOME_FAILURE.equals(
                        record.getAttributes().get(AttributeKey.stringKey("event.outcome")))
                    && cascadeSpan
                        .getTraceId()
                        .equals(record.getAttributes().get(AttributeKey.stringKey("trace_id")))
                    && cascadeSpan
                        .getSpanId()
                        .equals(record.getAttributes().get(AttributeKey.stringKey("span_id"))));
  }

  @Test
  void rejectedTransition_recordsExceptionOnTransitionSpan() {
    UUID ascriptionId = UUID.randomUUID();
    AscriptionEntity entity =
        stubEntity(ascriptionId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT);
    when(entityManager.find(AscriptionEntity.class, ascriptionId)).thenReturn(entity);
    doThrow(
            RuleViolationException.of(
                AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_PATH,
                "Invalid transition"))
        .when(stateMachine)
        .validateTransition(
            ascriptionId, AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED);

    assertThrows(RuleViolationException.class, () -> service.transition(ascriptionId, "PROPOSED"));

    SpanData transitionSpan =
        otel.getSpans().stream()
            .filter(s -> s.getName().equals("gsm.ascription.transition"))
            .findFirst()
            .orElseThrow();

    assertThat(transitionSpan.getEvents()).anyMatch(event -> event.getName().equals("exception"));
    assertThat(transitionSpan.getEvents())
        .anyMatch(event -> event.getName().equals(EVENT_HOOK_ERROR));

    LogRecordData lifecycleLog = findLifecycleLogRecord(EVENT_HOOK_ERROR, OUTCOME_FAILURE);
    assertThat(lifecycleLog.getSeverity()).isEqualTo(Severity.ERROR);
    assertThat(lifecycleLog.getBodyValue()).isNotNull();
    assertThat(lifecycleLog.getBodyValue().asString()).contains(EVENT_HOOK_ERROR);
    assertThat(lifecycleLog.getAttributes().get(AttributeKey.stringKey("trace_id")))
        .isEqualTo(transitionSpan.getTraceId());
    assertThat(lifecycleLog.getAttributes().get(AttributeKey.stringKey("span_id")))
        .isEqualTo(transitionSpan.getSpanId());
  }

  private AscriptionEntity stubEntity(
      UUID id, DefinitionSubjectType subjectType, AscriptionStatusType status) {
    DefinitionEntity definition = mock(DefinitionEntity.class);
    lenient().when(definition.getSubjectType()).thenReturn(subjectType);
    lenient().when(definition.getId()).thenReturn(UUID.randomUUID());

    AscriptionEntity entity = mock(AscriptionEntity.class);
    lenient().when(entity.getId()).thenReturn(id);
    lenient().when(entity.getDefinition()).thenReturn(definition);
    lenient().when(entity.getStatus()).thenReturn(status);
    return entity;
  }

  private AscriptionStatusTransitionService createService(
      List<AscriptionSubtypeService<?>> subtypes) {
    Tracer tracer = otel.getOpenTelemetry().getTracer("test.ascription.transition", "1");
    AscriptionStatusTransitionService configuredService =
        new AscriptionStatusTransitionService(
            transitionRepo, stateMachine, entityManager, subtypes, tracer);
    configuredService.afterSingletonsInstantiated();
    return configuredService;
  }

  private SpanData findTransitionSpanByAscription(UUID ascriptionId) {
    return otel.getSpans().stream()
        .filter(span -> span.getName().equals(TRANSITION_SPAN_NAME))
        .filter(
            span ->
                ascriptionId
                    .toString()
                    .equals(span.getAttributes().get(AttributeKey.stringKey("gsm.ascription.id"))))
        .findFirst()
        .orElseThrow();
  }

  private LogRecordData findLifecycleLogRecord(String eventName, String eventOutcome) {
    return otel.getLogRecords().stream()
        .filter(
            record ->
                eventName.equals(record.getAttributes().get(AttributeKey.stringKey("event.name")))
                    && eventOutcome.equals(
                        record.getAttributes().get(AttributeKey.stringKey("event.outcome"))))
        .findFirst()
        .orElseThrow();
  }

  private boolean hasNoBody(LogRecordData record) {
    if (record.getBodyValue() == null) {
      return true;
    }
    String body = record.getBodyValue().asString();
    return body == null || body.isBlank();
  }

  private void stubRepoSave() {
    when(transitionRepo.save(any(AscriptionStatusTransitionEntity.class)))
        .thenAnswer(
            invocation -> {
              AscriptionStatusTransitionEntity saved = mock(AscriptionStatusTransitionEntity.class);
              UUID transitionId = UUID.randomUUID();
              when(saved.getId()).thenReturn(transitionId);
              when(transitionRepo.findById(transitionId)).thenReturn(Optional.of(saved));
              return saved;
            });
  }
}
