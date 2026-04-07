package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;

class ArchetypeCompositionValidationServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ArchetypeCompositionValidationService service;

  /**
   * Maps archetype titles to their JSON Schemas; simulates the archetype
   * repository.
   */
  private Map<String, JsonNode> schemaStore;

  private Function<String, JsonNode> schemaResolver;

  @BeforeEach
  void setUp() {
    service = new ArchetypeCompositionValidationService();
    schemaStore = new HashMap<>();
    schemaResolver = schemaStore::get;
  }

  // ========================================================================
  // Schema composition validation ($ref chain + allOf facets)
  // ========================================================================

  @Nested
  class SchemaComposition {

    @Test
    void baseArchetype_exempt() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "Structure");
      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void baseArchetype_allExempt() {
      for (String title : List.of(
          "Structure",
          "Mechanism",
          "Interaction",
          "Archetype",
          "Effector",
          "Receptor",
          "Directive",
          "Norm")) {
        ObjectNode schema = MAPPER.createObjectNode().put("title", title);
        assertDoesNotThrow(
            () -> service.validateSchemaComposition(schema, schemaResolver),
            "Expected exempt: " + title);
      }
    }

    @Test
    void noRefNoAllOf_rootlessAccepted() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "SecurityProperties");
      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void emptyAllOf_rootlessAccepted() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "SecurityProperties");
      schema.putArray("allOf");
      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void allOfWithOnlyRootlessIntermediary_accepted() {
      schemaStore.put("SecurityProperties", schemaNode("SecurityProperties", false));

      ObjectNode schema = MAPPER.createObjectNode().put("title", "DetailedSecurity");
      schema.putArray("allOf").addObject().put("$ref", "gsmarc://gsm/SecurityProperties/v1");

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void allOfWithInlineEntriesOnly_rootlessAccepted() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "InlineFacet");
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("type", "object");

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void refBaseWithAllOfFacet_accepted() {
      schemaStore.put("SecurityProperties", schemaNode("SecurityProperties", false));

      ObjectNode schema = MAPPER.createObjectNode().put("title", "SecuredStructure");
      schema.put("$ref", "gsmarc://gsm/Structure/v1");
      schema.putArray("allOf").addObject().put("$ref", "gsmarc://gsm/SecurityProperties/v1");

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void directRefToBase_accepted() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "MyStructure");
      schema.put("$ref", "gsmarc://gsm/Structure/v1");

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void refToSealedBase_rejected() {
      schemaStore.put("Archetype", schemaNode("Archetype", true));

      ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantMeta");
      schema.put("$ref", "gsmarc://gsm/Archetype/v1");

      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));
      assertTrue(ex.getMessage().contains("sealed"));
      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_NON_SEALED, ex.getRuleType());
    }

    @Test
    void refToUnsealedEffectorBase_accepted() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "mTLSEffector");
      schema.put("$ref", "gsmarc://gsm/Effector/v1");

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void refToUnsealedReceptorBase_accepted() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "WebhookReceptor");
      schema.put("$ref", "gsmarc://gsm/Receptor/v1");

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void refToUnsealedDirectiveBase_accepted() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "Principle");
      schema.put("$ref", "gsmarc://gsm/Directive/v1");

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void refToUnsealedNormBase_accepted() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "Measure");
      schema.put("$ref", "gsmarc://gsm/Norm/v1");

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void invalidRefInAllOf_rejected() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantThing");
      schema.putArray("allOf").addObject().put("$ref", "https://example.com/not-gsm");

      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));
      assertTrue(ex.getMessage().contains("gsmarc://"));
      assertEquals(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_EXCLUSIVE_BASE_CONVERGENCE,
          ex.getRuleType());
    }

    @Test
    void allOfWithMultipleGsmBases_accepted() {
      // Under Option B, allOf entries are facets — they don't determine subject type.
      // Including multiple GSM bases in allOf is allowed (adds their properties).
      ObjectNode schema = MAPPER.createObjectNode().put("title", "RichFacet");
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", "gsmarc://gsm/Structure/v1");
      allOf.addObject().put("$ref", "gsmarc://gsm/Mechanism/v1");

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void cycleInRefChain_rejected() {
      // A's $ref → B, B's $ref → A
      ObjectNode schemaB = schemaNode("B", false);
      schemaB.put("$ref", "gsmarc://gsm/A/v1");
      schemaStore.put("B", schemaB);

      ObjectNode schema = MAPPER.createObjectNode().put("title", "A");
      schema.put("$ref", "gsmarc://gsm/B/v1");

      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));
      assertTrue(ex.getMessage().contains("Cycle") || ex.getMessage().contains("already visited"));
      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_ACYCLICITY, ex.getRuleType());
    }

    @Test
    void unresolvableAllOfIntermediary_lenientAtAuthoring() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantType");
      schema.putArray("allOf").addObject().put("$ref", "gsmarc://gsm/NonExistent/v1");

      // Authoring-time (strict=false): warns and skips unresolvable allOf
      // intermediary.
      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void unresolvableAllOfIntermediary_strictAtActivation() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantType");
      schema.putArray("allOf").addObject().put("$ref", "gsmarc://gsm/NonExistent/v1");

      // Activation-time (strict=true): rejects unresolvable allOf intermediary.
      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, true, schemaResolver));
      assertTrue(ex.getMessage().contains("Cannot resolve"));
      assertEquals(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_EXCLUSIVE_BASE_CONVERGENCE,
          ex.getRuleType());
    }
  }

  // ========================================================================
  // Schema composition extras ($ref chain + allOf facets)
  // ========================================================================

  @Nested
  class SchemaCompositionExtras {

    @Test
    void sealedAllOfIntermediary_rejected() {
      ObjectNode sealedSchema = schemaNode("SealedFacet", false);
      sealedSchema.put("$gsm:sealed", true);
      schemaStore.put("SealedFacet", sealedSchema);

      ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantType");
      schema.putArray("allOf").addObject().put("$ref", "gsmarc://gsm/SealedFacet/v1");

      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));
      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_NON_SEALED, ex.getRuleType());
      assertTrue(ex.getMessage().contains("SealedFacet"));
    }

    @Test
    void intermediaryRefChain_walksRecursively() {
      // TopLevel → ($ref) → MiddleLayer → ($ref) → Structure
      ObjectNode midSchema = schemaNode("MiddleLayer", false);
      midSchema.put("$ref", "gsmarc://gsm/Structure/v1");
      schemaStore.put("MiddleLayer", midSchema);

      ObjectNode schema = MAPPER.createObjectNode().put("title", "TopLevel");
      schema.put("$ref", "gsmarc://gsm/MiddleLayer/v1");

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void noTitleInSchema_accepted() {
      ObjectNode schema = MAPPER.createObjectNode();
      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }
  }

  // ========================================================================
  // resolveGsmBases
  // ========================================================================

  @Nested
  class ResolveGsmBases {

    @Test
    void directRefToBase_returnsSingleBase() {
      Set<String> bases = service.resolveGsmBases("gsmarc://gsm/Structure/v1", "MyStruct", schemaResolver);
      assertEquals(Set.of("Structure"), bases);
    }

    @Test
    void refViaIntermediary_returnsBase() {
      ObjectNode midSchema = schemaNode("MiddleLayer", false);
      midSchema.put("$ref", "gsmarc://gsm/Mechanism/v1");
      schemaStore.put("MiddleLayer", midSchema);

      Set<String> bases = service.resolveGsmBases("gsmarc://gsm/MiddleLayer/v1", "MyMechanism", schemaResolver);
      assertEquals(Set.of("Mechanism"), bases);
    }

    @Test
    void unresolvableIntermediary_throws() {
      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> service.resolveGsmBases("gsmarc://gsm/NonExistent/v1", "MyType", schemaResolver));
      assertTrue(ex.getMessage().contains("Cannot resolve"));
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private static ObjectNode schemaNode(String title, boolean sealed) {
    ObjectNode schema = MAPPER.createObjectNode().put("title", title);
    if (sealed) {
      schema.put("$gsm:sealed", true);
    }
    return schema;
  }
}
