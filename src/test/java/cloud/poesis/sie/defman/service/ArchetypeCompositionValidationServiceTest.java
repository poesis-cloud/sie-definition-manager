package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ArchetypeCompositionValidationServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ArchetypeCompositionValidationService service;

  /**
   * Maps exact Archetype URIs to their JSON Schemas; simulates the repository.
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
      ObjectNode schema = MAPPER
          .createObjectNode()
          .put("$id", "gsmarc://gsm/Structure/v1")
          .put("title", "Structure");
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
        ObjectNode schema = MAPPER
            .createObjectNode()
            .put("$id", "gsmarc://gsm/" + title + "/v1")
            .put("title", title);
        assertDoesNotThrow(
            () -> service.validateSchemaComposition(schema, schemaResolver),
            "Expected exempt: " + title);
      }
    }

    @Test
    void tenantArchetypeWithBaseTitle_isNotExempt() {
      ObjectNode schema = MAPPER
          .createObjectNode()
          .put("$id", "gsmarc://tenant/Structure/v1")
          .put("title", "Structure")
          .put("$ref", "gsmarc://tenant/MissingBase/v1");

      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));

      assertTrue(ex.getMessage().contains("gsmarc://tenant/MissingBase/v1"));
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
      schemaStore.put(
          "gsmarc://gsm/SecurityProperties/v1", schemaNode("SecurityProperties", false));

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
      schemaStore.put(
          "gsmarc://gsm/SecurityProperties/v1", schemaNode("SecurityProperties", false));

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
      schemaStore.put("gsmarc://gsm/Archetype/v1", schemaNode("Archetype", true));

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
      schemaStore.put("gsmarc://gsm/B/v1", schemaB);

      ObjectNode schema = MAPPER.createObjectNode().put("$id", "gsmarc://gsm/A/v1").put("title", "A");
      schema.put("$ref", "gsmarc://gsm/B/v1");

      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));
      assertTrue(ex.getMessage().contains("Cycle") || ex.getMessage().contains("already visited"));
      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_ACYCLICITY, ex.getRuleType());
    }

    @Test
    void unresolvableAllOfIntermediary_rejectedAtAuthoring() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantType");
      schema.putArray("allOf").addObject().put("$ref", "gsmarc://gsm/NonExistent/v1");

      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));
      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY, ex.getRuleType());
    }

    @Test
    void unresolvableAllOfIntermediary_strictAtActivation() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantType");
      schema.putArray("allOf").addObject().put("$ref", "gsmarc://gsm/NonExistent/v1");

      // Activation-time (strict=true): rejects unresolvable allOf intermediary.
      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));
      assertTrue(ex.getMessage().contains("Cannot resolve"));
      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY, ex.getRuleType());
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
      schemaStore.put("gsmarc://gsm/SealedFacet/v1", sealedSchema);

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
      schemaStore.put("gsmarc://gsm/MiddleLayer/v1", midSchema);

      ObjectNode schema = MAPPER.createObjectNode().put("title", "TopLevel");
      schema.put("$ref", "gsmarc://gsm/MiddleLayer/v1");

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void intermediaryResolution_usesExactUri() {
      String intermediaryId = "gsmarc://tenant/layers/MiddleLayer/v1";
      ObjectNode midSchema = schemaNode("MiddleLayer", false);
      midSchema.put("$id", intermediaryId);
      midSchema.put("$ref", "gsmarc://gsm/Structure/v1");
      schemaStore.put(intermediaryId, midSchema);

      ObjectNode schema = MAPPER
          .createObjectNode()
          .put("$id", "gsmarc://tenant/TopLevel/v1")
          .put("title", "TopLevel")
          .put("$ref", intermediaryId);

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void noTitleInSchema_accepted() {
      ObjectNode schema = MAPPER.createObjectNode();
      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }
  }

  @Nested
  class ConservativeCompositionParity {

    @Test
    void siblingInlineFacetsWithSharedResolvedProperty_rejected() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "Conflicted");
      var allOf = schema.putArray("allOf");
      allOf.add(objectSchema("shared", "string"));
      allOf.add(objectSchema("shared", "string"));

      RuleViolationException exception = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));

      assertEquals(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_PROPERTY_DISJOINTNESS,
          exception.getRuleType());
      assertEquals("allOf", exception.getExtensions().get("field"));
    }

    @Test
    void siblingLocalExternalAndTransitiveFacetsUseResolvedProperties() {
      String externalId = "gsmarc://tenant/ExternalFacet/v1";
      String transitiveId = "gsmarc://tenant/TransitiveFacet/v1";
      schemaStore.put(externalId, objectSchema("externalOnly", "string"));
      schemaStore.put(
          transitiveId,
          MAPPER
              .createObjectNode()
              .put("$id", transitiveId)
              .putArray("allOf")
              .addObject()
              .put("$ref", externalId));

      ObjectNode schema = MAPPER.createObjectNode().put("title", "Conflicted");
      schema.putObject("$defs").set("LocalFacet", objectSchema("localOnly", "boolean"));
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", "#/$defs/LocalFacet");
      allOf.addObject().put("$ref", transitiveId);
      allOf.add(objectSchema("externalOnly", "string"));

      RuleViolationException exception = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));

      assertEquals(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_PROPERTY_DISJOINTNESS,
          exception.getRuleType());
    }

    @Test
    void siblingLocalFacetsWithSharedResolvedProperty_rejected() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "LocalConflict");
      ObjectNode definitions = schema.putObject("$defs");
      definitions.set("First", objectSchema("shared", "string"));
      definitions.set("Second", objectSchema("shared", "string"));
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", "#/$defs/First");
      allOf.addObject().put("$ref", "#/$defs/Second");

      assertDisjointnessViolation(schema);
    }

    @Test
    void siblingExternalFacetsWithSharedResolvedProperty_rejected() {
      String firstId = "gsmarc://tenant/FirstFacet/v1";
      String secondId = "gsmarc://tenant/SecondFacet/v1";
      schemaStore.put(firstId, objectSchema("shared", "string"));
      schemaStore.put(secondId, objectSchema("shared", "string"));
      ObjectNode schema = MAPPER.createObjectNode().put("title", "ExternalConflict");
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", firstId);
      allOf.addObject().put("$ref", secondId);

      assertDisjointnessViolation(schema);
    }

    @Test
    void siblingTransitiveFacetsWithSharedResolvedProperty_rejected() {
      String sourceId = "gsmarc://tenant/SourceFacet/v1";
      String firstId = "gsmarc://tenant/FirstTransitiveFacet/v1";
      String secondId = "gsmarc://tenant/SecondTransitiveFacet/v1";
      schemaStore.put(sourceId, objectSchema("shared", "string"));
      schemaStore.put(firstId, refSchema(firstId, sourceId));
      schemaStore.put(secondId, refSchema(secondId, sourceId));
      ObjectNode schema = MAPPER.createObjectNode().put("title", "TransitiveConflict");
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", firstId);
      allOf.addObject().put("$ref", secondId);

      assertDisjointnessViolation(schema);
    }

    @Test
    void hostTypeChangeAgainstInheritedProperty_rejected() {
      String baseId = "gsmarc://tenant/BaseFacet/v1";
      schemaStore.put(baseId, objectSchema("value", "string"));
      ObjectNode schema = objectSchema("value", "integer");
      schema.put("$ref", baseId);

      RuleViolationException exception = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));

      assertEquals(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_PROPERTY_TYPE_STABILITY,
          exception.getRuleType());
      assertEquals("/properties/value", exception.getExtensions().get("field"));
    }

    @Test
    void hostTypeChangeAgainstInlineAndLocalFacets_rejected() {
      ObjectNode inlineSchema = objectSchema("value", "integer");
      inlineSchema.putArray("allOf").add(objectSchema("value", "string"));
      assertTypeStabilityViolation(inlineSchema);

      ObjectNode localSchema = objectSchema("value", "integer");
      localSchema.putObject("$defs").set("Facet", objectSchema("value", "string"));
      localSchema.putArray("allOf").addObject().put("$ref", "#/$defs/Facet");
      assertTypeStabilityViolation(localSchema);
    }

    @Test
    void hostTypeChangeAgainstExternalAndTransitiveFacets_rejected() {
      String externalId = "gsmarc://tenant/ExternalTypedFacet/v1";
      String transitiveId = "gsmarc://tenant/TransitiveTypedFacet/v1";
      schemaStore.put(externalId, objectSchema("value", "string"));
      schemaStore.put(transitiveId, refSchema(transitiveId, externalId));

      ObjectNode externalSchema = objectSchema("value", "integer");
      externalSchema.putArray("allOf").addObject().put("$ref", externalId);
      assertTypeStabilityViolation(externalSchema);

      ObjectNode transitiveSchema = objectSchema("value", "integer");
      transitiveSchema.putArray("allOf").addObject().put("$ref", transitiveId);
      assertTypeStabilityViolation(transitiveSchema);
    }

    @Test
    void scalarAndArrayTypeSetsNormalizeAsEqual() {
      String facetId = "gsmarc://tenant/Facet/v1";
      schemaStore.put(facetId, objectSchema("value", "string"));
      ObjectNode schema = MAPPER.createObjectNode().put("title", "Stable");
      schema.putObject("properties").putObject("value").putArray("type").add("string");
      schema.putArray("allOf").addObject().put("$ref", facetId);

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void absentType_isNotInferred() {
      String facetId = "gsmarc://tenant/Facet/v1";
      ObjectNode facet = MAPPER.createObjectNode();
      facet.putObject("properties").putObject("value").putArray("enum").add("one").add("two");
      schemaStore.put(facetId, facet);
      ObjectNode schema = MAPPER.createObjectNode().put("title", "Narrowed");
      schema.putObject("properties").putObject("value").put("type", "string").put("const", "one");
      schema.putArray("allOf").addObject().put("$ref", facetId);

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void booleanFacet_hasNoExplicitTypeSet() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "BooleanFacet");
      schema.putArray("allOf").add(false);

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void sameTypeNarrowing_isAccepted() {
      ObjectNode schema = objectSchema("value", "string");
      schema.withObject("/properties/value").put("const", "one");
      schema.putArray("allOf").add(objectSchema("value", "string"));

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void differingArrayTypeSets_rejected() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "ArrayTypeConflict");
      schema.putObject("properties").putObject("value").putArray("type").add("string").add("null");
      ObjectNode facet = MAPPER.createObjectNode();
      facet.putObject("properties").putObject("value").putArray("type").add("integer").add("null");
      schema.putArray("allOf").add(facet);

      assertTypeStabilityViolation(schema);
    }

    @Test
    void typeStabilityPointer_escapesPropertyName() {
      ObjectNode schema = objectSchema("path/with~tokens", "integer");
      schema.putArray("allOf").add(objectSchema("path/with~tokens", "string"));

      RuleViolationException exception = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));

      assertEquals(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_PROPERTY_TYPE_STABILITY,
          exception.getRuleType());
      assertEquals("/properties/path~1with~0tokens", exception.getExtensions().get("field"));
    }

    @Test
    void missingLocalFacet_rejected() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "MissingLocalFacet");
      schema.putArray("allOf").addObject().put("$ref", "#/$defs/Missing");

      RuleViolationException exception = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));

      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY, exception.getRuleType());
    }

    @Test
    void unsupportedLocalAnchorFacet_rejected() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "UnsupportedAnchor");
      schema.putArray("allOf").addObject().put("$ref", "#named-anchor");

      RuleViolationException exception = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));

      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY, exception.getRuleType());
    }

    @Test
    void transitiveLocalAllOfCycle_rejected() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "LocalCycle");
      ObjectNode loop = MAPPER.createObjectNode();
      loop.putArray("allOf").addObject().put("$ref", "#/$defs/Loop");
      schema.putObject("$defs").set("Loop", loop);
      schema.putArray("allOf").addObject().put("$ref", "#/$defs/Loop");

      RuleViolationException exception = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));

      assertEquals(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_ACYCLICITY, exception.getRuleType());
    }

    @Test
    void transitiveExternalAllOfCycle_rejected() {
      String firstId = "gsmarc://tenant/FirstCycleFacet/v1";
      String secondId = "gsmarc://tenant/SecondCycleFacet/v1";
      ObjectNode first = MAPPER.createObjectNode().put("$id", firstId);
      first.putArray("allOf").addObject().put("$ref", secondId);
      ObjectNode second = MAPPER.createObjectNode().put("$id", secondId);
      second.putArray("allOf").addObject().put("$ref", firstId);
      schemaStore.put(firstId, first);
      schemaStore.put(secondId, second);
      ObjectNode schema = MAPPER.createObjectNode().put("title", "ExternalCycle");
      schema.putArray("allOf").addObject().put("$ref", firstId);

      RuleViolationException exception = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));

      assertEquals(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_ACYCLICITY, exception.getRuleType());
    }

    @Test
    void rootLocalAllOfCycle_rejected() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "RootLocalCycle");
      schema.putArray("allOf").addObject().put("$ref", "#");

      RuleViolationException exception = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));

      assertEquals(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_ACYCLICITY, exception.getRuleType());
    }

    @Test
    void booleanPropertySchema_hasNoExplicitTypeSet() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "BooleanProperty");
      schema.putObject("properties").put("value", false);
      schema.putArray("allOf").add(objectSchema("value", "string"));

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void transitiveFacetWithInvalidExternalRef_rejected() {
      String facetId = "gsmarc://tenant/InvalidRefFacet/v1";
      ObjectNode facet = MAPPER.createObjectNode().put("$id", facetId);
      facet.putArray("allOf").addObject().put("$ref", "https://example.com/facet");
      schemaStore.put(facetId, facet);
      ObjectNode schema = MAPPER.createObjectNode().put("title", "InvalidTransitiveRef");
      schema.putArray("allOf").addObject().put("$ref", facetId);

      RuleViolationException exception = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));

      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_REF_NORM, exception.getRuleType());
    }

    @Test
    void transitiveFacetWithUnresolvedRef_rejected() {
      String facetId = "gsmarc://tenant/UnresolvedRefFacet/v1";
      ObjectNode facet = MAPPER.createObjectNode().put("$id", facetId);
      facet.putArray("allOf").addObject().put("$ref", "gsmarc://tenant/MissingFacet/v1");
      schemaStore.put(facetId, facet);
      ObjectNode schema = MAPPER.createObjectNode().put("title", "UnresolvedTransitiveRef");
      schema.putArray("allOf").addObject().put("$ref", facetId);

      RuleViolationException exception = assertThrows(
          RuleViolationException.class,
          () -> service.validateSchemaComposition(schema, schemaResolver));

      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY, exception.getRuleType());
    }

    @Test
    void trueAndFalseBooleanFacets_areTerminal() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "BooleanFacets");
      schema.putArray("allOf").add(true).add(false);

      assertDoesNotThrow(() -> service.validateSchemaComposition(schema, schemaResolver));
    }

    @Test
    void mountWrappersRemainDisjoint() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "Mounted");
      var allOf = schema.putArray("allOf");
      allOf.add(objectSchema("security", "object"));
      allOf.add(objectSchema("operations", "object"));

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
      assertEquals(Set.of("gsmarc://gsm/Structure/v1"), bases);
    }

    @Test
    void refViaIntermediary_returnsBase() {
      ObjectNode midSchema = schemaNode("MiddleLayer", false);
      midSchema.put("$ref", "gsmarc://gsm/Mechanism/v1");
      schemaStore.put("gsmarc://gsm/MiddleLayer/v1", midSchema);

      Set<String> bases = service.resolveGsmBases("gsmarc://gsm/MiddleLayer/v1", "MyMechanism", schemaResolver);
      assertEquals(Set.of("gsmarc://gsm/Mechanism/v1"), bases);
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

  private static ObjectNode objectSchema(String property, String type) {
    ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
    schema.putObject("properties").putObject(property).put("type", type);
    return schema;
  }

  private static ObjectNode refSchema(String id, String ref) {
    return MAPPER.createObjectNode().put("$id", id).put("$ref", ref);
  }

  private void assertDisjointnessViolation(ObjectNode schema) {
    RuleViolationException exception = assertThrows(
        RuleViolationException.class,
        () -> service.validateSchemaComposition(schema, schemaResolver));
    assertEquals(
        AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_PROPERTY_DISJOINTNESS,
        exception.getRuleType());
    assertEquals("allOf", exception.getExtensions().get("field"));
  }

  private void assertTypeStabilityViolation(ObjectNode schema) {
    RuleViolationException exception = assertThrows(
        RuleViolationException.class,
        () -> service.validateSchemaComposition(schema, schemaResolver));
    assertEquals(
        AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_PROPERTY_TYPE_STABILITY,
        exception.getRuleType());
    assertEquals("/properties/value", exception.getExtensions().get("field"));
  }
}
