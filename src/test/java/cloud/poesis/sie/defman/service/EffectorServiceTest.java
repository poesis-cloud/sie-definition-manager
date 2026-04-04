package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.EffectorEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.repository.EffectorRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
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

/**
 * Tests Effector lifecycle descriptors: identity-bound values, referee references, cascade target
 * roles, buildEntity, findEntityById, and findCascadeTargetsFrom.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EffectorServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private EffectorRepository effectorRepo;

  @Mock private MechanismService mechanismService;

  @Mock private ArchetypeService archetypeService;

  private EffectorService service;

  @BeforeEach
  void setUp() {
    service =
        new EffectorService(
            effectorRepo,
            mechanismService,
            archetypeService,
            mock(DefinitionService.class),
            mock(AscriptionStateMachineService.class),
            mock(AscriptionStatementValidationService.class),
            mock(EntityManager.class));
  }

  // ========================================================================
  // Identity-bound values
  // ========================================================================

  @Nested
  class IdentityBound {

    @Test
    void mechanismAndArchetypeExtracted() {
      UUID mechDefId = UUID.randomUUID();
      UUID archDefId = UUID.randomUUID();

      EffectorEntity entity = stubEffector(mechDefId, archDefId);

      Map<String, Object> values = service.getIdentityBoundValues(entity);

      assertEquals(mechDefId, values.get("mechanism"));
      assertEquals(archDefId, values.get("archetype"));
    }
  }

  // ========================================================================
  // Referee references
  // ========================================================================

  @Nested
  class RefereeReferences {

    @Test
    void referencesArchetypeOnly() {
      UUID mechDefId = UUID.randomUUID();
      UUID archDefId = UUID.randomUUID();

      EffectorEntity entity = stubEffector(mechDefId, archDefId);

      var refs = service.getRefereeReferences(entity);

      assertEquals(1, refs.size());
      assertEquals("archetype", refs.get(0).getValue());
    }
  }

  // ========================================================================
  // Cascade target roles
  // ========================================================================

  @Nested
  class CascadeRoles {

    @Test
    void constitutiveFromMechanism() {
      var roles = service.getCascadeTargetRoles();

      assertEquals(1, roles.size());
      assertTrue(roles.containsKey(DefinitionSubjectType.MECHANISM));
      assertEquals(
          AscriptionStatusTransitionCascadeType.CONSTITUTIVE,
          roles.get(DefinitionSubjectType.MECHANISM));
    }
  }

  // ========================================================================
  // buildEntity — happy path
  // ========================================================================

  @Nested
  class BuildEntity {

    @Test
    void validStatement_buildsEntity() {
      UUID mechId = UUID.randomUUID();
      UUID dataArchId = UUID.randomUUID();

      MechanismEntity mechanism = mock(MechanismEntity.class);
      when(mechanismService.findEntityById(mechId)).thenReturn(mechanism);

      ArchetypeEntity dataArchetype = mock(ArchetypeEntity.class);
      when(archetypeService.findEntityById(dataArchId)).thenReturn(dataArchetype);

      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("mechanism", mechId.toString());
      statement.put("archetype", dataArchId.toString());

      DefinitionEntity definition = mock(DefinitionEntity.class);
      ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);

      EffectorEntity result = service.buildEntity(definition, archetypeRef, statement);

      assertNotNull(result);
    }
  }

  // ========================================================================
  // findEntityById
  // ========================================================================

  @Nested
  class FindEntityById {

    @Test
    void found_returnsEntity() {
      UUID id = UUID.randomUUID();
      EffectorEntity entity = mock(EffectorEntity.class);
      when(effectorRepo.findById(id)).thenReturn(Optional.of(entity));

      assertSame(entity, service.findEntityById(id));
    }

    @Test
    void notFound_throws() {
      UUID id = UUID.randomUUID();
      when(effectorRepo.findById(id)).thenReturn(Optional.empty());

      assertThrows(ResourceNotFoundException.class, () -> service.findEntityById(id));
    }
  }

  // ========================================================================
  // findCascadeTargetsFrom
  // ========================================================================

  @Nested
  class FindCascadeTargetsFrom {

    @Test
    void mechanismType_delegatesToRepo() {
      UUID sourceId = UUID.randomUUID();
      EffectorEntity e = mock(EffectorEntity.class);
      when(effectorRepo.findAllByMechanismId(sourceId)).thenReturn(List.of(e));

      var result = service.findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, sourceId);

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
  // getSubjectType and getRepository
  // ========================================================================

  @Nested
  class MetadataAccessors {

    @Test
    void subjectType_isEffector() {
      assertEquals(DefinitionSubjectType.EFFECTOR, service.getSubjectType());
    }
  }

  // ========================================================================
  // findAllByMechanismDefinitionId
  // ========================================================================

  @Nested
  class FindAllByMechanismDefinitionId {

    @Test
    void delegatesToRepo() {
      UUID mechDefId = UUID.randomUUID();
      EffectorEntity e1 = mock(EffectorEntity.class);
      when(effectorRepo.findAllByMechanismDefinitionId(mechDefId)).thenReturn(List.of(e1));

      var result = service.findAllByMechanismDefinitionId(mechDefId);
      assertEquals(1, result.size());
      assertSame(e1, result.get(0));
    }

    @Test
    void noResults_returnsEmpty() {
      UUID mechDefId = UUID.randomUUID();
      when(effectorRepo.findAllByMechanismDefinitionId(mechDefId)).thenReturn(List.of());

      var result = service.findAllByMechanismDefinitionId(mechDefId);
      assertTrue(result.isEmpty());
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private EffectorEntity stubEffector(UUID mechDefId, UUID archDefId) {
    DefinitionEntity mechDef = mock(DefinitionEntity.class);
    when(mechDef.getId()).thenReturn(mechDefId);
    MechanismEntity mechanism = mock(MechanismEntity.class);
    when(mechanism.getDefinition()).thenReturn(mechDef);

    DefinitionEntity archDef = mock(DefinitionEntity.class);
    when(archDef.getId()).thenReturn(archDefId);
    ArchetypeEntity archetype = mock(ArchetypeEntity.class);
    when(archetype.getDefinition()).thenReturn(archDef);

    EffectorEntity entity = mock(EffectorEntity.class);
    when(entity.getMechanism()).thenReturn(mechanism);
    when(entity.getOutputArchetype()).thenReturn(archetype);

    return entity;
  }
}
