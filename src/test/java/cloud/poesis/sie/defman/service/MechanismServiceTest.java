package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.MechanismRepository;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.EnumSet;
import java.util.List;
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
class MechanismServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private MechanismRepository mechanismRepo;

  @Mock private StructureService structureService;

  @Mock private DefinitionService definitionService;

  @Mock private AscriptionStateMachineService stateMachine;

  @Mock private MechanismRuleValidationService ruleValidation;

  @Mock private MechanismPortDerivationService portDerivation;

  @Mock private EntityManager entityManager;

  private MechanismService service;

  @BeforeEach
  void setUp() {
    service = new MechanismService(mechanismRepo, structureService, ruleValidation, portDerivation);
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private MechanismEntity stubMechanism(String function, UUID structureDefId, UUID defId) {
    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(defId);

    DefinitionEntity structureDef = mock(DefinitionEntity.class);
    when(structureDef.getId()).thenReturn(structureDefId);

    StructureEntity structure = mock(StructureEntity.class);
    when(structure.getDefinition()).thenReturn(structureDef);

    ObjectNode stmt = MAPPER.createObjectNode();
    stmt.put("function", function);
    stmt.put("rule", "sys.receive(\"X\")\nsys.effect(\"Y\", {})");

    MechanismEntity entity = mock(MechanismEntity.class);
    when(entity.getId()).thenReturn(UUID.randomUUID());
    when(entity.getDefinition()).thenReturn(def);
    when(entity.getStatement()).thenReturn(stmt);
    when(entity.getStructure()).thenReturn(structure);

    return entity;
  }

  // ========================================================================
  // Activation
  // ========================================================================

  @Nested
  class Activation {

    @Nested
    class FunctionUniqueness {

      @Test
      void uniqueFunction_valid() {
        UUID structureDefId = UUID.randomUUID();
        UUID thisDefId = UUID.randomUUID();
        MechanismEntity entity = stubMechanism("UserValidation", structureDefId, thisDefId);

        when(mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
                structureDefId,
                EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
            .thenReturn(List.of());

        assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
      }

      @Test
      void duplicateFunction_differentDefinition_rejected() {
        UUID structureDefId = UUID.randomUUID();
        UUID thisDefId = UUID.randomUUID();
        UUID otherDefId = UUID.randomUUID();

        MechanismEntity entity = stubMechanism("UserValidation", structureDefId, thisDefId);
        MechanismEntity existing = stubMechanism("UserValidation", structureDefId, otherDefId);

        when(mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
                structureDefId,
                EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
            .thenReturn(List.of(existing));

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class, () -> service.validateActivationUniqueness(entity));
        assertEquals(
            AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
            ex.getRuleType());
        assertTrue(ex.getMessage().contains("UserValidation"));
        assertTrue(ex.getMessage().contains("already in"));
      }

      @Test
      void sameFunction_sameDefinition_valid() {
        UUID structureDefId = UUID.randomUUID();
        UUID defId = UUID.randomUUID();

        MechanismEntity entity = stubMechanism("UserValidation", structureDefId, defId);
        MechanismEntity existing = stubMechanism("UserValidation", structureDefId, defId);

        when(mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
                structureDefId,
                EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
            .thenReturn(List.of(existing));

        assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
      }

      @Test
      void differentFunction_valid() {
        UUID structureDefId = UUID.randomUUID();
        UUID thisDefId = UUID.randomUUID();
        UUID otherDefId = UUID.randomUUID();

        MechanismEntity entity = stubMechanism("UserValidation", structureDefId, thisDefId);
        MechanismEntity existing = stubMechanism("PaymentRouting", structureDefId, otherDefId);

        when(mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
                structureDefId,
                EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
            .thenReturn(List.of(existing));

        assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
      }

      @Test
      void differentStructure_sameFunctionAllowed() {
        UUID structureDefId1 = UUID.randomUUID();
        UUID thisDefId = UUID.randomUUID();

        MechanismEntity entity = stubMechanism("UserValidation", structureDefId1, thisDefId);

        when(mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
                structureDefId1,
                EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
            .thenReturn(List.of());

        assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
      }
    }

    @Nested
    class LifecycleDescriptors {

      @Test
      void identityBound_structureAndFunction() {
        UUID structureDefId = UUID.randomUUID();
        UUID defId = UUID.randomUUID();
        MechanismEntity entity = stubMechanism("UserValidation", structureDefId, defId);

        var values = service.getIdentityBoundValues(entity);

        assertEquals(structureDefId, values.get("structure"));
        assertEquals("UserValidation", values.get("function"));
      }

      @Test
      void refereeReferences_structure() {
        UUID structureDefId = UUID.randomUUID();
        UUID defId = UUID.randomUUID();
        MechanismEntity entity = stubMechanism("UserValidation", structureDefId, defId);

        var refs = service.getRefereeReferences(entity);

        assertEquals(1, refs.size());
        assertEquals("structure", refs.get(0).getValue());
      }

      @Test
      void cascadeRoles_governingFromStructure() {
        var roles = service.getCascadeTargetRoles();

        assertEquals(1, roles.size());
        assertTrue(roles.containsKey(DefinitionSubjectType.STRUCTURE));
        assertEquals(
            AscriptionStatusTransitionCascadeType.GOVERNING,
            roles.get(DefinitionSubjectType.STRUCTURE));
      }
    }
  }

  // ========================================================================
  // BuildEntity
  // ========================================================================

  @Nested
  class BuildEntity {

    @Test
    void validStatement_returnsEntity() {
      UUID structDefId = UUID.randomUUID();
      StructureEntity structure = mock(StructureEntity.class);
      when(structureService.findEntityById(structDefId)).thenReturn(structure);

      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);

      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("structure", structDefId.toString());
      stmt.put("function", "UserValidation");
      stmt.put("rule", "sys.receive(\"Input\")\nsys.effect(\"Output\", {})");

      MechanismEntity result = service.buildEntity(def, archetype, stmt);
      assertNotNull(result);
      assertEquals(def, result.getDefinition());
      assertEquals(structure, result.getStructure());
    }

    @Test
    void structureNotFound_rejected() {
      UUID structDefId = UUID.randomUUID();
      when(structureService.findEntityById(structDefId))
          .thenThrow(new ResourceNotFoundException(PrimitiveType.STRUCTURE, structDefId));

      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("structure", structDefId.toString());
      stmt.put("function", "X");
      stmt.put("rule", "sys.receive(\"A\")\nsys.effect(\"B\", {})");

      assertThrows(
          ResourceNotFoundException.class, () -> service.buildEntity(def, archetype, stmt));
    }

    @Test
    void invalidRule_rejected() {
      UUID structDefId = UUID.randomUUID();
      StructureEntity structure = mock(StructureEntity.class);
      when(structureService.findEntityById(structDefId)).thenReturn(structure);

      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("structure", structDefId.toString());
      stmt.put("function", "X");
      stmt.put("rule", "bad syntax $$@!");

      when(ruleValidation.validateStarlarkRule("bad syntax $$@!"))
          .thenThrow(
              new RuleViolationException(
                  AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_PARSING, "syntax error"));

      assertThrows(RuleViolationException.class, () -> service.buildEntity(def, archetype, stmt));
    }
  }

  // ========================================================================
  // FindEntityById
  // ========================================================================

  @Nested
  class FindEntityByIdTests {

    @Test
    void found_returnsEntity() {
      UUID id = UUID.randomUUID();
      MechanismEntity entity = mock(MechanismEntity.class);
      when(mechanismRepo.findById(id)).thenReturn(Optional.of(entity));

      assertEquals(entity, service.findEntityById(id));
    }

    @Test
    void notFound_throwsResourceNotFound() {
      UUID id = UUID.randomUUID();
      when(mechanismRepo.findById(id)).thenReturn(Optional.empty());

      assertThrows(ResourceNotFoundException.class, () -> service.findEntityById(id));
    }
  }

  // ========================================================================
  // FindCascadeTargetsFrom
  // ========================================================================

  @Nested
  class FindCascadeTargetsFromTests {

    @Test
    void structureSource_delegatesToRepo() {
      UUID sourceId = UUID.randomUUID();
      MechanismEntity m1 = mock(MechanismEntity.class);
      when(mechanismRepo.findAllByStructureId(sourceId)).thenReturn(List.of(m1));

      var result = service.findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, sourceId);
      assertEquals(1, result.size());
      assertEquals(m1, result.get(0));
    }

    @Test
    void nonStructureSource_returnsEmpty() {
      UUID sourceId = UUID.randomUUID();
      var result = service.findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, sourceId);
      assertTrue(result.isEmpty());
    }
  }

  // ========================================================================
  // AfterCreate — delegation to MechanismPortDerivationService
  // ========================================================================

  @Nested
  class AfterCreate {

    @Test
    void delegatesToPortDerivation() {
      UUID mechDefId = UUID.randomUUID();
      DefinitionEntity mechDef = mock(DefinitionEntity.class);
      when(mechDef.getId()).thenReturn(mechDefId);

      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("rule", "sys.receive(\"InputType\")\nsys.effect(\"OutputType\", {})");

      MechanismEntity mechanism = mock(MechanismEntity.class);
      when(mechanism.getStatement()).thenReturn(stmt);
      when(mechanism.getDefinition()).thenReturn(mechDef);

      service.afterCreate(mechanism);

      verify(portDerivation).derivePortsFromRule(mechanism);
    }

    @Test
    void wrongEntityType_rejected() {
      assertThrows(
          IllegalArgumentException.class, () -> service.afterCreate(mock(StructureEntity.class)));
    }
  }

  // ========================================================================
  // GetSubjectType / GetRepository
  // ========================================================================

  @Test
  void getSubjectType_returnsMechanism() {
    assertEquals(DefinitionSubjectType.MECHANISM, service.getSubjectType());
  }

  @Test
  void getRepository_returnsMechanismRepo() {
    assertSame(mechanismRepo, service.getRepository());
  }
}
