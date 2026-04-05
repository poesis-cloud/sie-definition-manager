package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AscriptionUniquenessValidationServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private AscriptionRepository ascriptionRepository;

  private AscriptionUniquenessValidationService service;

  @BeforeEach
  void setUp() {
    service = new AscriptionUniquenessValidationService(ascriptionRepository);
  }

  // ========================================================================
  // Annotation-driven uniqueness ($gsm:unique) — validate()
  // ========================================================================

  @Nested
  class AnnotationDriven {

    @Test
    void duplicate_throws() {
      UUID defId = UUID.randomUUID();
      UUID archetypeId = UUID.randomUUID();
      UUID conflictingDefId = UUID.randomUUID();
      UUID conflictingAscId = UUID.randomUUID();

      ObjectNode archetypeSchema = MAPPER.createObjectNode();
      ObjectNode props = archetypeSchema.putObject("properties");
      props.putObject("code").put("type", "string").put("$gsm:unique", true);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(archetypeSchema);
      when(archetype.getId()).thenReturn(archetypeId);

      ObjectNode statement = MAPPER.createObjectNode().put("code", "DUPLICATE");

      AscriptionEntity inEffect = mock(AscriptionEntity.class);
      when(inEffect.getId()).thenReturn(conflictingAscId);
      DefinitionEntity conflictingDef = mock(DefinitionEntity.class);
      when(conflictingDef.getId()).thenReturn(conflictingDefId);
      when(inEffect.getDefinition()).thenReturn(conflictingDef);
      ObjectNode inEffectStmt = MAPPER.createObjectNode().put("code", "DUPLICATE");
      when(inEffect.getStatement()).thenReturn(inEffectStmt);

      when(ascriptionRepository.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
              eq(archetypeId), any(), eq(defId)))
          .thenReturn(List.of(inEffect));

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validate(statement, archetype, defId));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("DUPLICATE"));
    }

    @Test
    void unique_passes() {
      UUID defId = UUID.randomUUID();
      UUID archetypeId = UUID.randomUUID();

      ObjectNode archetypeSchema = MAPPER.createObjectNode();
      ObjectNode props = archetypeSchema.putObject("properties");
      props.putObject("code").put("type", "string").put("$gsm:unique", true);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(archetypeSchema);
      when(archetype.getId()).thenReturn(archetypeId);

      ObjectNode statement = MAPPER.createObjectNode().put("code", "UNIQUE");

      when(ascriptionRepository.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
              eq(archetypeId), any(), eq(defId)))
          .thenReturn(List.of());

      assertDoesNotThrow(() -> service.validate(statement, archetype, defId));
    }

    @Test
    void noAnnotation_skips() {
      UUID defId = UUID.randomUUID();

      ObjectNode archetypeSchema = MAPPER.createObjectNode();
      archetypeSchema.putObject("properties").putObject("code").put("type", "string");

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(archetypeSchema);

      ObjectNode statement = MAPPER.createObjectNode().put("code", "val");

      assertDoesNotThrow(() -> service.validate(statement, archetype, defId));

      Mockito.verify(ascriptionRepository, never())
          .findAllByArchetypeIdAndStatusInAndDefinitionIdNot(any(), any(), any());
    }

    @Test
    void nullArchetypeStatement_skips() {
      UUID defId = UUID.randomUUID();

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(null);

      ObjectNode statement = MAPPER.createObjectNode();

      assertDoesNotThrow(() -> service.validate(statement, archetype, defId));
    }

    @Test
    void propertyNotInStatement_skips() {
      UUID defId = UUID.randomUUID();

      ObjectNode archetypeSchema = MAPPER.createObjectNode();
      ObjectNode props = archetypeSchema.putObject("properties");
      props.putObject("missing").put("type", "string").put("$gsm:unique", true);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(archetypeSchema);

      ObjectNode statement = MAPPER.createObjectNode(); // no "missing" field

      assertDoesNotThrow(() -> service.validate(statement, archetype, defId));
    }
  }

  // ========================================================================
  // validatePropertyAcrossDefinitions (static)
  // ========================================================================

  @Nested
  class PropertyAcrossDefinitions {

    @Test
    void unique_passes() {
      UUID thisDefId = UUID.randomUUID();

      AscriptionEntity sibling = mock(AscriptionEntity.class);
      DefinitionEntity sibDef = mock(DefinitionEntity.class);
      when(sibDef.getId()).thenReturn(UUID.randomUUID());
      when(sibling.getDefinition()).thenReturn(sibDef);
      ObjectNode sibStmt = MAPPER.createObjectNode();
      sibStmt.put("purpose", "other-purpose");
      when(sibling.getStatement()).thenReturn(sibStmt);

      assertDoesNotThrow(
          () ->
              AscriptionUniquenessValidationService.validatePropertyAcrossDefinitions(
                  DefinitionSubjectType.STRUCTURE,
                  "purpose",
                  "compliance",
                  thisDefId,
                  List.of(sibling)));
    }

    @Test
    void duplicate_throws() {
      UUID thisDefId = UUID.randomUUID();

      AscriptionEntity sibling = mock(AscriptionEntity.class);
      DefinitionEntity sibDef = mock(DefinitionEntity.class);
      when(sibDef.getId()).thenReturn(UUID.randomUUID());
      when(sibling.getDefinition()).thenReturn(sibDef);
      ObjectNode sibStmt = MAPPER.createObjectNode();
      sibStmt.put("purpose", "compliance");
      when(sibling.getStatement()).thenReturn(sibStmt);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  AscriptionUniquenessValidationService.validatePropertyAcrossDefinitions(
                      DefinitionSubjectType.STRUCTURE,
                      "purpose",
                      "compliance",
                      thisDefId,
                      List.of(sibling)));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
          ex.getRuleType());
    }

    @Test
    void sameDefinition_skipped() {
      UUID thisDefId = UUID.randomUUID();

      AscriptionEntity sibling = mock(AscriptionEntity.class);
      DefinitionEntity sibDef = mock(DefinitionEntity.class);
      when(sibDef.getId()).thenReturn(thisDefId);
      when(sibling.getDefinition()).thenReturn(sibDef);

      assertDoesNotThrow(
          () ->
              AscriptionUniquenessValidationService.validatePropertyAcrossDefinitions(
                  DefinitionSubjectType.STRUCTURE,
                  "purpose",
                  "compliance",
                  thisDefId,
                  List.of(sibling)));
    }
  }
}
