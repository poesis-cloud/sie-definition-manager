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
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.entity.ReceptorEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.repository.ReceptorRepository;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
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
 * Tests Receptor lifecycle descriptors: identity-bound values, referee references, cascade target
 * roles, buildEntity, findEntityById, and findCascadeTargetsFrom.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReceptorServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private ReceptorRepository receptorRepo;
  @Mock private MechanismService mechanismService;
  @Mock private ArchetypeService archetypeService;

  private ReceptorService service;

  @BeforeEach
  void setUp() {
    service =
        new ReceptorService(
            receptorRepo,
            mechanismService,
            archetypeService,
            mock(ArchetypeRepository.class),
            mock(DefinitionService.class),
            mock(AscriptionStatusTransitionService.class),
            mock(AscriptionService.class),
            mock(EntityManager.class),
            mock(DataProtectionService.class));
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

      ReceptorEntity entity = stubReceptor(mechDefId, archDefId);

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

      ReceptorEntity entity = stubReceptor(mechDefId, archDefId);

      var refs = service.getRefereeReferences(entity);

      assertEquals(1, refs.size());
      assertEquals("archetype", refs.get(0).label());
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
  // buildEntity — happy path + error cases
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

      ReceptorEntity result = service.buildEntity(definition, archetypeRef, statement);

      assertNotNull(result);
    }

    @Test
    void missingMechanism_rejected() {
      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode emptyStatement = MAPPER.createObjectNode();

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.buildEntity(def, archetype, emptyStatement));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("mechanism"));
    }

    @Test
    void missingArchetype_rejected() {
      UUID mechId = UUID.randomUUID();
      MechanismEntity mechanism = mock(MechanismEntity.class);
      when(mechanismService.findEntityById(mechId)).thenReturn(mechanism);

      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("mechanism", mechId.toString());

      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.buildEntity(def, archetypeRef, statement));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("archetype"));
    }

    @Test
    void invalidUuid_rejected() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("mechanism", "not-a-uuid");

      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.buildEntity(def, archetypeRef, statement));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ex.getRuleType());
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
      ReceptorEntity entity = mock(ReceptorEntity.class);
      when(receptorRepo.findById(id)).thenReturn(Optional.of(entity));

      assertSame(entity, service.findEntityById(id));
    }

    @Test
    void notFound_throws() {
      UUID id = UUID.randomUUID();
      when(receptorRepo.findById(id)).thenReturn(Optional.empty());

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
      ReceptorEntity r = mock(ReceptorEntity.class);
      when(receptorRepo.findAllByMechanismId(sourceId)).thenReturn(List.of(r));

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
  // getSubjectType
  // ========================================================================

  @Nested
  class MetadataAccessors {

    @Test
    void subjectType_isReceptor() {
      assertEquals(DefinitionSubjectType.RECEPTOR, service.getSubjectType());
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private ReceptorEntity stubReceptor(UUID mechDefId, UUID archDefId) {
    DefinitionEntity mechDef = mock(DefinitionEntity.class);
    when(mechDef.getId()).thenReturn(mechDefId);
    MechanismEntity mechanism = mock(MechanismEntity.class);
    when(mechanism.getDefinition()).thenReturn(mechDef);

    DefinitionEntity archDef = mock(DefinitionEntity.class);
    when(archDef.getId()).thenReturn(archDefId);
    ArchetypeEntity archetype = mock(ArchetypeEntity.class);
    when(archetype.getDefinition()).thenReturn(archDef);

    ReceptorEntity entity = mock(ReceptorEntity.class);
    when(entity.getMechanism()).thenReturn(mechanism);
    when(entity.getInputArchetype()).thenReturn(archetype);

    return entity;
  }
}
