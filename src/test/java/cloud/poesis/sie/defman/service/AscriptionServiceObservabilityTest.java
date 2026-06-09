package cloud.poesis.sie.defman.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class AscriptionServiceObservabilityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String CREATE_SPAN_NAME = "gsm.definition.create";
  private static final String TRANSFORM_SPAN_NAME = "gsm.definition.transform";

  @RegisterExtension static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

  @Mock private AscriptionRepository ascriptionRepository;
  @Mock private ArchetypeService archetypeService;
  @Mock private DefinitionService definitionService;
  @Mock private AscriptionStateMachineService stateMachine;
  @Mock private AscriptionParsingValidationService statementValidation;
  @Mock private AscriptionIdentityBoundValidationService identityBoundValidation;
  @Mock private AscriptionUniquenessValidationService uniquenessValidation;
  @Mock private AscriptionProtectionService statementProtection;
  @Mock private EntityManager entityManager;

  @SuppressWarnings("unchecked")
  private final AscriptionSubtypeService<AscriptionEntity> structureHandler =
      mock(AscriptionSubtypeService.class);

  @BeforeEach
  void setUpHandler() {
    when(structureHandler.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    when(structureHandler.getCascadeTargetRoles()).thenReturn(Map.of());
    when(structureHandler.getRefereeReferences(any())).thenReturn(List.of());
  }

  private void withOperationSpan(String spanName, Runnable action) {
    Tracer tracer = otel.getOpenTelemetry().getTracer("test.definition.create", "1");
    Span operationSpan = tracer.spanBuilder(spanName).startSpan();
    try (Scope ignored = operationSpan.makeCurrent()) {
      action.run();
    } finally {
      operationSpan.end();
    }
  }

  @Test
  void createPath_emitsCreateSpanWithRequiredAttributesAndDomainEvents() {
    UUID archetypeId = UUID.randomUUID();
    UUID definitionId = UUID.randomUUID();

    ArchetypeEntity archetype = mock(ArchetypeEntity.class);
    when(archetypeService.resolveForCreation(archetypeId))
        .thenReturn(
            new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));

    DefinitionEntity definition = mock(DefinitionEntity.class);
    when(definition.getId()).thenReturn(definitionId);
    when(definitionService.resolve(isNull(), eq(DefinitionSubjectType.STRUCTURE)))
        .thenReturn(definition);

    ObjectNode statement = MAPPER.createObjectNode().put("purpose", "governance");

    AscriptionEntity saved = mock(AscriptionEntity.class);
    when(saved.getStatement()).thenReturn(statement);
    when(saved.getArchetype()).thenReturn(archetype);
    when(structureHandler.create(definition, archetype, statement)).thenReturn(saved);
    when(structureHandler.save(saved)).thenReturn(saved);

    AscriptionService service = createService(512);

    MDC.put("gsm.tenant.id", "tenant-alpha");
    try {
      withOperationSpan(CREATE_SPAN_NAME, () -> service.create(archetypeId, statement, null));
    } finally {
      MDC.remove("gsm.tenant.id");
    }

    SpanData createSpan =
        otel.getSpans().stream()
            .filter(span -> span.getName().equals(CREATE_SPAN_NAME))
            .findFirst()
            .orElseThrow();

    assertThat(createSpan.getAttributes().get(AttributeKey.stringKey("gsm.definition.operation")))
        .isEqualTo("create");
    assertThat(createSpan.getAttributes().get(AttributeKey.stringKey("gsm.definition.id")))
        .isEqualTo(definitionId.toString());
    assertThat(createSpan.getAttributes().get(AttributeKey.stringKey("gsm.definition.kind")))
        .isEqualTo("STRUCTURE");
    assertThat(createSpan.getAttributes().get(AttributeKey.stringKey("gsm.tenant.id")))
        .isEqualTo("tenant-alpha");
    assertThat(createSpan.getAttributes().get(AttributeKey.stringKey("sie.component")))
        .isEqualTo("definition-manager");

    assertThat(createSpan.getEvents())
        .anyMatch(event -> event.getName().equals("gsm.definition.create.validate-statement"));
    assertThat(createSpan.getEvents())
        .anyMatch(event -> event.getName().equals("gsm.definition.create.resolve-definition"));
    assertThat(createSpan.getEvents())
        .anyMatch(event -> event.getName().equals("gsm.definition.create.persist"));
    assertThat(createSpan.getEvents())
        .anyMatch(event -> event.getName().equals("gsm.definition.create.after-create"));
    assertThat(
            otel.getSpans().stream()
                .filter(span -> span.getName().equals(CREATE_SPAN_NAME))
                .count())
        .isEqualTo(1L);
  }

  @Test
  void createPath_logsBoundedResultPayloadViaS007HelperAndAddsTruncationEvent() {
    UUID archetypeId = UUID.randomUUID();
    UUID definitionId = UUID.randomUUID();

    ArchetypeEntity archetype = mock(ArchetypeEntity.class);
    when(archetypeService.resolveForCreation(archetypeId))
        .thenReturn(
            new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));

    DefinitionEntity definition = mock(DefinitionEntity.class);
    when(definition.getId()).thenReturn(definitionId);
    when(definitionService.resolve(isNull(), eq(DefinitionSubjectType.STRUCTURE)))
        .thenReturn(definition);

    ObjectNode statement = MAPPER.createObjectNode().put("purpose", "governance");
    String oversized = "x".repeat(200);

    AscriptionEntity saved = mock(AscriptionEntity.class);
    when(saved.getStatement()).thenReturn(MAPPER.createObjectNode().put("payload", oversized));
    when(saved.getArchetype()).thenReturn(archetype);
    when(structureHandler.create(definition, archetype, statement)).thenReturn(saved);
    when(structureHandler.save(saved)).thenReturn(saved);

    AscriptionService service = createService(80);

    withOperationSpan(CREATE_SPAN_NAME, () -> service.create(archetypeId, statement, null));

    SpanData createSpan =
        otel.getSpans().stream()
            .filter(span -> span.getName().equals(CREATE_SPAN_NAME))
            .findFirst()
            .orElseThrow();
    assertThat(createSpan.getEvents())
        .anyMatch(event -> event.getName().equals("sie.payload.truncated.summary"));
  }

    @Test
    void transformPath_emitsTransformSpanWithRequiredAttributesAndDomainEvents() {
    UUID archetypeId = UUID.randomUUID();
    UUID definitionId = UUID.randomUUID();

    ArchetypeEntity archetype = mock(ArchetypeEntity.class);
    when(archetypeService.resolveForCreation(archetypeId))
      .thenReturn(
        new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));

    DefinitionEntity definition = mock(DefinitionEntity.class);
    when(definition.getId()).thenReturn(definitionId);
    when(definitionService.resolve(eq(definitionId), eq(DefinitionSubjectType.STRUCTURE)))
      .thenReturn(definition);

    ObjectNode statement = MAPPER.createObjectNode().put("purpose", "governance-transform");

    AscriptionEntity saved = mock(AscriptionEntity.class);
    when(saved.getStatement()).thenReturn(statement);
    when(saved.getArchetype()).thenReturn(archetype);
    when(structureHandler.create(definition, archetype, statement)).thenReturn(saved);
    when(structureHandler.save(saved)).thenReturn(saved);

    AscriptionService service = createService(512);

    MDC.put("gsm.tenant.id", "tenant-beta");
    try {
      withOperationSpan(
          TRANSFORM_SPAN_NAME, () -> service.create(archetypeId, statement, definitionId));
    } finally {
      MDC.remove("gsm.tenant.id");
    }

    SpanData transformSpan =
      otel.getSpans().stream()
        .filter(span -> span.getName().equals(TRANSFORM_SPAN_NAME))
        .findFirst()
        .orElseThrow();

    assertThat(transformSpan.getAttributes().get(AttributeKey.stringKey("gsm.definition.operation")))
      .isEqualTo("transform");
    assertThat(transformSpan.getAttributes().get(AttributeKey.stringKey("gsm.definition.id")))
      .isEqualTo(definitionId.toString());
    assertThat(transformSpan.getAttributes().get(AttributeKey.stringKey("gsm.definition.kind")))
      .isEqualTo("STRUCTURE");
    assertThat(transformSpan.getAttributes().get(AttributeKey.stringKey("gsm.tenant.id")))
      .isEqualTo("tenant-beta");
    assertThat(transformSpan.getAttributes().get(AttributeKey.stringKey("sie.component")))
      .isEqualTo("definition-manager");

    assertThat(transformSpan.getEvents())
      .anyMatch(event -> event.getName().equals("gsm.definition.transform.validate-statement"));
    assertThat(transformSpan.getEvents())
      .anyMatch(event -> event.getName().equals("gsm.definition.transform.resolve-definition"));
    assertThat(transformSpan.getEvents())
      .anyMatch(event -> event.getName().equals("gsm.definition.transform.persist"));
    assertThat(transformSpan.getEvents())
      .anyMatch(event -> event.getName().equals("gsm.definition.transform.after-create"));
    assertThat(
        otel.getSpans().stream()
          .filter(span -> span.getName().equals(TRANSFORM_SPAN_NAME))
          .count())
      .isEqualTo(1L);
    }

    @Test
    void transformPath_logsBoundedPriorAndResultPayloadViaS007HelperAndAddsTruncationEvent() {
    UUID archetypeId = UUID.randomUUID();
    UUID definitionId = UUID.randomUUID();

    ArchetypeEntity archetype = mock(ArchetypeEntity.class);
    when(archetypeService.resolveForCreation(archetypeId))
      .thenReturn(
        new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));

    DefinitionEntity definition = mock(DefinitionEntity.class);
    when(definition.getId()).thenReturn(definitionId);
    when(definitionService.resolve(eq(definitionId), eq(DefinitionSubjectType.STRUCTURE)))
      .thenReturn(definition);

    String oversizedPrior = "p".repeat(220);
    String oversizedResult = "r".repeat(220);
    ObjectNode statement = MAPPER.createObjectNode().put("purpose", oversizedPrior);

    AscriptionEntity saved = mock(AscriptionEntity.class);
    when(saved.getStatement()).thenReturn(MAPPER.createObjectNode().put("purpose", oversizedResult));
    when(saved.getArchetype()).thenReturn(archetype);
    when(structureHandler.create(definition, archetype, statement)).thenReturn(saved);
    when(structureHandler.save(saved)).thenReturn(saved);

    AscriptionService service = createService(90);

    withOperationSpan(
      TRANSFORM_SPAN_NAME, () -> service.create(archetypeId, statement, definitionId));

    SpanData transformSpan =
      otel.getSpans().stream()
        .filter(span -> span.getName().equals(TRANSFORM_SPAN_NAME))
        .findFirst()
        .orElseThrow();
    assertThat(transformSpan.getEvents())
      .anyMatch(event -> event.getName().equals("sie.payload.truncated.summary"));
    }

  private AscriptionService createService(int payloadCapBytes) {
    List<AscriptionSubtypeService<?>> handlers = new ArrayList<>();
    handlers.add(structureHandler);

    for (DefinitionSubjectType type : DefinitionSubjectType.values()) {
      if (type == DefinitionSubjectType.STRUCTURE) {
        continue;
      }
      AscriptionSubtypeService<?> handler = mock(AscriptionSubtypeService.class);
      when(handler.getSubjectType()).thenReturn(type);
      handlers.add(handler);
    }

    AscriptionService service =
        new AscriptionService(
            ascriptionRepository,
            archetypeService,
            definitionService,
            stateMachine,
            statementValidation,
            identityBoundValidation,
            uniquenessValidation,
            statementProtection,
            entityManager,
            handlers,
            payloadCapBytes);
    service.afterSingletonsInstantiated();
    return service;
  }
}
