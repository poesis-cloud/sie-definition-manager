package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.EffectorEntity;
import cloud.poesis.sie.defman.entity.InteractionEntity;
import cloud.poesis.sie.defman.entity.ReceptorEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.repository.InteractionRepository;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.RuleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Tests Interaction-specific validation rules: - Effector/Receptor archetype compatibility (GSM
 * Interaction validation rules) - Referee references and identity-bound values
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InteractionServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private EffectorService effectorService;

  @Mock private ReceptorService receptorService;

  @Mock private InteractionRepository interactionRepo;

  private InteractionService service;

  @BeforeEach
  void setUp() {
    service =
        new InteractionService(
            interactionRepo,
            effectorService,
            receptorService,
            mock(DefinitionService.class),
            mock(AscriptionStatusTransitionService.class),
            mock(AscriptionRepository.class),
            mock(EntityManager.class),
            mock(DataProtectionService.class));
  }

  // ========================================================================
  // Archetype compatibility (GSM Interaction validation rules)
  // ========================================================================

  @Nested
  class ArchetypeCompatibility {

    @Test
    void matchingArchetypes_valid() {
      UUID archetypeDefId = UUID.randomUUID();
      UUID effectorId = UUID.randomUUID();
      UUID receptorId = UUID.randomUUID();

      EffectorEntity effector = stubEffectorWithArchetypeDefId(effectorId, archetypeDefId);
      ReceptorEntity receptor = stubReceptorWithArchetypeDefId(receptorId, archetypeDefId);

      when(effectorService.findEntityById(effectorId)).thenReturn(effector);
      when(receptorService.findEntityById(receptorId)).thenReturn(receptor);

      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("effector", effectorId.toString());
      statement.put("receptor", receptorId.toString());

      DefinitionEntity definition = mock(DefinitionEntity.class);
      ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);

      assertNotNull(service.buildEntity(definition, archetypeRef, statement));
    }

    @Test
    void mismatchedArchetypes_rejected() {
      UUID effArchetypeDefId = UUID.randomUUID();
      UUID recArchetypeDefId = UUID.randomUUID();
      UUID effectorId = UUID.randomUUID();
      UUID receptorId = UUID.randomUUID();

      EffectorEntity effector = stubEffectorWithArchetypeDefId(effectorId, effArchetypeDefId);
      ReceptorEntity receptor = stubReceptorWithArchetypeDefId(receptorId, recArchetypeDefId);

      when(effectorService.findEntityById(effectorId)).thenReturn(effector);
      when(receptorService.findEntityById(receptorId)).thenReturn(receptor);

      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("effector", effectorId.toString());
      statement.put("receptor", receptorId.toString());

      DefinitionEntity definition = mock(DefinitionEntity.class);
      ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.buildEntity(definition, archetypeRef, statement));
      assertEquals(RuleType.INTERACTION_EFFECTOR_RECEPTOR_COMPATIBILITY, ex.getRuleType());
      assertTrue(ex.getMessage().contains("archetype mismatch"));
    }
  }

  // ========================================================================
  // Identity-bound values
  // ========================================================================

  @Nested
  class IdentityBoundValues {

    @Test
    void effectorAndReceptorExtracted() {
      UUID effDefId = UUID.randomUUID();
      UUID recDefId = UUID.randomUUID();

      EffectorEntity effector = mock(EffectorEntity.class);
      DefinitionEntity effDef = mock(DefinitionEntity.class);
      when(effDef.getId()).thenReturn(effDefId);
      when(effector.getDefinition()).thenReturn(effDef);

      ReceptorEntity receptor = mock(ReceptorEntity.class);
      DefinitionEntity recDef = mock(DefinitionEntity.class);
      when(recDef.getId()).thenReturn(recDefId);
      when(receptor.getDefinition()).thenReturn(recDef);

      // Create an InteractionEntity mock directly
      var entity = mock(cloud.poesis.sie.defman.entity.InteractionEntity.class);
      when(entity.getEffector()).thenReturn(effector);
      when(entity.getReceptor()).thenReturn(receptor);

      var values = service.getIdentityBoundValues(entity);

      assertTrue(values.containsKey("effector"));
      assertTrue(values.containsKey("receptor"));
      assertTrue(values.get("effector").equals(effDefId));
      assertTrue(values.get("receptor").equals(recDefId));
    }
  }

  // ========================================================================
  // Referee references
  // ========================================================================

  @Nested
  class RefereeReferences {

    @Test
    void referencesEffectorAndReceptor() {
      EffectorEntity effector = mock(EffectorEntity.class);
      ReceptorEntity receptor = mock(ReceptorEntity.class);

      var entity = mock(cloud.poesis.sie.defman.entity.InteractionEntity.class);
      when(entity.getEffector()).thenReturn(effector);
      when(entity.getReceptor()).thenReturn(receptor);

      var refs = service.getRefereeReferences(entity);

      assertTrue(refs.size() == 2);
      assertTrue(refs.stream().anyMatch(r -> r.label().equals("effector")));
      assertTrue(refs.stream().anyMatch(r -> r.label().equals("receptor")));
    }
  }

  // ========================================================================
  // Statement Compliance
  // ========================================================================

  @Nested
  class StatementCompliance {

    @Test
    void missingRequiredField_rejected() {
      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode emptyStatement = MAPPER.createObjectNode();

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.buildEntity(def, archetype, emptyStatement));
      assertEquals(RuleType.INTERACTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
      assertTrue(ex.getMessage().contains("effector"));
    }

    @Test
    void extensionSchemaViolation_rejected() {
      // Schema with a required tenant-extension property (not GSM-base:
      // effector/receptor)
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "ExtInt");
      schema.put("type", "object");
      ObjectNode props = schema.putObject("properties");
      props.set("customField", MAPPER.createObjectNode().put("type", "string"));
      schema.putArray("required").add("customField");

      DefinitionEntity archDef = mock(DefinitionEntity.class);
      when(archDef.getId()).thenReturn(UUID.randomUUID());
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(schema);
      when(archetype.getDefinition()).thenReturn(archDef);

      ObjectNode statement = MAPPER.createObjectNode(); // missing customField

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateStatement(statement, archetype));
      assertEquals(
          RuleType.INTERACTION_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE, ex.getRuleType());
      assertTrue(ex.getMessage().contains("tenant-extended"));
    }
  }

  // ========================================================================
  // findCascadeTargetsFrom
  // ========================================================================

  @Nested
  class FindCascadeTargetsFrom {

    @Test
    void effectorType_delegatesToRepo() {
      UUID id = UUID.randomUUID();
      InteractionEntity entity = mock(InteractionEntity.class);
      when(interactionRepo.findAllByEffectorId(id)).thenReturn(List.of(entity));

      var result = service.findCascadeTargetsFrom(DefinitionSubjectType.EFFECTOR, id);

      assertEquals(1, result.size());
    }

    @Test
    void receptorType_delegatesToRepo() {
      UUID id = UUID.randomUUID();
      InteractionEntity entity = mock(InteractionEntity.class);
      when(interactionRepo.findAllByReceptorId(id)).thenReturn(List.of(entity));

      var result = service.findCascadeTargetsFrom(DefinitionSubjectType.RECEPTOR, id);

      assertEquals(1, result.size());
    }

    @Test
    void otherType_returnsEmpty() {
      var result =
          service.findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, UUID.randomUUID());

      assertTrue(result.isEmpty());
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private EffectorEntity stubEffectorWithArchetypeDefId(UUID effectorId, UUID archetypeDefId) {
    DefinitionEntity effArchDef = mock(DefinitionEntity.class);
    when(effArchDef.getId()).thenReturn(archetypeDefId);

    ArchetypeEntity effArchetype = mock(ArchetypeEntity.class);
    when(effArchetype.getDefinition()).thenReturn(effArchDef);

    EffectorEntity effector = mock(EffectorEntity.class);
    when(effector.getId()).thenReturn(effectorId);
    when(effector.getOutputArchetype()).thenReturn(effArchetype);

    return effector;
  }

  private ReceptorEntity stubReceptorWithArchetypeDefId(UUID receptorId, UUID archetypeDefId) {
    DefinitionEntity recArchDef = mock(DefinitionEntity.class);
    when(recArchDef.getId()).thenReturn(archetypeDefId);

    ArchetypeEntity recArchetype = mock(ArchetypeEntity.class);
    when(recArchetype.getDefinition()).thenReturn(recArchDef);

    ReceptorEntity receptor = mock(ReceptorEntity.class);
    when(receptor.getId()).thenReturn(receptorId);
    when(receptor.getInputArchetype()).thenReturn(recArchetype);

    return receptor;
  }
}
