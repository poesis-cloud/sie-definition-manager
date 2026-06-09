package cloud.poesis.sie.defman.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.InternalException;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
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
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class AscriptionServiceObservabilityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String CREATE_SPAN_NAME = "gsm.definition.create";
  private static final String TRANSFORM_SPAN_NAME = "gsm.definition.transform";
  private static final String CREATE_PERSIST_FAILURE_EVENT =
      "gsm.definition.create.persist-failure";
  private static final String CREATE_ERROR_EVENT = "gsm.definition.create.error";
  private static final String CREATE_PAYLOAD_SKIPPED_EVENT =
      "gsm.definition.create.payload-skipped";
  private static final String TRANSFORM_ERROR_EVENT = "gsm.definition.transform.error";
  private static final String TRANSFORM_PAYLOAD_SKIPPED_EVENT =
      "gsm.definition.transform.payload-skipped";

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
      service.create(archetypeId, statement, null);
    } finally {
      MDC.remove("gsm.tenant.id");
    }

    SpanData createSpan = findSpan(CREATE_SPAN_NAME);

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

    service.create(archetypeId, statement, null);

    SpanData createSpan = findSpan(CREATE_SPAN_NAME);
    assertThat(createSpan.getEvents())
        .anyMatch(event -> event.getName().equals("sie.payload.truncated.summary"));
  }

  @Test
  void createPath_whenResultPayloadMissing_emitsPayloadSkippedEvent() {
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
    when(saved.getStatement()).thenReturn(null);
    when(saved.getArchetype()).thenReturn(archetype);
    when(structureHandler.create(definition, archetype, statement)).thenReturn(saved);
    when(structureHandler.save(saved)).thenReturn(saved);

    AscriptionService service = createService(512);
    service.create(archetypeId, statement, null);

    SpanData createSpan = findSpan(CREATE_SPAN_NAME);
    assertThat(createSpan.getEvents())
        .anyMatch(event -> event.getName().equals(CREATE_PAYLOAD_SKIPPED_EVENT));
  }

  @Test
  void createPath_whenPersistFails_emitsPersistFailureAndErrorMilestones() {
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

    AscriptionEntity entity = mock(AscriptionEntity.class);
    when(structureHandler.create(definition, archetype, statement)).thenReturn(entity);
    when(structureHandler.save(entity)).thenThrow(new DataIntegrityViolationException("dup"));

    AscriptionService service = createService(512);
    assertThatThrownBy(() -> service.create(archetypeId, statement, null))
        .isInstanceOf(InternalException.class);

    SpanData createSpan = findSpan(CREATE_SPAN_NAME);
    assertThat(createSpan.getEvents())
        .anyMatch(event -> event.getName().equals(CREATE_PERSIST_FAILURE_EVENT));
    assertThat(createSpan.getEvents())
        .anyMatch(event -> event.getName().equals(CREATE_ERROR_EVENT));
    assertThat(createSpan.getEvents()).anyMatch(event -> event.getName().equals("exception"));
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
    when(saved.getArchetype()).thenReturn(archetype);
    when(structureHandler.create(definition, archetype, statement)).thenReturn(saved);
    when(structureHandler.save(saved)).thenReturn(saved);

    AscriptionService service = createService(512);

    MDC.put("gsm.tenant.id", "tenant-beta");
    try {
      service.create(archetypeId, statement, definitionId);
    } finally {
      MDC.remove("gsm.tenant.id");
    }

    SpanData transformSpan = findSpan(TRANSFORM_SPAN_NAME);

    assertThat(
            transformSpan.getAttributes().get(AttributeKey.stringKey("gsm.definition.operation")))
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
    service.create(archetypeId, statement, definitionId);

    SpanData transformSpan = findSpan(TRANSFORM_SPAN_NAME);
    assertThat(transformSpan.getEvents())
        .anyMatch(event -> event.getName().equals("sie.payload.truncated.summary"));
  }

  @Test
  void transformPath_whenPriorAndResultMissing_emitsPayloadSkippedMilestone() {
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

    AscriptionEntity saved = mock(AscriptionEntity.class);
    when(saved.getStatement()).thenReturn(null);
    when(saved.getArchetype()).thenReturn(archetype);
    when(structureHandler.create(eq(definition), eq(archetype), isNull())).thenReturn(saved);
    when(structureHandler.save(saved)).thenReturn(saved);

    AscriptionService service = createService(512);
    service.create(archetypeId, null, definitionId);

    SpanData transformSpan = findSpan(TRANSFORM_SPAN_NAME);
    assertThat(transformSpan.getEvents())
        .anyMatch(event -> event.getName().equals(TRANSFORM_PAYLOAD_SKIPPED_EVENT));
  }

  @Test
  void transformPath_whenAfterCreateFails_emitsErrorMilestone() {
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
    when(saved.getArchetype()).thenReturn(archetype);
    when(structureHandler.create(definition, archetype, statement)).thenReturn(saved);
    when(structureHandler.save(saved)).thenReturn(saved);
    doThrow(new IllegalStateException("after-create failure"))
        .when(structureHandler)
        .afterCreate(saved);

    AscriptionService service = createService(512);
    assertThatThrownBy(() -> service.create(archetypeId, statement, definitionId))
        .isInstanceOf(IllegalStateException.class);

    SpanData transformSpan = findSpan(TRANSFORM_SPAN_NAME);
    assertThat(transformSpan.getEvents())
        .anyMatch(event -> event.getName().equals(TRANSFORM_ERROR_EVENT));
    assertThat(transformSpan.getEvents()).anyMatch(event -> event.getName().equals("exception"));
  }

  private SpanData findSpan(String spanName) {
    return otel.getSpans().stream()
        .filter(span -> span.getName().equals(spanName))
        .findFirst()
        .orElseThrow();
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

    Tracer tracer = otel.getOpenTelemetry().getTracer("test.definition.create", "1");
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
            tracer,
            payloadCapBytes);
    service.afterSingletonsInstantiated();
    return service;
  }
}
