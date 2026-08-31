package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
class AscriptionParsingValidationServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void gsmBaseSchemaMapping_requiresExactSeedIdentity() {
    for (String title : DefinitionSubjectType.archetypeTitles()) {
      String id = "gsmarc://gsm/" + title + "/v1";
      assertEquals(
          "classpath:gsm/schemas/" + title + ".schema.json",
          AscriptionParsingValidationService.gsmBaseSchemaLocation(id));
      assertTrue(ArchetypeParsingService.isGsmBaseId(id));
    }
    assertNull(
        AscriptionParsingValidationService.gsmBaseSchemaLocation("gsmarc://gsm/Structure/v99"));
    assertNull(
        AscriptionParsingValidationService.gsmBaseSchemaLocation("gsmarc://gsm/x/Structure/v1"));
    assertNull(
        AscriptionParsingValidationService.gsmBaseSchemaLocation("gsmarc://tenant/Structure/v1"));
    assertTrue(!ArchetypeParsingService.isGsmBaseId("gsmarc://gsm/Structure/v99"));
    assertTrue(!ArchetypeParsingService.isGsmBaseId("gsmarc://gsm/x/Structure/v1"));
    assertTrue(!ArchetypeParsingService.isGsmBaseId("gsmarc://tenant/Structure/v1"));
  }

  @Mock
  private ArchetypeParsingService archetypeSchemaService;

  private AscriptionParsingValidationService svc;

  @BeforeEach
  void setUp() {
    svc = new AscriptionParsingValidationService(
        archetypeSchemaService, new JsonSchemaPositionWalker());
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

      RuleViolationException ex = assertThrows(
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

      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> svc.validateStatement(statement, archetype, DefinitionSubjectType.STRUCTURE));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Statement validation failed"));
    }

    @Test
    void tenantArchetypeRef_resolvedFromDb() {
      String tenantId = "gsmarc://gsm/CustomTenantArchetype/v1";
      ObjectNode tenantSchema = MAPPER.createObjectNode();
      tenantSchema.put("$id", tenantId);
      tenantSchema.put("title", "CustomTenantArchetype");
      tenantSchema.put("type", "object");
      tenantSchema.putObject("properties").putObject("label").put("type", "string");

      ArchetypeEntity tenantArchetype = mock(ArchetypeEntity.class);
      when(tenantArchetype.getStatement()).thenReturn(tenantSchema);
      when(tenantArchetype.getStatus()).thenReturn(AscriptionStatusType.ACTIVE);

      when(archetypeSchemaService.findResolvableByUri(tenantId))
          .thenReturn(Optional.of(tenantArchetype));

      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "CompositeTenant");
      schema.put("type", "object");
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", tenantId);
      var local = allOf.addObject();
      local.put("type", "object");
      local.putObject("properties").putObject("extra").put("type", "integer");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("label", "hello").put("extra", 42);

      assertDoesNotThrow(
          () -> svc.validateStatement(statement, archetype, DefinitionSubjectType.STRUCTURE));
      verify(archetypeSchemaService).findResolvableByUri(tenantId);
    }

    @Test
    void draftTenantArchetypeRef_isNotResolvable() {
      String candidateId = "gsmarc://gsm/DraftTenantArchetype/v1";
      ObjectNode tenantSchema = MAPPER.createObjectNode();
      tenantSchema.put("$id", candidateId);
      tenantSchema.put("title", "DraftTenantArchetype");
      tenantSchema.put("type", "object");
      tenantSchema.putObject("properties").putObject("name").put("type", "string");
      tenantSchema.putArray("required").add("name");

      ArchetypeEntity tenantArchetype = mock(ArchetypeEntity.class);
      when(tenantArchetype.getStatement()).thenReturn(tenantSchema);
      when(tenantArchetype.getStatus()).thenReturn(AscriptionStatusType.DRAFT);

      when(archetypeSchemaService.findResolvableByUri(candidateId)).thenReturn(Optional.empty());

      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "CompositeWithDraft");
      schema.put("type", "object");
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", candidateId);
      var local = allOf.addObject();
      local.put("type", "object");
      local.putObject("properties").putObject("extra").put("type", "integer");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("name", "test").put("extra", 7);

      RuleViolationException exception = assertThrows(
          RuleViolationException.class,
          () -> svc.validateStatement(statement, archetype, DefinitionSubjectType.STRUCTURE));
      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY, exception.getRuleType());
      verify(archetypeSchemaService).findResolvableByUri(candidateId);
    }

    @Test
    void refsInsideDataValuedKeywords_areNotResolvedAsSchemaReferences() {
      String defaultValue = "gsmarc://tenant/MissingDefaultValue/v1";
      String constValue = "gsmarc://tenant/MissingConstValue/v1";
      String enumValue = "gsmarc://tenant/MissingEnumValue/v1";
      String exampleValue = "gsmarc://tenant/MissingExampleValue/v1";
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("type", "object");
      ObjectNode payload = schema.putObject("properties").putObject("payload");
      payload.put("type", "object");
      payload.putObject("default").put("$ref", defaultValue);
      payload.set("const", MAPPER.createObjectNode().put("$ref", constValue));
      payload.putArray("enum").addObject().put("$ref", enumValue);
      payload.putArray("examples").addObject().put("$ref", exampleValue);

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);

      assertDoesNotThrow(
          () -> svc.validateStatement(
              MAPPER.createObjectNode(), archetype, DefinitionSubjectType.STRUCTURE));
      verify(archetypeSchemaService, never()).findResolvableByUri(defaultValue);
      verify(archetypeSchemaService, never()).findResolvableByUri(constValue);
      verify(archetypeSchemaService, never()).findResolvableByUri(enumValue);
      verify(archetypeSchemaService, never()).findResolvableByUri(exampleValue);
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

      RuleViolationException ex = assertThrows(
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

      RuleViolationException ex = assertThrows(
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

      RuleViolationException ex = assertThrows(
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
    }
  }

  // ========================================================================
  // Statement closure (GSM §5)
  // ========================================================================

  /**
   * Closure is applied by DM at validation time, on a schema object that applies
   * the whole resolved
   * chain. These tests pin the behaviour a base-level
   * {@code unevaluatedProperties} could not
   * provide: extension properties are accepted, undeclared properties are not.
   */
  @Nested
  class StatementClosure {

    /**
     * Mirrors the ITIP shape: a typing archetype extending a GSM base with domain
     * properties.
     */
    private ObjectNode sourcedDirectiveSchema() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("$id", "gsmarc://gsm-ontology/SourcedDirective/v1");
      schema.put("title", "SourcedDirective");
      schema.put("type", "object");
      schema.put("$ref", "gsmarc://gsm/Directive/v1");
      schema.putArray("required").add("source");
      ObjectNode props = schema.putObject("properties");
      props.putObject("source").put("type", "string");
      props.putObject("deploymentNotes").put("type", "string");
      return schema;
    }

    private ObjectNode sourcedDirectiveStatement() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("structure", "019cc49a-dc59-7fef-9b0c-b3f100044603");
      statement.put("modal", "MUST");
      statement.put("verb", "ENSURE");
      statement.put("qualifier", "gsmarc://tenant/SecurityProperties/v1");
      statement.put("purpose", "payment-processing");
      statement.put("source", "GDPR Article 5");
      return statement;
    }

    @Test
    void extensionProperties_acceptedThroughRefChainToGsmBase() {
      ArchetypeEntity archetype = stubArchetypeWithSchema(sourcedDirectiveSchema());

      assertDoesNotThrow(
          () -> svc.validateStatement(
              sourcedDirectiveStatement(), archetype, DefinitionSubjectType.DIRECTIVE));
    }

    @Test
    void undeclaredProperty_rejectedByAppliedClosure() {
      ArchetypeEntity archetype = stubArchetypeWithSchema(sourcedDirectiveSchema());
      ObjectNode statement = sourcedDirectiveStatement().put("bogus", "x");

      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> svc.validateStatement(statement, archetype, DefinitionSubjectType.DIRECTIVE));
      assertTrue(ex.getMessage().contains("bogus"));
    }

    @Test
    void schemaKeywordLeakedIntoStatement_rejected() {
      ArchetypeEntity archetype = stubArchetypeWithSchema(sourcedDirectiveSchema());
      ObjectNode statement = sourcedDirectiveStatement().put("$comment", "leaked");

      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> svc.validateStatement(statement, archetype, DefinitionSubjectType.DIRECTIVE));
      assertTrue(ex.getMessage().contains("$comment"));
    }

    @Test
    void selfClosingSchema_notModified() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "SelfClosing");
      schema.put("type", "object");
      schema.putObject("properties").putObject("name").put("type", "string");
      schema.put("unevaluatedProperties", false);

      assertEquals(
          schema,
          AscriptionParsingValidationService.applyStatementClosure(
              schema, DefinitionSubjectType.STRUCTURE));

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      assertDoesNotThrow(
          () -> svc.validateStatement(
              MAPPER.createObjectNode().put("name", "ok"),
              archetype,
              DefinitionSubjectType.STRUCTURE));
      assertThrows(
          RuleViolationException.class,
          () -> svc.validateStatement(
              MAPPER.createObjectNode().put("name", "ok").put("bogus", 1),
              archetype,
              DefinitionSubjectType.STRUCTURE));
    }

    @Test
    void additionalPropertiesFalse_countsAsClosure() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "ClosedWithAdditionalProperties");
      schema.put("type", "object");
      schema.putObject("properties").putObject("name").put("type", "string");
      schema.put("additionalProperties", false);

      assertEquals(
          schema,
          AscriptionParsingValidationService.applyStatementClosure(
              schema, DefinitionSubjectType.STRUCTURE));
    }

    @Test
    void permissiveDeclaration_stillClosed() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "OptingOut");
      schema.put("type", "object");
      schema.putObject("properties").putObject("name").put("type", "string");
      schema.put("unevaluatedProperties", true);

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);

      assertThrows(
          RuleViolationException.class,
          () -> svc.validateStatement(
              MAPPER.createObjectNode().put("name", "ok").put("bogus", 1),
              archetype,
              DefinitionSubjectType.STRUCTURE));
    }

    @Test
    void openSchema_closedWithoutAlteringOriginal() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "Open");
      schema.put("type", "object");
      ObjectNode original = schema.deepCopy();

      var closed = AscriptionParsingValidationService.applyStatementClosure(
          schema, DefinitionSubjectType.STRUCTURE);

      assertEquals(false, closed.get("unevaluatedProperties").booleanValue());
      assertEquals("Open", closed.get("title").asText());
      assertEquals(original, schema);
    }

    /**
     * An Archetype statement is itself a JSON Schema, and the sealed Archetype
     * meta-schema is
     * deliberately open so tenants may declare vocabulary keywords (e.g. ITIP's
     * {@code framework}).
     * Closing it would reject every framework archetype.
     */
    @Test
    void archetypeStatements_exemptFromClosure() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "SomeMetaSchema");
      schema.put("type", "object");

      assertEquals(
          schema,
          AscriptionParsingValidationService.applyStatementClosure(
              schema, DefinitionSubjectType.ARCHETYPE));
    }

    @Test
    void archetypeStatement_mayCarryVocabularyKeywords() {
      ObjectNode metaSchema = MAPPER.createObjectNode();
      metaSchema.put("title", "OpenMetaSchema");
      metaSchema.put("type", "object");
      metaSchema.putObject("properties").putObject("title").put("type", "string");

      ArchetypeEntity archetype = stubArchetypeWithSchema(metaSchema);
      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("title", "GdprProcessingPrinciple");
      statement.putObject("framework").put("name", "gdpr").put("version", "1.0.0");

      assertDoesNotThrow(
          () -> svc.validateStatement(statement, archetype, DefinitionSubjectType.ARCHETYPE));
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private ArchetypeEntity stubArchetypeWithSchema(ObjectNode schema) {
    ArchetypeEntity archetype = mock(ArchetypeEntity.class);
    when(archetype.getStatement()).thenReturn(schema);

    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(UUID.randomUUID());
    when(archetype.getDefinition()).thenReturn(def);

    return archetype;
  }
}
