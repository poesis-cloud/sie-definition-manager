package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import cloud.poesis.sie.defman.service.MechanismPortDerivationService.PortSignature;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
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

  private final MechanismRuleParsingService parsingService = new MechanismRuleParsingService();
  @Mock private ArchetypeService archetypeService;
  @Mock private EffectorService effectorService;
  @Mock private ReceptorService receptorService;
  @Mock private DefinitionService definitionService;
  @Mock private EntityManager entityManager;

  private MechanismPortDerivationService service;

  @BeforeEach
  void setUp() {
    service =
        new MechanismPortDerivationService(
            parsingService,
            archetypeService,
            effectorService,
            receptorService,
            definitionService,
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
    when(arch.getId()).thenReturn(UUID.randomUUID());
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
    when(mechanism.getId()).thenReturn(UUID.randomUUID());
    when(mechanism.getStatement()).thenReturn(stmt);
    when(mechanism.getDefinition()).thenReturn(mechDef);
    return mechanism;
  }

  // ========================================================================
  // Port signature collection (from Starlark AST)
  // ========================================================================

  @Nested
  class PortSignatureCollection {

    private Set<PortSignature> uniqueSignatures(String rule) {
      return Set.copyOf(service.collectPortSignatures(rule));
    }

    @Nested
    class TriggerReceptor {

      @Test
      void sysReceive_producesReceptor() {
        List<PortSignature> sigs = service.collectPortSignatures("sys.receive(\"Event\")");
        assertEquals(1, sigs.size());
        assertEquals(new PortSignature("receptor", "Event", null), sigs.get(0));
      }

      @Test
      void sysAssigned_producesReceptor() {
        List<PortSignature> sigs = service.collectPortSignatures("x = sys.receive(\"Config\")");
        assertEquals(1, sigs.size());
        assertEquals("receptor", sigs.get(0).direction());
        assertEquals("Config", sigs.get(0).dataArchetypeName());
      }

      @Test
      void sysReceiveWithOn_capturesPortName() {
        List<PortSignature> sigs =
            service.collectPortSignatures("sys.receive(\"Feedback\").on(\"FbPort\")");
        assertEquals(1, sigs.size());
        assertEquals(new PortSignature("receptor", "Feedback", "FbPort"), sigs.get(0));
      }
    }

    @Nested
    class SysEffectEffectors {

      @Test
      void basicEffect_producesEffector() {
        List<PortSignature> sigs = service.collectPortSignatures("sys.effect(\"Order\", {})");
        assertEquals(1, sigs.size());
        assertEquals(new PortSignature("effector", "Order", null), sigs.get(0));
      }

      @Test
      void namedEffect_capturesBy() {
        List<PortSignature> sigs =
            service.collectPortSignatures("sys.effect(\"Order\", {}).by(\"CustomPort\")");
        assertEquals(1, sigs.size());
        assertEquals(new PortSignature("effector", "Order", "CustomPort"), sigs.get(0));
      }
    }

    @Nested
    class ClosedLoopReceptor {

      @Test
      void effectReceive_producesEffectorAndReceptor() {
        List<PortSignature> sigs =
            service.collectPortSignatures("sys.effect(\"Out\", {}).receive(\"Ack\")");
        assertEquals(2, sigs.size());
        assertEquals(new PortSignature("effector", "Out", null), sigs.get(0));
        assertEquals(new PortSignature("receptor", "Ack", null), sigs.get(1));
      }

      @Test
      void effectReceiveOn_capturesPortName() {
        List<PortSignature> sigs =
            service.collectPortSignatures(
                "sys.effect(\"Out\", {}).receive(\"Ack\").on(\"AckPort\")");
        assertEquals(2, sigs.size());
        assertEquals(new PortSignature("receptor", "Ack", "AckPort"), sigs.get(1));
      }
    }

    @Nested
    class ForLoopPorts {

      @Test
      void forLoop_collectsInnerEffects() {
        String rule = "for item in items:\n  sys.effect(\"Batch\", {})";
        Set<PortSignature> sigs = uniqueSignatures(rule);
        assertEquals(1, sigs.size());
        assertTrue(sigs.contains(new PortSignature("effector", "Batch", null)));
      }
    }

    @Nested
    class CombinedSignatures {

      @Test
      void multipleStatements_collectsAll() {
        String rule = "sys.receive(\"Trigger\")\nsys.effect(\"Out\", {}).receive(\"Ack\")";
        List<PortSignature> sigs = service.collectPortSignatures(rule);
        assertEquals(3, sigs.size());
      }

      @Test
      void emptyRule_noSignatures() {
        List<PortSignature> sigs = service.collectPortSignatures("x = 1");
        assertEquals(0, sigs.size());
      }
    }

    @Nested
    class Deduplication {

      @Test
      void duplicateSigs_dedupedBySet() {
        String rule = "sys.effect(\"Order\", {})\nsys.effect(\"Order\", {})";
        Set<PortSignature> unique = uniqueSignatures(rule);
        assertEquals(1, unique.size());
      }
    }
  }

  // ========================================================================
  // DerivePortsFromRule
  // ========================================================================

  @Nested
  class DerivePortsFromRule {

    @Test
    void derivesEffectorAndReceptor() {
      MechanismEntity mechanism =
          stubMechanism("sys.receive(\"InputType\")\nsys.effect(\"OutputType\", {})");
      UUID mechDefId = mechanism.getDefinition().getId();

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
    void alwaysCreatesFreshDefinitions() {
      MechanismEntity mechanism =
          stubMechanism("sys.receive(\"InputType\")\nsys.effect(\"OutputType\", {})");

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

      // Fresh definitions always created — no matching/reuse
      verify(definitionService).create(DefinitionSubjectType.EFFECTOR);
      verify(definitionService).create(DefinitionSubjectType.RECEPTOR);
      verify(effectorService).save(any(EffectorEntity.class));
      verify(receptorService).save(any(ReceptorEntity.class));
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

      ArchetypeEntity baseEff = mockArchetypeWithTitle("EffectorArchetype");
      ArchetypeEntity baseRec = mockArchetypeWithTitle("ReceptorArchetype");
      when(archetypeService.findInEffectBySchemaTitle("EffectorArchetype")).thenReturn(baseEff);
      when(archetypeService.findInEffectBySchemaTitle("ReceptorArchetype")).thenReturn(baseRec);

      ArchetypeEntity inputType = mockArchetypeWithTitle("InputType");
      ArchetypeEntity outputType = mockArchetypeWithTitle("OutputType");
      when(archetypeService.findInEffectBySchemaTitle("InputType")).thenReturn(inputType);
      when(archetypeService.findInEffectBySchemaTitle("OutputType")).thenReturn(outputType);
      when(archetypeService.findInEffectBySchemaTitle("UnknownPort")).thenReturn(null);

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
