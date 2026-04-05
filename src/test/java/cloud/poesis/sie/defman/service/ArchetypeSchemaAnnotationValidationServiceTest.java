package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ArchetypeSchemaAnnotationValidationServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ArchetypeSchemaAnnotationValidationService service =
      new ArchetypeSchemaAnnotationValidationService();

  // ========================================================================
  // Annotation validation
  // ========================================================================

  @Nested
  class Annotation {

    @Nested
    class UnknownAnnotations {

      @Test
      void unknownTopLevelAnnotation_rejected() {
        ObjectNode schema = schemaWithProperty("x", prop("string"));
        schema.put("$gsm:bogus", true);

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateArchetypeAnnotations(schema, List.of()));
        assertEquals(
            AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
            ex.getRuleType());
      }

      @Test
      void unknownPropertyAnnotation_rejected() {
        ObjectNode propNode = prop("string");
        propNode.put("$gsm:foobar", true);
        ObjectNode schema = schemaWithProperty("x", propNode);

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateArchetypeAnnotations(schema, List.of()));
        assertTrue(ex.getMessage().contains("$gsm:foobar"));
        assertEquals(
            AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
            ex.getRuleType());
      }

      @Test
      void topLevelAnnotationOnProperty_rejected() {
        ObjectNode propNode = prop("string");
        propNode.put("$gsm:sealed", true);
        ObjectNode schema = schemaWithProperty("x", propNode);

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateArchetypeAnnotations(schema, List.of()));
        assertTrue(ex.getMessage().contains("top-level only"));
        assertEquals(
            AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
            ex.getRuleType());
      }
    }

    @Nested
    class IdentityBoundSetImmutability {

      @Test
      void firstAscription_noCheck() {
        ObjectNode propNode = prop("string");
        propNode.put("$gsm:identityBound", true);
        ObjectNode schema = schemaWithProperty("purpose", propNode);

        assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, List.of()));
      }

      @Test
      void sameIdentityBoundSet_valid() {
        ObjectNode existingProp = prop("string");
        existingProp.put("$gsm:identityBound", true);
        ObjectNode existingSchema = schemaWithProperty("purpose", existingProp);
        ArchetypeEntity existing = stubArchetypeWithSchema(existingSchema);

        ObjectNode newProp = prop("string");
        newProp.put("$gsm:identityBound", true);
        ObjectNode newSchema = schemaWithProperty("purpose", newProp);

        assertDoesNotThrow(
            () -> service.validateArchetypeAnnotations(newSchema, List.of(existing)));
      }

      @Test
      void changedIdentityBoundSet_rejected() {
        ObjectNode existingProp = prop("string");
        existingProp.put("$gsm:identityBound", true);
        ObjectNode existingSchema = schemaWithProperty("purpose", existingProp);
        ArchetypeEntity existing = stubArchetypeWithSchema(existingSchema);

        ObjectNode newProp = prop("string");
        newProp.put("$gsm:identityBound", true);
        ObjectNode newSchema = schemaWithProperty("name", newProp);

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateArchetypeAnnotations(newSchema, List.of(existing)));
        assertTrue(ex.getMessage().contains("identityBound set immutability"));
        assertEquals(
            AscriptionConsistencyRuleType.ARCHETYPE_IDENTITY_BOUND_PROPERTY_IMMUTABILITY,
            ex.getRuleType());
      }
    }

    @Nested
    class CollectIdentityBoundFields {

      @Test
      void collectsAnnotatedFields() {
        ObjectNode p1 = prop("string");
        p1.put("$gsm:identityBound", true);
        ObjectNode p2 = prop("string");
        ObjectNode p3 = prop("number");
        p3.put("$gsm:identityBound", true);

        ObjectNode schema = MAPPER.createObjectNode();
        ObjectNode props = schema.putObject("properties");
        props.set("alpha", p1);
        props.set("beta", p2);
        props.set("gamma", p3);

        Set<String> result =
            ArchetypeSchemaAnnotationValidationService.collectIdentityBoundFields(schema);
        assertEquals(Set.of("alpha", "gamma"), result);
      }

      @Test
      void noProperties_returnsEmpty() {
        ObjectNode schema = MAPPER.createObjectNode();
        Set<String> result =
            ArchetypeSchemaAnnotationValidationService.collectIdentityBoundFields(schema);
        assertTrue(result.isEmpty());
      }
    }

    @Test
    void cleanSchema_noAnnotations_valid() {
      ObjectNode schema = schemaWithProperty("env", prop("string"));

      assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, List.of()));
    }
  }

  // ========================================================================
  // $ref URI policy validation (E1 R2/R3)
  // ========================================================================

  @Nested
  class RefUriPolicy {

    @Test
    void localJsonPointer_accepted() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "MyArchetype");
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", "#/$defs/SomeLocal");

      assertDoesNotThrow(() -> service.validateRefUriPolicy(schema));
    }

    @Test
    void gsmUri_accepted() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "MyArchetype");
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", "gsm://archetypes/StructureArchetype/v1");

      assertDoesNotThrow(() -> service.validateRefUriPolicy(schema));
    }

    @Test
    void httpUri_rejected() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "MyArchetype");
      var props = schema.putObject("properties");
      props.putObject("ext").put("$ref", "http://example.com/schema.json");

      var ex =
          assertThrows(RuleViolationException.class, () -> service.validateRefUriPolicy(schema));
      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_REF_NORM, ex.getRuleType());
      assertTrue(ex.getMessage().contains("http://example.com/schema.json"));
    }

    @Test
    void httpsUri_rejected() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "MyArchetype");
      schema.putObject("$defs").putObject("Ext").put("$ref", "https://evil.com/inject.json");

      var ex =
          assertThrows(RuleViolationException.class, () -> service.validateRefUriPolicy(schema));
      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_REF_NORM, ex.getRuleType());
    }

    @Test
    void deeplyNestedRef_detected() {
      ObjectNode schema = MAPPER.createObjectNode();
      var arr = schema.putArray("allOf");
      var inner = arr.addObject().putObject("properties").putObject("nested");
      inner.put("$ref", "ftp://bad-host/schema");

      var ex =
          assertThrows(RuleViolationException.class, () -> service.validateRefUriPolicy(schema));
      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_REF_NORM, ex.getRuleType());
      assertTrue(ex.getMessage().contains("ftp://bad-host/schema"));
    }

    @Test
    void noRefs_noViolation() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "Plain");
      schema.putObject("properties").putObject("name").put("type", "string");

      assertDoesNotThrow(() -> service.validateRefUriPolicy(schema));
    }

    @Test
    void nullSchema_noViolation() {
      assertDoesNotThrow(() -> service.validateRefUriPolicy(null));
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private static ObjectNode prop(String type) {
    return MAPPER.createObjectNode().put("type", type);
  }

  private static ObjectNode schemaWithProperty(String propName, ObjectNode propNode) {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("title", "TestSchema");
    ObjectNode props = schema.putObject("properties");
    props.set(propName, propNode);
    return schema;
  }

  private static ArchetypeEntity stubArchetypeWithSchema(ObjectNode schema) {
    ArchetypeEntity archetype = mock(ArchetypeEntity.class);
    when(archetype.getStatement()).thenReturn(schema);
    return archetype;
  }
}
