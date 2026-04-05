package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatementValidationServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private ArchetypeSchemaService archetypeSchemaService;

  @Mock private AscriptionArchetypeSchemaAnnotationEnforcementService annotationEnforcement;

  private AscriptionStatementValidationService svc;

  @BeforeEach
  void setUp() {
    svc = new AscriptionStatementValidationService(archetypeSchemaService, annotationEnforcement);
  }

  // ========================================================================
  // enforceAnnotations — delegation
  // ========================================================================

  @Nested
  class EnforceAnnotationsDelegation {

    @Test
    void delegatesToAnnotationEnforcement() {
      ArchetypeEntity archetype = stubArchetypeWithSchema(MAPPER.createObjectNode());
      ObjectNode statement = MAPPER.createObjectNode().put("x", "val");
      UUID defId = UUID.randomUUID();

      assertDoesNotThrow(
          () -> svc.enforceAnnotations(statement, archetype, defId, id -> List.of()));

      verify(annotationEnforcement).enforceAnnotations(any(), any(), any(), any());
    }
  }

  // ========================================================================
  // Statement validation (Ascription-V1)
  // ========================================================================

  @Nested
  class ValidateStatement {

    @Test
    void validStatement_passes() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      schema.put("type", "object");
      ObjectNode props = schema.putObject("properties");
      props.set("name", MAPPER.createObjectNode().put("type", "string"));

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("name", "hello");

      assertDoesNotThrow(
          () -> svc.validateStatement(statement, archetype, DefinitionSubjectType.STRUCTURE));
    }

    @Test
    void invalidStatement_rejected() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      schema.put("type", "object");
      ObjectNode props = schema.putObject("properties");
      props.set("count", MAPPER.createObjectNode().put("type", "integer"));
      schema.putArray("required").add("count");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode();

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> svc.validateStatement(statement, archetype, DefinitionSubjectType.STRUCTURE));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Statement validation failed"));
    }

    @Test
    void wrongType_rejected() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      schema.put("type", "object");
      ObjectNode props = schema.putObject("properties");
      props.set("count", MAPPER.createObjectNode().put("type", "integer"));

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("count", "not-a-number");

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> svc.validateStatement(statement, archetype, DefinitionSubjectType.STRUCTURE));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Statement validation failed"));
    }

    @Test
    void tenantArchetypeRef_resolvedFromDb() {
      ObjectNode tenantSchema = MAPPER.createObjectNode();
      tenantSchema.put("title", "CustomTenantArchetype");
      tenantSchema.put("type", "object");
      tenantSchema.putObject("properties").putObject("label").put("type", "string");

      ArchetypeEntity tenantArchetype = mock(ArchetypeEntity.class);
      when(tenantArchetype.getStatement()).thenReturn(tenantSchema);
      when(tenantArchetype.getStatus()).thenReturn(AscriptionStatusType.ACTIVE);

      when(archetypeSchemaService.findInEffectByTitle("CustomTenantArchetype"))
          .thenReturn(Optional.of(tenantArchetype));

      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "CompositeTenant");
      schema.put("type", "object");
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", "gsm://archetypes/CustomTenantArchetype/v1");
      var local = allOf.addObject();
      local.put("type", "object");
      local.putObject("properties").putObject("extra").put("type", "integer");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("label", "hello").put("extra", 42);

      assertDoesNotThrow(
          () -> svc.validateStatement(statement, archetype, DefinitionSubjectType.STRUCTURE));
    }

    @Test
    void classpathOnly_usesStaticFactory() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "PureSelf");
      schema.put("type", "object");
      schema.putObject("properties").putObject("val").put("type", "string");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("val", "ok");

      assertDoesNotThrow(
          () -> svc.validateStatement(statement, archetype, DefinitionSubjectType.STRUCTURE));
      verify(archetypeSchemaService, never()).findInEffectByTitle(any());
    }
  }

  // ========================================================================
  // ValidateStatement — GSM base property classification
  // ========================================================================

  @Nested
  class ValidateStatementGsmBaseErrors {

    @Test
    void basePropertyViolation_throwsGsmRule() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "StructureSchema");
      schema.put("type", "object");
      schema.putObject("properties").putObject("purpose").put("type", "string");
      schema.putArray("required").add("purpose");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode();

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> svc.validateStatement(statement, archetype, DefinitionSubjectType.STRUCTURE));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ex.getRuleType());
    }

    @Test
    void extensionPropertyViolation_throwsExtensionRule() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "StructureSchema");
      schema.put("type", "object");
      ObjectNode props = schema.putObject("properties");
      props.putObject("purpose").put("type", "string");
      props.putObject("customField").put("type", "integer");
      schema.putArray("required").add("customField");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("purpose", "demo");

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> svc.validateStatement(statement, archetype, DefinitionSubjectType.STRUCTURE));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("tenant-extended"));
    }

    @Test
    void archetypeType_noExtensionRule_usesGsmRule() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "ArchetypeSchema");
      schema.put("type", "object");
      schema.putObject("properties").putObject("needed").put("type", "integer");
      schema.putArray("required").add("needed");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode();

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> svc.validateStatement(statement, archetype, DefinitionSubjectType.ARCHETYPE));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ex.getRuleType());
    }
  }

  // ========================================================================
  // buildSchemaFactory — package-private visibility
  // ========================================================================

  @Nested
  class BuildSchemaFactory {

    @Test
    void classpathOnly_returnsStaticFactory() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("type", "object");
      schema.putObject("properties").putObject("x").put("type", "string");

      // No tenant $ref → should return without hitting DB
      var factory = svc.buildSchemaFactory(schema);
      assertTrue(factory != null);
      verify(archetypeSchemaService, never()).findInEffectByTitle(any());
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private ObjectNode prop(String type) {
    return MAPPER.createObjectNode().put("type", type);
  }

  private ObjectNode schemaWithProperty(String propName, ObjectNode propNode) {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("title", "TestSchema");
    ObjectNode props = schema.putObject("properties");
    props.set(propName, propNode);
    return schema;
  }

  private ArchetypeEntity stubArchetypeWithSchema(ObjectNode schema) {
    ArchetypeEntity archetype = mock(ArchetypeEntity.class);
    when(archetype.getStatement()).thenReturn(schema);

    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(UUID.randomUUID());
    when(archetype.getDefinition()).thenReturn(def);

    return archetype;
  }
}
