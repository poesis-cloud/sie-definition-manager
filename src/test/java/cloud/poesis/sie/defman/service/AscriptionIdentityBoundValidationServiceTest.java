package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AscriptionIdentityBoundValidationServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @SuppressWarnings("unchecked")
  private final AscriptionSubtypeService<AscriptionEntity> handler =
      mock(AscriptionSubtypeService.class);

  private AscriptionIdentityBoundValidationService service;

  @BeforeEach
  void setUp() {
    service = new AscriptionIdentityBoundValidationService();
  }

  // ========================================================================
  // Handler-declared identity-bound fields
  // ========================================================================

  @Nested
  class HandlerDeclared {

    @Test
    void noIdentityBoundFields_passes() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(handler.getIdentityBoundValues(any())).thenReturn(Map.of());

      assertDoesNotThrow(() -> service.validate(handler, entity, archetype));
    }

    @Test
    void noExistingAscriptions_passes() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(handler.getIdentityBoundValues(any())).thenReturn(Map.of("purpose", "compliance"));
      when(handler.findAllByDefinitionId(defId)).thenReturn(List.of());

      assertDoesNotThrow(() -> service.validate(handler, entity, archetype));
    }

    @Test
    void sameValues_passes() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);

      AscriptionEntity existing = mock(AscriptionEntity.class);
      when(handler.getIdentityBoundValues(any())).thenReturn(Map.of("purpose", "compliance"));
      when(handler.findAllByDefinitionId(defId)).thenReturn(List.of(existing));

      assertDoesNotThrow(() -> service.validate(handler, entity, archetype));
    }

    @Test
    void changedValue_throws() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(handler.getIdentityBoundValues(entity)).thenReturn(Map.of("purpose", "new-val"));

      AscriptionEntity existing = mock(AscriptionEntity.class);
      when(handler.getIdentityBoundValues(existing)).thenReturn(Map.of("purpose", "old-val"));
      when(handler.findAllByDefinitionId(defId)).thenReturn(List.of(existing));

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validate(handler, entity, archetype));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("purpose"));
    }
  }

  // ========================================================================
  // Annotation-declared identity-bound fields ($gsm:identityBound)
  // ========================================================================

  @Nested
  class AnnotationDeclared {

    @Test
    void changed_annotatedProperty_throws() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);

      ObjectNode archetypeSchema = MAPPER.createObjectNode();
      ObjectNode props = archetypeSchema.putObject("properties");
      props.putObject("tenantField").put("type", "string").put("$gsm:identityBound", true);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(archetypeSchema);

      ObjectNode statement = MAPPER.createObjectNode().put("tenantField", "newValue");
      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(entity.getStatement()).thenReturn(statement);
      when(handler.getIdentityBoundValues(any())).thenReturn(Map.of());

      AscriptionEntity existing = mock(AscriptionEntity.class);
      ObjectNode existingStmt = MAPPER.createObjectNode().put("tenantField", "oldValue");
      when(existing.getStatement()).thenReturn(existingStmt);
      when(handler.findAllByDefinitionId(defId)).thenReturn(List.of(existing));

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validate(handler, entity, archetype));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("tenantField"));
    }

    @Test
    void same_annotatedProperty_passes() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);

      ObjectNode archetypeSchema = MAPPER.createObjectNode();
      ObjectNode props = archetypeSchema.putObject("properties");
      props.putObject("tenantField").put("type", "string").put("$gsm:identityBound", true);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(archetypeSchema);

      ObjectNode statement = MAPPER.createObjectNode().put("tenantField", "sameValue");
      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(entity.getStatement()).thenReturn(statement);
      when(handler.getIdentityBoundValues(any())).thenReturn(Map.of());

      AscriptionEntity existing = mock(AscriptionEntity.class);
      ObjectNode existingStmt = MAPPER.createObjectNode().put("tenantField", "sameValue");
      when(existing.getStatement()).thenReturn(existingStmt);
      when(handler.findAllByDefinitionId(defId)).thenReturn(List.of(existing));

      assertDoesNotThrow(() -> service.validate(handler, entity, archetype));
    }

    @Test
    void noExistingAscriptions_passes() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);

      ObjectNode archetypeSchema = MAPPER.createObjectNode();
      ObjectNode props = archetypeSchema.putObject("properties");
      props.putObject("tenantField").put("type", "string").put("$gsm:identityBound", true);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(archetypeSchema);

      ObjectNode statement = MAPPER.createObjectNode().put("tenantField", "value");
      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(entity.getStatement()).thenReturn(statement);
      when(handler.getIdentityBoundValues(any())).thenReturn(Map.of());
      when(handler.findAllByDefinitionId(defId)).thenReturn(List.of());

      assertDoesNotThrow(() -> service.validate(handler, entity, archetype));
    }

    @Test
    void propertyNotInStatement_skips() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);

      ObjectNode archetypeSchema = MAPPER.createObjectNode();
      ObjectNode props = archetypeSchema.putObject("properties");
      props.putObject("missing").put("type", "string").put("$gsm:identityBound", true);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(archetypeSchema);

      ObjectNode statement = MAPPER.createObjectNode(); // no "missing" field
      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(entity.getStatement()).thenReturn(statement);
      when(handler.getIdentityBoundValues(any())).thenReturn(Map.of());

      assertDoesNotThrow(() -> service.validate(handler, entity, archetype));
    }

    @Test
    void existingLacksProperty_skips() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);

      ObjectNode archetypeSchema = MAPPER.createObjectNode();
      ObjectNode props = archetypeSchema.putObject("properties");
      props.putObject("tenantField").put("type", "string").put("$gsm:identityBound", true);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(archetypeSchema);

      ObjectNode statement = MAPPER.createObjectNode().put("tenantField", "val");
      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(entity.getStatement()).thenReturn(statement);
      when(handler.getIdentityBoundValues(any())).thenReturn(Map.of());

      AscriptionEntity existing = mock(AscriptionEntity.class);
      when(existing.getStatement()).thenReturn(MAPPER.createObjectNode());
      when(handler.findAllByDefinitionId(defId)).thenReturn(List.of(existing));

      assertDoesNotThrow(() -> service.validate(handler, entity, archetype));
    }

    @Test
    void nullArchetypeStatement_skips() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(null);

      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(handler.getIdentityBoundValues(any())).thenReturn(Map.of());

      assertDoesNotThrow(() -> service.validate(handler, entity, archetype));
    }
  }
}
