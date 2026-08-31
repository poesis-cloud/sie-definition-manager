package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.service.MechanismPortDerivationService.PortDerivation;
import cloud.poesis.sie.defman.service.MechanismPortDerivationService.PortSignature;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
  private static final String EFFECTOR_ID = "gsmarc://gsm/Effector/v1";
  private static final String RECEPTOR_ID = "gsmarc://gsm/Receptor/v1";

  private final MechanismRuleParsingService parsingService = new MechanismRuleParsingService();
  @Mock
  private ArchetypeService archetypeService;

  private MechanismPortDerivationService service;

  @BeforeEach
  void setUp() {
    service = new MechanismPortDerivationService(parsingService, archetypeService, new ObjectMapper());
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private ArchetypeEntity mockArchetypeWithTitle(String title) {
    return mockArchetypeWithStatus(title, AscriptionStatusType.ACTIVE);
  }

  private ArchetypeEntity mockArchetypeWithStatus(String title, AscriptionStatusType status) {
    UUID defId = UUID.randomUUID();
    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(defId);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    when(arch.getId()).thenReturn(UUID.randomUUID());
    when(arch.getDefinition()).thenReturn(def);
    when(arch.getStatus()).thenReturn(status);
    ObjectNode schema = MAPPER
        .createObjectNode()
        .put("$id", "gsmarc://tenant/" + title + "/v1")
        .put("title", title);
    when(arch.getStatement()).thenReturn(schema);
    return arch;
  }

  private String id(String title) {
    return "gsmarc://tenant/" + title + "/v1";
  }

  private void stubTyping(String archetypeId, ArchetypeEntity archetype) {
    when(archetypeService.resolveForCreation(archetypeId))
        .thenReturn(
            new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.ARCHETYPE));
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
        assertEquals("Config", sigs.get(0).dataArchetypeId());
      }

      @Test
      void sysReceiveWithOn_capturesPortName() {
        List<PortSignature> sigs = service.collectPortSignatures("sys.receive(\"Feedback\").on(\"FbPort\")");
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
        List<PortSignature> sigs = service.collectPortSignatures("sys.effect(\"Order\", {}).by(\"CustomPort\")");
        assertEquals(1, sigs.size());
        assertEquals(new PortSignature("effector", "Order", "CustomPort"), sigs.get(0));
      }
    }

    @Nested
    class ClosedLoopReceptor {

      @Test
      void effectReceive_producesEffectorAndReceptor() {
        List<PortSignature> sigs = service.collectPortSignatures("sys.effect(\"Out\", {}).receive(\"Ack\")");
        assertEquals(2, sigs.size());
        assertEquals(new PortSignature("effector", "Out", null), sigs.get(0));
        assertEquals(new PortSignature("receptor", "Ack", null), sigs.get(1));
      }

      @Test
      void effectReceiveOn_capturesPortName() {
        List<PortSignature> sigs = service.collectPortSignatures(
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
    void resolvesBaseAndDataArchetypesByUri() {
      String inputId = "gsmarc://tenant/events/InputType/v1";
      String outputId = "gsmarc://tenant/events/OutputType/v2";
      MechanismEntity mechanism = stubMechanism(
          "sys.receive(\"" + inputId + "\")\nsys.effect(\"" + outputId + "\", {})");
      ArchetypeEntity effector = mockArchetypeWithTitle("Effector");
      ArchetypeEntity receptor = mockArchetypeWithTitle("Receptor");
      ArchetypeEntity input = mockArchetypeWithTitle("InputType");
      ArchetypeEntity output = mockArchetypeWithTitle("OutputType");
      stubTyping(EFFECTOR_ID, effector);
      stubTyping(RECEPTOR_ID, receptor);
      when(archetypeService.resolveArchetypeUri(inputId, "mechanism rule reference"))
          .thenReturn(input);
      when(archetypeService.resolveArchetypeUri(outputId, "mechanism rule reference"))
          .thenReturn(output);

      List<PortDerivation> specs = service.derivePortSpecs(mechanism);

      assertEquals(2, specs.size());
      verify(archetypeService).resolveForCreation(EFFECTOR_ID);
      verify(archetypeService).resolveForCreation(RECEPTOR_ID);
      verify(archetypeService).resolveArchetypeUri(inputId, "mechanism rule reference");
      verify(archetypeService).resolveArchetypeUri(outputId, "mechanism rule reference");
    }

    @Test
    void derivesEffectorAndReceptorSpecs() {
      MechanismEntity mechanism = stubMechanism(
          "sys.receive(\""
              + id("InputType")
              + "\")\nsys.effect(\""
              + id("OutputType")
              + "\", {})");

      ArchetypeEntity effArchetype = mockArchetypeWithTitle("Effector");
      ArchetypeEntity recArchetype = mockArchetypeWithTitle("Receptor");
      stubTyping(EFFECTOR_ID, effArchetype);
      stubTyping(RECEPTOR_ID, recArchetype);

      ArchetypeEntity inputType = mockArchetypeWithTitle("InputType");
      ArchetypeEntity outputType = mockArchetypeWithTitle("OutputType");
      when(archetypeService.resolveArchetypeUri(id("InputType"), "mechanism rule reference"))
          .thenReturn(inputType);
      when(archetypeService.resolveArchetypeUri(id("OutputType"), "mechanism rule reference"))
          .thenReturn(outputType);

      List<PortDerivation> specs = service.derivePortSpecs(mechanism);

      assertEquals(2, specs.size());
      // Receptor first (trigger from sys.receive), then Effector (from sys.effect)
      assertEquals("gsmarc://tenant/Receptor/v1", specs.get(0).archetypeId());
      assertEquals("gsmarc://tenant/Effector/v1", specs.get(1).archetypeId());
      // Both statements reference the mechanism and data archetype
      assertEquals(
          mechanism.getId().toString(), specs.get(0).statement().get("mechanism").asText());
      assertEquals(
          "gsmarc://tenant/InputType/v1", specs.get(0).statement().get("archetype").asText());
      assertEquals(
          mechanism.getId().toString(), specs.get(1).statement().get("mechanism").asText());
      assertEquals(
          "gsmarc://tenant/OutputType/v1", specs.get(1).statement().get("archetype").asText());
    }

    @Test
    void baseArchetypeNotEligible_failsHard() {
      MechanismEntity mechanism = stubMechanism(
          "sys.receive(\"" + id("X") + "\")\nsys.effect(\"" + id("Y") + "\", {})");
      when(archetypeService.resolveForCreation(EFFECTOR_ID))
          .thenThrow(
              RuleViolationException.of(
                  AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_IN_EFFECT,
                  "Typing Archetype is not eligible"));

      assertThrows(RuleViolationException.class, () -> service.derivePortSpecs(mechanism));
    }

    @Test
    void dataArchetypeNotEligible_failsHard() {
      MechanismEntity mechanism = stubMechanism(
          "sys.receive(\""
              + id("MissingType")
              + "\")\nsys.effect(\""
              + id("AlsoMissing")
              + "\", {})");

      ArchetypeEntity effArchetype = mockArchetypeWithTitle("Effector");
      ArchetypeEntity recArchetype = mockArchetypeWithTitle("Receptor");
      ArchetypeEntity missingType = mockArchetypeWithStatus("MissingType", AscriptionStatusType.RETIRED);
      stubTyping(EFFECTOR_ID, effArchetype);
      stubTyping(RECEPTOR_ID, recArchetype);

      when(archetypeService.resolveArchetypeUri(id("MissingType"), "mechanism rule reference"))
          .thenReturn(missingType);
      doThrow(
          RuleViolationException.of(
              AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY,
              "Archetype Referee is not eligible"))
          .when(archetypeService)
          .validateRefereeEligibility(missingType, "mechanism rule reference");

      assertThrows(RuleViolationException.class, () -> service.derivePortSpecs(mechanism));
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
      MechanismEntity mechanism = stubMechanism(
          "sys.receive(\""
              + id("InputType")
              + "\")\nsys.effect(\""
              + id("OutputType")
              + "\", {}).by(\""
              + id("CustomEff")
              + "\")");

      ArchetypeEntity baseEff = mockArchetypeWithTitle("Effector");
      ArchetypeEntity baseRec = mockArchetypeWithTitle("Receptor");
      stubTyping(EFFECTOR_ID, baseEff);
      stubTyping(RECEPTOR_ID, baseRec);

      ArchetypeEntity inputType = mockArchetypeWithTitle("InputType");
      ArchetypeEntity outputType = mockArchetypeWithTitle("OutputType");
      ArchetypeEntity customEff = mockArchetypeWithTitle("CustomEff");
      when(archetypeService.resolveArchetypeUri(id("InputType"), "mechanism rule reference"))
          .thenReturn(inputType);
      when(archetypeService.resolveArchetypeUri(id("OutputType"), "mechanism rule reference"))
          .thenReturn(outputType);
      stubTyping(id("CustomEff"), customEff);

      List<PortDerivation> specs = service.derivePortSpecs(mechanism);

      assertEquals(2, specs.size());
      // Receptor first (trigger), uses base
      assertEquals(id("Receptor"), specs.get(0).archetypeId());
      // Effector uses custom archetype, not base
      assertEquals(id("CustomEff"), specs.get(1).archetypeId());
    }

    @Test
    void namedPortArchetypeNotEligible_failsHard() {
      MechanismEntity mechanism = stubMechanism(
          "sys.receive(\""
              + id("InputType")
              + "\")\nsys.effect(\""
              + id("OutputType")
              + "\", {}).by(\""
              + id("UnknownPort")
              + "\")");

      ArchetypeEntity baseEff = mockArchetypeWithTitle("Effector");
      ArchetypeEntity baseRec = mockArchetypeWithTitle("Receptor");
      stubTyping(EFFECTOR_ID, baseEff);
      stubTyping(RECEPTOR_ID, baseRec);

      ArchetypeEntity inputType = mockArchetypeWithTitle("InputType");
      ArchetypeEntity outputType = mockArchetypeWithTitle("OutputType");
      when(archetypeService.resolveArchetypeUri(id("InputType"), "mechanism rule reference"))
          .thenReturn(inputType);
      when(archetypeService.resolveArchetypeUri(id("OutputType"), "mechanism rule reference"))
          .thenReturn(outputType);
      when(archetypeService.resolveForCreation(id("UnknownPort")))
          .thenThrow(
              RuleViolationException.of(
                  AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_IN_EFFECT,
                  "Typing Archetype is not eligible"));

      assertThrows(RuleViolationException.class, () -> service.derivePortSpecs(mechanism));
    }

    @Test
    void receiveChainWithOn_derivesTypedReceptor() {
      MechanismEntity mechanism = stubMechanism(
          "sys.receive(\""
              + id("Trigger")
              + "\")\nsys.effect(\""
              + id("OutType")
              + "\", {}).receive(\""
              + id("AckType")
              + "\").on(\""
              + id("AckPort")
              + "\")");

      ArchetypeEntity baseEff = mockArchetypeWithTitle("Effector");
      ArchetypeEntity baseRec = mockArchetypeWithTitle("Receptor");
      stubTyping(EFFECTOR_ID, baseEff);
      stubTyping(RECEPTOR_ID, baseRec);

      ArchetypeEntity trigger = mockArchetypeWithTitle("Trigger");
      ArchetypeEntity outType = mockArchetypeWithTitle("OutType");
      ArchetypeEntity ackType = mockArchetypeWithTitle("AckType");
      ArchetypeEntity ackPort = mockArchetypeWithTitle("AckPort");
      when(archetypeService.resolveArchetypeUri(id("Trigger"), "mechanism rule reference"))
          .thenReturn(trigger);
      when(archetypeService.resolveArchetypeUri(id("OutType"), "mechanism rule reference"))
          .thenReturn(outType);
      when(archetypeService.resolveArchetypeUri(id("AckType"), "mechanism rule reference"))
          .thenReturn(ackType);
      stubTyping(id("AckPort"), ackPort);

      List<PortDerivation> specs = service.derivePortSpecs(mechanism);

      // 3 ports: trigger receptor (base), effector (base), feedback receptor (typed
      // AckPort)
      assertEquals(3, specs.size());
      assertEquals(id("Receptor"), specs.get(0).archetypeId()); // trigger receptor → base
      assertEquals(id("Effector"), specs.get(1).archetypeId()); // effector → base
      assertEquals(id("AckPort"), specs.get(2).archetypeId()); // feedback receptor → AckPort
    }
  }
}
