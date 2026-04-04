package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
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
class AscriptionAnnotationEnforcementServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private AscriptionService ascriptionService;

  private AscriptionAnnotationEnforcementService svc;

  // Configurable existing ascriptions for identity-bound testing
  private List<AscriptionEntity> existingAscriptions = List.of();
  private final Function<UUID, List<? extends AscriptionEntity>> existingFinder =
      id -> existingAscriptions;

  @BeforeEach
  void setUp() {
    svc =
        new AscriptionAnnotationEnforcementService(
            ascriptionService, new AscriptionStatementProtectionService());
  }

  // ========================================================================
  // $gsm:unique enforcement
  // ========================================================================

  @Nested
  class EnforceUnique {

    @Test
    void uniqueProperty_noDuplicates_valid() {
      UUID defId = UUID.randomUUID();
      ObjectNode propNode = prop("string");
      propNode.put("$gsm:unique", true);
      ObjectNode archetypeSchema = schemaWithProperty("code", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("code", "ALPHA");

      when(ascriptionService.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
              any(), any(), eq(defId)))
          .thenReturn(List.of());

      assertDoesNotThrow(() -> svc.enforceAnnotations(statement, archetype, defId, existingFinder));
    }

    @Test
    void uniqueProperty_duplicateInEffect_rejected() {
      UUID defId = UUID.randomUUID();
      ObjectNode propNode = prop("string");
      propNode.put("$gsm:unique", true);
      ObjectNode archetypeSchema = schemaWithProperty("code", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("code", "ALPHA");

      AscriptionEntity existing = mock(AscriptionEntity.class);
      when(existing.getId()).thenReturn(UUID.randomUUID());
      DefinitionEntity existingDef = mock(DefinitionEntity.class);
      when(existingDef.getId()).thenReturn(UUID.randomUUID());
      when(existing.getDefinition()).thenReturn(existingDef);
      when(existing.getStatement()).thenReturn(MAPPER.createObjectNode().put("code", "ALPHA"));

      when(ascriptionService.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
              any(), any(), eq(defId)))
          .thenReturn(List.of(existing));

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> svc.enforceAnnotations(statement, archetype, defId, existingFinder));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("duplicates"));
      assertTrue(ex.getMessage().contains("code"));
    }

    @Test
    void uniqueProperty_differentValues_valid() {
      UUID defId = UUID.randomUUID();
      ObjectNode propNode = prop("string");
      propNode.put("$gsm:unique", true);
      ObjectNode archetypeSchema = schemaWithProperty("code", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("code", "ALPHA");

      AscriptionEntity existing = mock(AscriptionEntity.class);
      when(existing.getStatement()).thenReturn(MAPPER.createObjectNode().put("code", "BETA"));

      when(ascriptionService.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
              any(), any(), eq(defId)))
          .thenReturn(List.of(existing));

      assertDoesNotThrow(() -> svc.enforceAnnotations(statement, archetype, defId, existingFinder));
    }
  }

  // ========================================================================
  // $gsm:identityBound enforcement on statement properties (schema-driven)
  // ========================================================================

  @Nested
  class EnforceStatementIdentityBound {

    @Test
    void identityBoundProperty_firstAscription_passes() {
      UUID defId = UUID.randomUUID();
      ObjectNode propNode = prop("string");
      propNode.put("$gsm:identityBound", true);
      ObjectNode archetypeSchema = schemaWithProperty("region", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("region", "eu-west-1");

      existingAscriptions = List.of();

      assertDoesNotThrow(() -> svc.enforceAnnotations(statement, archetype, defId, existingFinder));
    }

    @Test
    void identityBoundProperty_sameValue_passes() {
      UUID defId = UUID.randomUUID();
      ObjectNode propNode = prop("string");
      propNode.put("$gsm:identityBound", true);
      ObjectNode archetypeSchema = schemaWithProperty("region", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("region", "eu-west-1");

      AscriptionEntity existing = mock(AscriptionEntity.class);
      DefinitionEntity existingDef = mock(DefinitionEntity.class);
      when(existingDef.getId()).thenReturn(defId);
      when(existing.getDefinition()).thenReturn(existingDef);
      when(existing.getStatement())
          .thenReturn(MAPPER.createObjectNode().put("region", "eu-west-1"));

      existingAscriptions = List.of(existing);

      assertDoesNotThrow(() -> svc.enforceAnnotations(statement, archetype, defId, existingFinder));
    }

    @Test
    void identityBoundProperty_differentValue_rejected() {
      UUID defId = UUID.randomUUID();
      ObjectNode propNode = prop("string");
      propNode.put("$gsm:identityBound", true);
      ObjectNode archetypeSchema = schemaWithProperty("region", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("region", "us-east-1");

      AscriptionEntity existing = mock(AscriptionEntity.class);
      DefinitionEntity existingDef = mock(DefinitionEntity.class);
      when(existingDef.getId()).thenReturn(defId);
      when(existing.getDefinition()).thenReturn(existingDef);
      when(existing.getStatement())
          .thenReturn(MAPPER.createObjectNode().put("region", "eu-west-1"));

      existingAscriptions = List.of(existing);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> svc.enforceAnnotations(statement, archetype, defId, existingFinder));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Identity-bound field"));
      assertTrue(ex.getMessage().contains("region"));
    }

    @Test
    void identityBoundProperty_missingInFirstAscription_passes() {
      UUID defId = UUID.randomUUID();
      ObjectNode propNode = prop("string");
      propNode.put("$gsm:identityBound", true);
      ObjectNode archetypeSchema = schemaWithProperty("region", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("region", "eu-west-1");

      AscriptionEntity existing = mock(AscriptionEntity.class);
      DefinitionEntity existingDef = mock(DefinitionEntity.class);
      when(existingDef.getId()).thenReturn(defId);
      when(existing.getDefinition()).thenReturn(existingDef);
      when(existing.getStatement()).thenReturn(MAPPER.createObjectNode());

      existingAscriptions = List.of(existing);

      assertDoesNotThrow(() -> svc.enforceAnnotations(statement, archetype, defId, existingFinder));
    }
  }

  // ========================================================================
  // $gsm:dataProtection atRest enforcement
  // ========================================================================

  @Nested
  class EnforceDataProtectionAtRest {

    @Test
    void hashAtRest_replacesValueWithHash() {
      ObjectNode propNode = prop("string");
      ObjectNode dp = MAPPER.createObjectNode();
      ObjectNode atRest = dp.putObject("atRest");
      atRest.putObject("hash").put("algorithm", "SHA-256");
      propNode.set("$gsm:dataProtection", dp);
      ObjectNode archetypeSchema = schemaWithProperty("ssn", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("ssn", "123-45-6789");

      assertDoesNotThrow(
          () -> svc.enforceAnnotations(statement, archetype, UUID.randomUUID(), existingFinder));

      String hashed = statement.get("ssn").asText();
      assertTrue(hashed.matches("[0-9a-f]{64}"), "Expected SHA-256 hex hash, got: " + hashed);
    }

    @Test
    void maskAtRest_masksValue() {
      ObjectNode propNode = prop("string");
      ObjectNode dp = MAPPER.createObjectNode();
      ObjectNode atRest = dp.putObject("atRest");
      ObjectNode mask = atRest.putObject("mask");
      mask.put("from", "RIGHT");
      ObjectNode with = mask.putObject("with");
      with.put("character", "*");
      with.put("occurrence", 4);
      propNode.set("$gsm:dataProtection", dp);
      ObjectNode archetypeSchema = schemaWithProperty("phone", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("phone", "555-1234");

      assertDoesNotThrow(
          () -> svc.enforceAnnotations(statement, archetype, UUID.randomUUID(), existingFinder));

      String masked = statement.get("phone").asText();
      assertTrue(masked.endsWith("1234"), "Expected last 4 chars visible, got: " + masked);
      assertTrue(masked.contains("*"), "Expected masking characters, got: " + masked);
    }

    @Test
    void maskAtRest_leftDirection_masksCorrectly() {
      ObjectNode propNode = prop("string");
      ObjectNode dp = MAPPER.createObjectNode();
      ObjectNode atRest = dp.putObject("atRest");
      ObjectNode mask = atRest.putObject("mask");
      mask.put("from", "LEFT");
      ObjectNode with = mask.putObject("with");
      with.put("character", "#");
      with.put("occurrence", 3);
      propNode.set("$gsm:dataProtection", dp);
      ObjectNode archetypeSchema = schemaWithProperty("card", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("card", "4111222233334444");

      assertDoesNotThrow(
          () -> svc.enforceAnnotations(statement, archetype, UUID.randomUUID(), existingFinder));

      String masked = statement.get("card").asText();
      assertEquals("411#############", masked);
    }

    @Test
    void maskAtRest_shortValue_masksEntirely() {
      ObjectNode propNode = prop("string");
      ObjectNode dp = MAPPER.createObjectNode();
      ObjectNode atRest = dp.putObject("atRest");
      ObjectNode mask = atRest.putObject("mask");
      mask.put("from", "RIGHT");
      ObjectNode with = mask.putObject("with");
      with.put("occurrence", 4);
      propNode.set("$gsm:dataProtection", dp);
      ObjectNode archetypeSchema = schemaWithProperty("pin", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("pin", "12");

      assertDoesNotThrow(
          () -> svc.enforceAnnotations(statement, archetype, UUID.randomUUID(), existingFinder));

      String masked = statement.get("pin").asText();
      assertEquals("**", masked);
    }

    @Test
    void suppressionAtRest_removesField() {
      ObjectNode propNode = prop("string");
      ObjectNode dp = MAPPER.createObjectNode();
      ObjectNode atRest = dp.putObject("atRest");
      atRest.put("suppression", true);
      propNode.set("$gsm:dataProtection", dp);
      ObjectNode archetypeSchema = schemaWithProperty("secret", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("secret", "top-secret-value");

      assertDoesNotThrow(
          () -> svc.enforceAnnotations(statement, archetype, UUID.randomUUID(), existingFinder));

      assertTrue(
          !statement.has("secret") || statement.get("secret").isNull(),
          "Expected field to be removed or null");
    }

    @Test
    void encryptionAtRest_silentlyIgnored() {
      ObjectNode propNode = prop("string");
      ObjectNode dp = MAPPER.createObjectNode();
      ObjectNode atRest = dp.putObject("atRest");
      atRest.putObject("encryption").put("algorithm", "AES-256-GCM");
      propNode.set("$gsm:dataProtection", dp);
      ObjectNode archetypeSchema = schemaWithProperty("card", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("card", "4111-1111-1111-1111");

      assertDoesNotThrow(
          () -> svc.enforceAnnotations(statement, archetype, UUID.randomUUID(), existingFinder));
    }

    @Test
    void noDataProtection_unchanged() {
      ObjectNode archetypeSchema = schemaWithProperty("name", prop("string"));

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("name", "Alice");

      assertDoesNotThrow(
          () -> svc.enforceAnnotations(statement, archetype, UUID.randomUUID(), existingFinder));
      assertEquals("Alice", statement.get("name").asText());
    }

    @Test
    void missingFieldInStatement_skipped() {
      ObjectNode propNode = prop("string");
      ObjectNode dp = MAPPER.createObjectNode();
      ObjectNode atRest = dp.putObject("atRest");
      atRest.putObject("hash").put("algorithm", "SHA-256");
      propNode.set("$gsm:dataProtection", dp);
      ObjectNode archetypeSchema = schemaWithProperty("ssn", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode();

      assertDoesNotThrow(
          () -> svc.enforceAnnotations(statement, archetype, UUID.randomUUID(), existingFinder));
      assertTrue(!statement.has("ssn"));
    }
  }

  // ========================================================================
  // enforceAnnotations — early-return paths
  // ========================================================================

  @Nested
  class EnforceAnnotationsEarlyReturns {

    @Test
    void nullArchetypeStatement_returnsSilently() {
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(null);

      DefinitionEntity def = mock(DefinitionEntity.class);
      when(def.getId()).thenReturn(UUID.randomUUID());
      when(archetype.getDefinition()).thenReturn(def);

      ObjectNode statement = MAPPER.createObjectNode().put("x", "val");

      assertDoesNotThrow(
          () -> svc.enforceAnnotations(statement, archetype, UUID.randomUUID(), existingFinder));
    }

    @Test
    void nullProperties_returnsSilently() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("x", "val");

      assertDoesNotThrow(
          () -> svc.enforceAnnotations(statement, archetype, UUID.randomUUID(), existingFinder));
    }

    @Test
    void propertiesIsNotObject_returnsSilently() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      schema.put("properties", "not-an-object");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("x", "val");

      assertDoesNotThrow(
          () -> svc.enforceAnnotations(statement, archetype, UUID.randomUUID(), existingFinder));
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
