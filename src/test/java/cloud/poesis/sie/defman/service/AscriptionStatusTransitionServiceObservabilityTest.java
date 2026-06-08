package cloud.poesis.sie.defman.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
  private static final String EVENT_HOOK_APPROVAL = "gsm.ascription.hook.approval";
  private static final String EVENT_HOOK_CASCADE = "gsm.ascription.hook.cascade";
  private static final String EVENT_HOOK_CASCADE_SKIP = "gsm.ascription.hook.cascade-skip";
  private static final String EVENT_HOOK_ERROR = "gsm.ascription.hook.error";

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
        transitionSpan
          .getAttributes()
          .get(AttributeKey.stringKey("gsm.ascription.state.from")))
        .isEqualTo("DRAFT");
    assertThat(
        transitionSpan
          .getAttributes()
          .get(AttributeKey.stringKey("gsm.ascription.state.to")))
        .isEqualTo("PROPOSED");
    assertThat(transitionSpan.getAttributes().get(AttributeKey.stringKey("gsm.tenant.id")))
        .isEqualTo("tenant-alpha");
    assertThat(transitionSpan.getAttributes().get(AttributeKey.stringKey("sie.component")))
        .isEqualTo("definition-manager");
  }

    @Test
    void activationTransition_emitsActivationHookEventOnParentTransitionSpan() {
    UUID ascriptionId = UUID.randomUUID();
    AscriptionEntity entity =
      stubEntity(ascriptionId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.APPROVED);
    when(entityManager.find(AscriptionEntity.class, ascriptionId)).thenReturn(entity);

    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> structureSubtype = mock(AscriptionSubtypeService.class);
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
    void approvedTransition_emitsApprovalHookEventOnParentTransitionSpan() {
    UUID ascriptionId = UUID.randomUUID();
    AscriptionEntity entity =
      stubEntity(ascriptionId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.PROPOSED);
    when(entityManager.find(AscriptionEntity.class, ascriptionId)).thenReturn(entity);

    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> structureSubtype = mock(AscriptionSubtypeService.class);
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
    AscriptionSubtypeService<AscriptionEntity> structureSubtype = mock(AscriptionSubtypeService.class);
    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> mechanismSubtype = mock(AscriptionSubtypeService.class);
    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> effectorSubtype = mock(AscriptionSubtypeService.class);

    when(structureSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    when(structureSubtype.getCascadeTargetRoles()).thenReturn(Map.of());

    when(mechanismSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
    when(mechanismSubtype.getCascadeTargetRoles())
      .thenReturn(
        Map.of(DefinitionSubjectType.STRUCTURE, AscriptionStatusTransitionCascadeType.GOVERNING));
    doReturn(List.of(mechanism))
      .when(mechanismSubtype)
      .findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, sourceId);
    when(mechanismSubtype.getRefereeReferences(mechanism)).thenReturn(List.of());

    when(effectorSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.EFFECTOR);
    when(effectorSubtype.getCascadeTargetRoles())
      .thenReturn(
        Map.of(DefinitionSubjectType.MECHANISM, AscriptionStatusTransitionCascadeType.CONSTITUTIVE));
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
    AscriptionSubtypeService<AscriptionEntity> structureSubtype = mock(AscriptionSubtypeService.class);
    @SuppressWarnings("unchecked")
    AscriptionSubtypeService<AscriptionEntity> mechanismSubtype = mock(AscriptionSubtypeService.class);

    when(structureSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    when(structureSubtype.getCascadeTargetRoles()).thenReturn(Map.of());

    when(mechanismSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
    when(mechanismSubtype.getCascadeTargetRoles())
      .thenReturn(
        Map.of(DefinitionSubjectType.STRUCTURE, AscriptionStatusTransitionCascadeType.GOVERNING));
    doReturn(List.of(mechanism))
      .when(mechanismSubtype)
      .findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, sourceId);

    service = createService(List.of(structureSubtype, mechanismSubtype));
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
      .validateTransition(ascriptionId, AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED);

    assertThrows(RuleViolationException.class, () -> service.transition(ascriptionId, "PROPOSED"));

    SpanData transitionSpan =
        otel.getSpans().stream()
            .filter(s -> s.getName().equals("gsm.ascription.transition"))
            .findFirst()
            .orElseThrow();

    assertThat(transitionSpan.getEvents()).anyMatch(event -> event.getName().equals("exception"));
    assertThat(transitionSpan.getEvents()).anyMatch(event -> event.getName().equals(EVENT_HOOK_ERROR));
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
                    .equals(
                        span
                            .getAttributes()
                            .get(AttributeKey.stringKey("gsm.ascription.id"))))
        .findFirst()
        .orElseThrow();
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