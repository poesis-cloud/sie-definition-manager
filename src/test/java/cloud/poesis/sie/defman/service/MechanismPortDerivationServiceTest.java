package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.EffectorEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.entity.ReceptorEntity;
import cloud.poesis.sie.defman.service.MechanismRuleValidationService.PortSignature;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MechanismPortDerivationServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private MechanismRuleValidationService ruleValidation;
  @Mock private ArchetypeService archetypeService;
  @Mock private EffectorService effectorService;
  @Mock private ReceptorService receptorService;
  @Mock private DefinitionService definitionService;
  @Mock private AscriptionStateMachineService stateMachine;
  @Mock private EntityManager entityManager;

  private MechanismPortDerivationService service;

  @BeforeEach
  void setUp() {
    service =
        new MechanismPortDerivationService(
            ruleValidation,
            archetypeService,
            effectorService,
            receptorService,
            definitionService,
            stateMachine,
            entityManager,
            new ObjectMapper());
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private ArchetypeEntity mockArchetypeWithTitle(String title) {
    UUID defId = UUID.randomUUID();
    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(defId);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    when(arch.getDefinition()).thenReturn(def);
    ObjectNode schema = MAPPER.createObjectNode().put("title", title);
    when(arch.getStatement()).thenReturn(schema);
    return arch;
  }

  private MechanismEntity stubMechanism(String rule) {
    UUID mechDefId = UUID.randomUUID();
    DefinitionEntity mechDef = mock(DefinitionEntity.class);
    when(mechDef.getId()).thenReturn(mechDefId);

    ObjectNode stmt = MAPPER.createObjectNode();
    stmt.put("rule", rule);

    MechanismEntity mechanism = mock(MechanismEntity.class);
    when(mechanism.getStatement()).thenReturn(stmt);
    when(mechanism.getDefinition()).thenReturn(mechDef);
    return mechanism;
  }

  // ========================================================================
  // DerivePortsFromRule
  // ========================================================================

  @Nested
  class DerivePortsFromRule {

    @Test
    void noSignatures_skips() {
      MechanismEntity mechanism = stubMechanism("sys.receive(\"X\")");
      when(ruleValidation.collectPortSignatures(any(String.class))).thenReturn(List.of());

      service.derivePortsFromRule(mechanism);

      verify(effectorService, never()).save(any());
      verify(receptorService, never()).save(any());
    }

    @Test
    void derivesEffectorAndReceptor() {
      MechanismEntity mechanism =
          stubMechanism("sys.receive(\"InputType\")\nsys.effect(\"OutputType\", {})");
      UUID mechDefId = mechanism.getDefinition().getId();

      when(ruleValidation.collectPortSignatures(any(String.class)))
          .thenReturn(
              List.of(
                  new PortSignature("receptor", "InputType", null),
                  new PortSignature("effector", "OutputType", null)));

      ArchetypeEntity effArchetype = mockArchetypeWithTitle("EffectorArchetype");
      ArchetypeEntity recArchetype = mockArchetypeWithTitle("ReceptorArchetype");
      when(archetypeService.findInEffectBySchemaTitle("EffectorArchetype"))
          .thenReturn(effArchetype);
      when(archetypeService.findInEffectBySchemaTitle("ReceptorArchetype"))
          .thenReturn(recArchetype);

      ArchetypeEntity inputType = mockArchetypeWithTitle("InputType");
      ArchetypeEntity outputType = mockArchetypeWithTitle("OutputType");
      when(archetypeService.findInEffectBySchemaTitle("InputType")).thenReturn(inputType);
      when(archetypeService.findInEffectBySchemaTitle("OutputType")).thenReturn(outputType);

      when(effectorService.findAllByMechanismDefinitionId(mechDefId)).thenReturn(List.of());
      when(receptorService.findAllByMechanismDefinitionId(mechDefId)).thenReturn(List.of());

      DefinitionEntity effDef = mock(DefinitionEntity.class);
      DefinitionEntity recDef = mock(DefinitionEntity.class);
      when(definitionService.create(DefinitionSubjectType.EFFECTOR)).thenReturn(effDef);
      when(definitionService.create(DefinitionSubjectType.RECEPTOR)).thenReturn(recDef);

      EffectorEntity savedEff = mock(EffectorEntity.class);
      when(savedEff.getId()).thenReturn(UUID.randomUUID());
      when(savedEff.getStatement()).thenReturn(MAPPER.createObjectNode());
      when(effectorService.save(any(EffectorEntity.class))).thenReturn(savedEff);

      ReceptorEntity savedRec = mock(ReceptorEntity.class);
      when(savedRec.getId()).thenReturn(UUID.randomUUID());
      when(savedRec.getStatement()).thenReturn(MAPPER.createObjectNode());
      when(receptorService.save(any(ReceptorEntity.class))).thenReturn(savedRec);

      service.derivePortsFromRule(mechanism);

      verify(effectorService).save(any(EffectorEntity.class));
      verify(receptorService).save(any(ReceptorEntity.class));
    }

    @Test
    void baseArchetypesMissing_skips() {
      MechanismEntity mechanism = stubMechanism("sys.receive(\"X\")\nsys.effect(\"Y\", {})");

      when(ruleValidation.collectPortSignatures(any(String.class)))
          .thenReturn(
              List.of(
                  new PortSignature("receptor", "X", null),
                  new PortSignature("effector", "Y", null)));

      when(archetypeService.findInEffectBySchemaTitle("EffectorArchetype")).thenReturn(null);
      when(archetypeService.findInEffectBySchemaTitle("ReceptorArchetype")).thenReturn(null);

      assertDoesNotThrow(() -> service.derivePortsFromRule(mechanism));
      verify(effectorService, never()).save(any());
    }

    @Test
    void dataArchetypeNotFound_skipsPort() {
      MechanismEntity mechanism =
          stubMechanism("sys.receive(\"MissingType\")\nsys.effect(\"AlsoMissing\", {})");
      UUID mechDefId = mechanism.getDefinition().getId();

      when(ruleValidation.collectPortSignatures(any(String.class)))
          .thenReturn(
              List.of(
                  new PortSignature("receptor", "MissingType", null),
                  new PortSignature("effector", "AlsoMissing", null)));

      ArchetypeEntity effArchetype = mockArchetypeWithTitle("EffectorArchetype");
      ArchetypeEntity recArchetype = mockArchetypeWithTitle("ReceptorArchetype");
      when(archetypeService.findInEffectBySchemaTitle("EffectorArchetype"))
          .thenReturn(effArchetype);
      when(archetypeService.findInEffectBySchemaTitle("ReceptorArchetype"))
          .thenReturn(recArchetype);

      when(archetypeService.findInEffectBySchemaTitle("MissingType")).thenReturn(null);
      when(archetypeService.findInEffectBySchemaTitle("AlsoMissing")).thenReturn(null);

      assertDoesNotThrow(() -> service.derivePortsFromRule(mechanism));
      verify(effectorService, never()).save(any());
    }

    @Test
    void existingPortDefinition_reused() {
      MechanismEntity mechanism =
          stubMechanism("sys.receive(\"InputType\")\nsys.effect(\"OutputType\", {})");
      UUID mechDefId = mechanism.getDefinition().getId();

      when(ruleValidation.collectPortSignatures(any(String.class)))
          .thenReturn(
              List.of(
                  new PortSignature("receptor", "InputType", null),
                  new PortSignature("effector", "OutputType", null)));

      ArchetypeEntity effArchetype = mockArchetypeWithTitle("EffectorArchetype");
      ArchetypeEntity recArchetype = mockArchetypeWithTitle("ReceptorArchetype");
      when(archetypeService.findInEffectBySchemaTitle("EffectorArchetype"))
          .thenReturn(effArchetype);
      when(archetypeService.findInEffectBySchemaTitle("ReceptorArchetype"))
          .thenReturn(recArchetype);

      ArchetypeEntity inputType = mockArchetypeWithTitle("InputType");
      ArchetypeEntity outputType = mockArchetypeWithTitle("OutputType");
      when(archetypeService.findInEffectBySchemaTitle("InputType")).thenReturn(inputType);
      when(archetypeService.findInEffectBySchemaTitle("OutputType")).thenReturn(outputType);

      // Existing effector with matching data archetype → reuse definition
      UUID outputDefId = outputType.getDefinition().getId();
      DefinitionEntity existingEffDef = mock(DefinitionEntity.class);
      EffectorEntity existingEff = mock(EffectorEntity.class);
      ArchetypeEntity existingOutputArch = mock(ArchetypeEntity.class);
      DefinitionEntity existingOutputDef = mock(DefinitionEntity.class);
      when(existingOutputDef.getId()).thenReturn(outputDefId);
      when(existingOutputArch.getDefinition()).thenReturn(existingOutputDef);
      when(existingEff.getDefinition()).thenReturn(existingEffDef);
      when(existingEff.getOutputArchetype()).thenReturn(existingOutputArch);
      List<EffectorEntity> existingEffectors = List.of(existingEff);
      when(effectorService.findAllByMechanismDefinitionId(mechDefId)).thenReturn(existingEffectors);

      when(receptorService.findAllByMechanismDefinitionId(mechDefId)).thenReturn(List.of());
      DefinitionEntity recDef = mock(DefinitionEntity.class);
      when(definitionService.create(DefinitionSubjectType.RECEPTOR)).thenReturn(recDef);

      EffectorEntity savedEff = mock(EffectorEntity.class);
      when(savedEff.getId()).thenReturn(UUID.randomUUID());
      when(savedEff.getStatement()).thenReturn(MAPPER.createObjectNode());
      when(effectorService.save(any(EffectorEntity.class))).thenReturn(savedEff);

      ReceptorEntity savedRec = mock(ReceptorEntity.class);
      when(savedRec.getId()).thenReturn(UUID.randomUUID());
      when(savedRec.getStatement()).thenReturn(MAPPER.createObjectNode());
      when(receptorService.save(any(ReceptorEntity.class))).thenReturn(savedRec);

      service.derivePortsFromRule(mechanism);

      // Effector should use existing definition, not create new
      verify(definitionService, never()).create(DefinitionSubjectType.EFFECTOR);
      verify(effectorService).save(any(EffectorEntity.class));
    }
  }

  // ========================================================================
  // Port archetype resolution (named / fallback)
  // ========================================================================

  @Nested
  class PortArchetypeResolution {

    @Test
    void namedPortArchetypeFound_usesIt() {
      MechanismEntity mechanism =
          stubMechanism(
              "sys.receive(\"InputType\")\nsys.effect(\"OutputType\", {}).by(\"CustomEff\")");
      UUID mechDefId = mechanism.getDefinition().getId();

      when(ruleValidation.collectPortSignatures(any(String.class)))
          .thenReturn(
              List.of(
                  new PortSignature("receptor", "InputType", null),
                  new PortSignature("effector", "OutputType", "CustomEff")));

      ArchetypeEntity baseEff = mockArchetypeWithTitle("EffectorArchetype");
      ArchetypeEntity baseRec = mockArchetypeWithTitle("ReceptorArchetype");
      when(archetypeService.findInEffectBySchemaTitle("EffectorArchetype")).thenReturn(baseEff);
      when(archetypeService.findInEffectBySchemaTitle("ReceptorArchetype")).thenReturn(baseRec);

      ArchetypeEntity inputType = mockArchetypeWithTitle("InputType");
      ArchetypeEntity outputType = mockArchetypeWithTitle("OutputType");
      ArchetypeEntity customEff = mockArchetypeWithTitle("CustomEff");
      when(archetypeService.findInEffectBySchemaTitle("InputType")).thenReturn(inputType);
      when(archetypeService.findInEffectBySchemaTitle("OutputType")).thenReturn(outputType);
      when(archetypeService.findInEffectBySchemaTitle("CustomEff")).thenReturn(customEff);

      when(effectorService.findAllByMechanismDefinitionId(mechDefId)).thenReturn(List.of());
      when(receptorService.findAllByMechanismDefinitionId(mechDefId)).thenReturn(List.of());

      DefinitionEntity effDef = mock(DefinitionEntity.class);
      DefinitionEntity recDef = mock(DefinitionEntity.class);
      when(definitionService.create(DefinitionSubjectType.EFFECTOR)).thenReturn(effDef);
      when(definitionService.create(DefinitionSubjectType.RECEPTOR)).thenReturn(recDef);

      EffectorEntity savedEff = mock(EffectorEntity.class);
      when(savedEff.getId()).thenReturn(UUID.randomUUID());
      when(savedEff.getStatement()).thenReturn(MAPPER.createObjectNode());
      when(effectorService.save(any(EffectorEntity.class))).thenReturn(savedEff);

      ReceptorEntity savedRec = mock(ReceptorEntity.class);
      when(savedRec.getId()).thenReturn(UUID.randomUUID());
      when(savedRec.getStatement()).thenReturn(MAPPER.createObjectNode());
      when(receptorService.save(any(ReceptorEntity.class))).thenReturn(savedRec);

      service.derivePortsFromRule(mechanism);

      verify(effectorService).save(any(EffectorEntity.class));
      verify(receptorService).save(any(ReceptorEntity.class));
    }

    @Test
    void namedPortArchetypeNotFound_fallsBackToBase() {
      MechanismEntity mechanism =
          stubMechanism(
              "sys.receive(\"InputType\")\nsys.effect(\"OutputType\", {}).by(\"UnknownPort\")");
      UUID mechDefId = mechanism.getDefinition().getId();

      when(ruleValidation.collectPortSignatures(any(String.class)))
          .thenReturn(
              List.of(
                  new PortSignature("receptor", "InputType", null),
                  new PortSignature("effector", "OutputType", "UnknownPort")));

      ArchetypeEntity baseEff = mockArchetypeWithTitle("EffectorArchetype");
      ArchetypeEntity baseRec = mockArchetypeWithTitle("ReceptorArchetype");
      when(archetypeService.findInEffectBySchemaTitle("EffectorArchetype")).thenReturn(baseEff);
      when(archetypeService.findInEffectBySchemaTitle("ReceptorArchetype")).thenReturn(baseRec);

      ArchetypeEntity inputType = mockArchetypeWithTitle("InputType");
      ArchetypeEntity outputType = mockArchetypeWithTitle("OutputType");
      when(archetypeService.findInEffectBySchemaTitle("InputType")).thenReturn(inputType);
      when(archetypeService.findInEffectBySchemaTitle("OutputType")).thenReturn(outputType);
      when(archetypeService.findInEffectBySchemaTitle("UnknownPort")).thenReturn(null);

      when(effectorService.findAllByMechanismDefinitionId(mechDefId)).thenReturn(List.of());
      when(receptorService.findAllByMechanismDefinitionId(mechDefId)).thenReturn(List.of());

      DefinitionEntity effDef = mock(DefinitionEntity.class);
      DefinitionEntity recDef = mock(DefinitionEntity.class);
      when(definitionService.create(DefinitionSubjectType.EFFECTOR)).thenReturn(effDef);
      when(definitionService.create(DefinitionSubjectType.RECEPTOR)).thenReturn(recDef);

      EffectorEntity savedEff = mock(EffectorEntity.class);
      when(savedEff.getId()).thenReturn(UUID.randomUUID());
      when(savedEff.getStatement()).thenReturn(MAPPER.createObjectNode());
      when(effectorService.save(any(EffectorEntity.class))).thenReturn(savedEff);

      ReceptorEntity savedRec = mock(ReceptorEntity.class);
      when(savedRec.getId()).thenReturn(UUID.randomUUID());
      when(savedRec.getStatement()).thenReturn(MAPPER.createObjectNode());
      when(receptorService.save(any(ReceptorEntity.class))).thenReturn(savedRec);

      assertDoesNotThrow(() -> service.derivePortsFromRule(mechanism));
      verify(effectorService).save(any(EffectorEntity.class));
    }

    @Test
    void receiveChainWithOn_derivesTypedReceptor() {
      MechanismEntity mechanism =
          stubMechanism(
              "sys.receive(\"Trigger\")\nsys.effect(\"OutType\", {}).receive(\"AckType\").on(\"AckPort\")");
      UUID mechDefId = mechanism.getDefinition().getId();

      when(ruleValidation.collectPortSignatures(any(String.class)))
          .thenReturn(
              List.of(
                  new PortSignature("receptor", "Trigger", null),
                  new PortSignature("effector", "OutType", null),
                  new PortSignature("receptor", "AckType", "AckPort")));

      ArchetypeEntity baseEff = mockArchetypeWithTitle("EffectorArchetype");
      ArchetypeEntity baseRec = mockArchetypeWithTitle("ReceptorArchetype");
      when(archetypeService.findInEffectBySchemaTitle("EffectorArchetype")).thenReturn(baseEff);
      when(archetypeService.findInEffectBySchemaTitle("ReceptorArchetype")).thenReturn(baseRec);

      ArchetypeEntity trigger = mockArchetypeWithTitle("Trigger");
      ArchetypeEntity outType = mockArchetypeWithTitle("OutType");
      ArchetypeEntity ackType = mockArchetypeWithTitle("AckType");
      ArchetypeEntity ackPort = mockArchetypeWithTitle("AckPort");
      when(archetypeService.findInEffectBySchemaTitle("Trigger")).thenReturn(trigger);
      when(archetypeService.findInEffectBySchemaTitle("OutType")).thenReturn(outType);
      when(archetypeService.findInEffectBySchemaTitle("AckType")).thenReturn(ackType);
      when(archetypeService.findInEffectBySchemaTitle("AckPort")).thenReturn(ackPort);

      when(effectorService.findAllByMechanismDefinitionId(mechDefId)).thenReturn(List.of());
      when(receptorService.findAllByMechanismDefinitionId(mechDefId)).thenReturn(List.of());

      DefinitionEntity effDef = mock(DefinitionEntity.class);
      DefinitionEntity recDef1 = mock(DefinitionEntity.class);
      DefinitionEntity recDef2 = mock(DefinitionEntity.class);
      when(definitionService.create(DefinitionSubjectType.EFFECTOR)).thenReturn(effDef);
      when(definitionService.create(DefinitionSubjectType.RECEPTOR)).thenReturn(recDef1, recDef2);

      EffectorEntity savedEff = mock(EffectorEntity.class);
      when(savedEff.getId()).thenReturn(UUID.randomUUID());
      when(savedEff.getStatement()).thenReturn(MAPPER.createObjectNode());
      when(effectorService.save(any(EffectorEntity.class))).thenReturn(savedEff);

      ReceptorEntity savedRec = mock(ReceptorEntity.class);
      when(savedRec.getId()).thenReturn(UUID.randomUUID());
      when(savedRec.getStatement()).thenReturn(MAPPER.createObjectNode());
      when(receptorService.save(any(ReceptorEntity.class))).thenReturn(savedRec);

      service.derivePortsFromRule(mechanism);

      // 2 receptors: trigger + feedback from .receive("AckType")
      verify(receptorService, org.mockito.Mockito.atLeast(2)).save(any(ReceptorEntity.class));
    }
  }
}
