package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.service.MechanismPortDerivationService.PortDerivation;
import cloud.poesis.sie.defman.service.MechanismPortDerivationService.PortSignature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
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

  private MechanismPortDerivationService service;

  @BeforeEach
  void setUp() {
    service =
        new MechanismPortDerivationService(parsingService, archetypeService, new ObjectMapper());
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
  // DerivePortSpecs
  // ========================================================================

  @Nested
  class DerivePortSpecs {

    @Test
    void derivesEffectorAndReceptorSpecs() {
      MechanismEntity mechanism =
          stubMechanism("sys.receive(\"InputType\")\nsys.effect(\"OutputType\", {})");

      ArchetypeEntity effArchetype = mockArchetypeWithTitle("Effector");
      ArchetypeEntity recArchetype = mockArchetypeWithTitle("Receptor");
      when(archetypeService.findInEffectByTitle("Effector")).thenReturn(Optional.of(effArchetype));
      when(archetypeService.findInEffectByTitle("Receptor")).thenReturn(Optional.of(recArchetype));

      ArchetypeEntity inputType = mockArchetypeWithTitle("InputType");
      ArchetypeEntity outputType = mockArchetypeWithTitle("OutputType");
      when(archetypeService.findInEffectByTitle("InputType")).thenReturn(Optional.of(inputType));
      when(archetypeService.findInEffectByTitle("OutputType")).thenReturn(Optional.of(outputType));

      List<PortDerivation> specs = service.derivePortSpecs(mechanism);

      assertEquals(2, specs.size());
      // Receptor first (trigger from sys.receive), then Effector (from sys.effect)
      assertEquals(recArchetype.getId(), specs.get(0).archetypeId());
      assertEquals(effArchetype.getId(), specs.get(1).archetypeId());
      // Both statements reference the mechanism and data archetype
      assertEquals(
          mechanism.getId().toString(), specs.get(0).statement().get("mechanism").asText());
      assertEquals(
          inputType.getId().toString(), specs.get(0).statement().get("archetype").asText());
      assertEquals(
          mechanism.getId().toString(), specs.get(1).statement().get("mechanism").asText());
      assertEquals(
          outputType.getId().toString(), specs.get(1).statement().get("archetype").asText());
    }

    @Test
    void baseArchetypesMissing_returnsEmpty() {
      MechanismEntity mechanism = stubMechanism("sys.receive(\"X\")\nsys.effect(\"Y\", {})");

      when(archetypeService.findInEffectByTitle("Effector")).thenReturn(Optional.empty());
      when(archetypeService.findInEffectByTitle("Receptor")).thenReturn(Optional.empty());

      List<PortDerivation> specs = service.derivePortSpecs(mechanism);

      assertTrue(specs.isEmpty());
    }

    @Test
    void dataArchetypeNotFound_skipsPort() {
      MechanismEntity mechanism =
          stubMechanism("sys.receive(\"MissingType\")\nsys.effect(\"AlsoMissing\", {})");

      ArchetypeEntity effArchetype = mockArchetypeWithTitle("Effector");
      ArchetypeEntity recArchetype = mockArchetypeWithTitle("Receptor");
      when(archetypeService.findInEffectByTitle("Effector")).thenReturn(Optional.of(effArchetype));
      when(archetypeService.findInEffectByTitle("Receptor")).thenReturn(Optional.of(recArchetype));

      when(archetypeService.findInEffectByTitle("MissingType")).thenReturn(Optional.empty());
      when(archetypeService.findInEffectByTitle("AlsoMissing")).thenReturn(Optional.empty());

      List<PortDerivation> specs = service.derivePortSpecs(mechanism);

      assertTrue(specs.isEmpty());
    }

    @Test
    void noPortSignatures_returnsEmpty() {
      MechanismEntity mechanism = stubMechanism("x = 1");

      List<PortDerivation> specs = service.derivePortSpecs(mechanism);

      assertTrue(specs.isEmpty());
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

      ArchetypeEntity baseEff = mockArchetypeWithTitle("Effector");
      ArchetypeEntity baseRec = mockArchetypeWithTitle("Receptor");
      when(archetypeService.findInEffectByTitle("Effector")).thenReturn(Optional.of(baseEff));
      when(archetypeService.findInEffectByTitle("Receptor")).thenReturn(Optional.of(baseRec));

      ArchetypeEntity inputType = mockArchetypeWithTitle("InputType");
      ArchetypeEntity outputType = mockArchetypeWithTitle("OutputType");
      ArchetypeEntity customEff = mockArchetypeWithTitle("CustomEff");
      when(archetypeService.findInEffectByTitle("InputType")).thenReturn(Optional.of(inputType));
      when(archetypeService.findInEffectByTitle("OutputType")).thenReturn(Optional.of(outputType));
      when(archetypeService.findInEffectByTitle("CustomEff")).thenReturn(Optional.of(customEff));

      List<PortDerivation> specs = service.derivePortSpecs(mechanism);

      assertEquals(2, specs.size());
      // Receptor first (trigger), uses base
      assertEquals(baseRec.getId(), specs.get(0).archetypeId());
      // Effector uses custom archetype, not base
      assertEquals(customEff.getId(), specs.get(1).archetypeId());
    }

    @Test
    void namedPortArchetypeNotFound_fallsBackToBase() {
      MechanismEntity mechanism =
          stubMechanism(
              "sys.receive(\"InputType\")\nsys.effect(\"OutputType\", {}).by(\"UnknownPort\")");

      ArchetypeEntity baseEff = mockArchetypeWithTitle("Effector");
      ArchetypeEntity baseRec = mockArchetypeWithTitle("Receptor");
      when(archetypeService.findInEffectByTitle("Effector")).thenReturn(Optional.of(baseEff));
      when(archetypeService.findInEffectByTitle("Receptor")).thenReturn(Optional.of(baseRec));

      ArchetypeEntity inputType = mockArchetypeWithTitle("InputType");
      ArchetypeEntity outputType = mockArchetypeWithTitle("OutputType");
      when(archetypeService.findInEffectByTitle("InputType")).thenReturn(Optional.of(inputType));
      when(archetypeService.findInEffectByTitle("OutputType")).thenReturn(Optional.of(outputType));
      when(archetypeService.findInEffectByTitle("UnknownPort")).thenReturn(Optional.empty());

      List<PortDerivation> specs = service.derivePortSpecs(mechanism);

      assertEquals(2, specs.size());
      // Receptor first (trigger), then Effector falls back to base
      assertEquals(baseRec.getId(), specs.get(0).archetypeId());
      assertEquals(baseEff.getId(), specs.get(1).archetypeId());
    }

    @Test
    void receiveChainWithOn_derivesTypedReceptor() {
      MechanismEntity mechanism =
          stubMechanism(
              "sys.receive(\"Trigger\")\nsys.effect(\"OutType\", {}).receive(\"AckType\").on(\"AckPort\")");

      ArchetypeEntity baseEff = mockArchetypeWithTitle("Effector");
      ArchetypeEntity baseRec = mockArchetypeWithTitle("Receptor");
      when(archetypeService.findInEffectByTitle("Effector")).thenReturn(Optional.of(baseEff));
      when(archetypeService.findInEffectByTitle("Receptor")).thenReturn(Optional.of(baseRec));

      ArchetypeEntity trigger = mockArchetypeWithTitle("Trigger");
      ArchetypeEntity outType = mockArchetypeWithTitle("OutType");
      ArchetypeEntity ackType = mockArchetypeWithTitle("AckType");
      ArchetypeEntity ackPort = mockArchetypeWithTitle("AckPort");
      when(archetypeService.findInEffectByTitle("Trigger")).thenReturn(Optional.of(trigger));
      when(archetypeService.findInEffectByTitle("OutType")).thenReturn(Optional.of(outType));
      when(archetypeService.findInEffectByTitle("AckType")).thenReturn(Optional.of(ackType));
      when(archetypeService.findInEffectByTitle("AckPort")).thenReturn(Optional.of(ackPort));

      List<PortDerivation> specs = service.derivePortSpecs(mechanism);

      // 3 ports: trigger receptor (base), effector (base), feedback receptor (typed
      // AckPort)
      assertEquals(3, specs.size());
      assertEquals(baseRec.getId(), specs.get(0).archetypeId()); // trigger receptor → base
      assertEquals(baseEff.getId(), specs.get(1).archetypeId()); // effector → base
      assertEquals(ackPort.getId(), specs.get(2).archetypeId()); // feedback receptor → AckPort
    }
  }
}
