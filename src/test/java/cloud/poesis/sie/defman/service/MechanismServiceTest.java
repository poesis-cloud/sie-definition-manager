package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.EffectorEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.entity.ReceptorEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.repository.MechanismRepository;
import cloud.poesis.sie.defman.service.MechanismService.PortSignature;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import cloud.poesis.sie.defman.type.RuleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.StarlarkFile;
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

  @Mock private EffectorService effectorService;

  @Mock private ReceptorService receptorService;

  @Mock private StructureService structureService;

  @Mock private ArchetypeService archetypeService;

  @Mock private DefinitionService definitionService;

  @Mock private AscriptionStatusTransitionService transitionService;

  @Mock private EntityManager entityManager;

  private MechanismService service;

  @BeforeEach
  void setUp() {
    service =
        new MechanismService(
            mechanismRepo,
            structureService,
            archetypeService,
            mock(ArchetypeRepository.class),
            effectorService,
            receptorService,
            definitionService,
            transitionService,
            mock(AscriptionService.class),
            entityManager,
            mock(DataProtectionService.class));
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private void stubGenerativeModeValid(UUID mechanismDefId) {
    // Generative mode: has rule → skip port check
  }

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

  private StarlarkFile parse(String rule) {
    return StarlarkFile.parse(ParserInput.fromString(rule, "<test>"), FileOptions.DEFAULT);
  }

  private Set<PortSignature> uniqueSignatures(String rule) {
    return Set.copyOf(service.collectPortSignatures(parse(rule)));
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

        stubGenerativeModeValid(thisDefId);

        when(mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
                structureDefId,
                List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
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

        stubGenerativeModeValid(thisDefId);

        when(mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
                structureDefId,
                List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
            .thenReturn(List.of(existing));

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class, () -> service.validateActivationUniqueness(entity));
        assertEquals(RuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS, ex.getRuleType());
        assertTrue(ex.getMessage().contains("UserValidation"));
        assertTrue(ex.getMessage().contains("already in"));
      }

      @Test
      void sameFunction_sameDefinition_valid() {
        UUID structureDefId = UUID.randomUUID();
        UUID defId = UUID.randomUUID();

        MechanismEntity entity = stubMechanism("UserValidation", structureDefId, defId);
        MechanismEntity existing = stubMechanism("UserValidation", structureDefId, defId);

        stubGenerativeModeValid(defId);

        when(mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
                structureDefId,
                List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
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

        stubGenerativeModeValid(thisDefId);

        when(mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
                structureDefId,
                List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
            .thenReturn(List.of(existing));

        assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
      }

      @Test
      void differentStructure_sameFunctionAllowed() {
        UUID structureDefId1 = UUID.randomUUID();
        UUID thisDefId = UUID.randomUUID();

        MechanismEntity entity = stubMechanism("UserValidation", structureDefId1, thisDefId);

        stubGenerativeModeValid(thisDefId);

        when(mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
                structureDefId1,
                List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
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
        assertEquals("structure", refs.get(0).label());
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
  // PortDerivation
  // ========================================================================

  @Nested
  class PortDerivation {

    @Nested
    class TriggerReceptor {

      @Test
      void onTrigger_producesReceptor() {
        List<PortSignature> sigs =
            service.collectPortSignatures(
                parse(
                    """
                    sys.receive("AlertEvent")
                    sys.effect("NotificationEvent", {"level": "warn"})
                    """));

        assertTrue(
            sigs.stream()
                .anyMatch(
                    s ->
                        "receptor".equals(s.direction())
                            && "AlertEvent".equals(s.dataArchetypeName())));
      }

      @Test
      void onAssigned_producesReceptor() {
        List<PortSignature> sigs =
            service.collectPortSignatures(
                parse(
                    """
                    evt = sys.receive("IncomingOrder")
                    sys.effect("OrderAck", {"ok": True})
                    """));

        assertTrue(
            sigs.stream()
                .anyMatch(
                    s ->
                        "receptor".equals(s.direction())
                            && "IncomingOrder".equals(s.dataArchetypeName())));
      }
    }

    @Nested
    class SysEffectEffectors {

      @Test
      void sysEffect_producesEffector() {
        Set<PortSignature> sigs =
            uniqueSignatures(
                """
                sys.receive("Trigger")
                sys.effect("OutputEvent", {"data": 1})
                """);

        assertTrue(sigs.contains(new PortSignature("effector", "OutputEvent", null)));
      }

      @Test
      void sysEffectWithBy_producesTypedEffector() {
        Set<PortSignature> sigs =
            uniqueSignatures(
                """
                sys.receive("Trigger")
                sys.effect("OutputEvent", {"data": 1}).by("CustomEffector")
                """);

        assertTrue(sigs.contains(new PortSignature("effector", "OutputEvent", "CustomEffector")));
      }

      @Test
      void multipleSysEffects_eachProducesEffector() {
        Set<PortSignature> sigs =
            uniqueSignatures(
                """
                sys.receive("Trigger")
                sys.effect("A", {})
                sys.effect("B", {"x": 1})
                sys.effect("C", {}).by("TypedPort")
                """);

        assertTrue(sigs.contains(new PortSignature("effector", "A", null)));
        assertTrue(sigs.contains(new PortSignature("effector", "B", null)));
        assertTrue(sigs.contains(new PortSignature("effector", "C", "TypedPort")));
      }
    }

    @Nested
    class ClosedLoopReceptor {

      @Test
      void effectWithReceive_producesEffectorAndReceptor() {
        Set<PortSignature> sigs =
            uniqueSignatures(
                """
                sys.receive("Trigger")
                sys.effect("NewRecord", {"id": "x"}).receive("Feedback")
                """);

        assertTrue(sigs.contains(new PortSignature("effector", "NewRecord", null)));
        assertTrue(sigs.contains(new PortSignature("receptor", "Feedback", null)));
      }

      @Test
      void effectWithReceiveAndOn_producesTypedReceptor() {
        Set<PortSignature> sigs =
            uniqueSignatures(
                """
                sys.receive("Trigger")
                sys.effect("Record", {"id": "x"}).receive("Ack").on("AckPort")
                """);

        assertTrue(sigs.contains(new PortSignature("effector", "Record", null)));
        assertTrue(sigs.contains(new PortSignature("receptor", "Ack", "AckPort")));
      }

      @Test
      void effectWithoutReceive_noFeedbackReceptor() {
        List<PortSignature> sigs =
            service.collectPortSignatures(
                parse(
                    """
                    sys.receive("Trigger")
                    sys.effect("NewRecord", {"id": "x"})
                    """));

        long receptorCount =
            sigs.stream()
                .filter(
                    s ->
                        "receptor".equals(s.direction())
                            && "NewRecord".equals(s.dataArchetypeName()))
                .count();
        assertEquals(0, receptorCount, "effect() without .receive() should not produce receptor");
      }
    }

    @Nested
    class FeedbackReceptor {

      @Test
      void effectWithReceive_producesFeedbackReceptor() {
        Set<PortSignature> sigs =
            uniqueSignatures(
                """
                sys.receive("Trigger")
                sys.effect("Request", {}).receive("ResponseType")
                """);

        assertTrue(sigs.contains(new PortSignature("effector", "Request", null)));
        assertTrue(sigs.contains(new PortSignature("receptor", "ResponseType", null)));
      }
    }

    @Nested
    class ForLoopPorts {

      @Test
      void sysCallInsideForLoop_producesEffector() {
        Set<PortSignature> sigs =
            uniqueSignatures(
                """
                sys.receive("Batch")
                items = [1, 2, 3]
                for item in items:
                    sys.effect("ItemEvent", {"item": item})
                """);

        assertTrue(sigs.contains(new PortSignature("effector", "ItemEvent", null)));
      }

      @Test
      void sysCallWithReceiveInForLoop_producesClosedLoop() {
        Set<PortSignature> sigs =
            uniqueSignatures(
                """
                sys.receive("Batch")
                items = [1, 2, 3]
                for item in items:
                    sys.effect("ItemRecord", {"item": item}).receive("ItemAck")
                """);

        assertTrue(sigs.contains(new PortSignature("effector", "ItemRecord", null)));
        assertTrue(sigs.contains(new PortSignature("receptor", "ItemAck", null)));
      }
    }

    @Nested
    class CombinedSignatures {

      @Test
      void complexRule_allPortTypes() {
        Set<PortSignature> sigs =
            uniqueSignatures(
                """
                evt = sys.receive("PaymentRequest")
                sys.effect("PaymentRecord", {"amount": 100}).receive("PaymentAck")
                sys.effect("PaymentProcessed", {"status": "ok"})
                sys.effect("ExternalNotify", {}).receive("NotifyAck").on("AckPort")
                """);

        assertTrue(sigs.contains(new PortSignature("receptor", "PaymentRequest", null)));
        assertTrue(sigs.contains(new PortSignature("effector", "PaymentRecord", null)));
        assertTrue(sigs.contains(new PortSignature("receptor", "PaymentAck", null)));
        assertTrue(sigs.contains(new PortSignature("effector", "PaymentProcessed", null)));
        assertTrue(sigs.contains(new PortSignature("effector", "ExternalNotify", null)));
        assertTrue(sigs.contains(new PortSignature("receptor", "NotifyAck", "AckPort")));
      }

      @Test
      void complexRule_correctCounts() {
        Set<PortSignature> sigs =
            uniqueSignatures(
                """
                evt = sys.receive("PaymentRequest")
                sys.effect("PaymentRecord", {"amount": 100}).receive("PaymentAck")
                sys.effect("PaymentProcessed", {"status": "ok"})
                """);

        long effectors = sigs.stream().filter(s -> "effector".equals(s.direction())).count();
        long receptors = sigs.stream().filter(s -> "receptor".equals(s.direction())).count();

        assertEquals(2, effectors, "PaymentRecord + PaymentProcessed effectors");
        assertEquals(2, receptors, "PaymentRequest trigger + PaymentAck feedback receptors");
      }
    }

    @Nested
    class Deduplication {

      @Test
      void duplicateEffectors_deduplicatedInUniqueSet() {
        Set<PortSignature> sigs =
            uniqueSignatures(
                """
                sys.receive("Trigger")
                sys.effect("SameEvent", {"a": 1})
                sys.effect("SameEvent", {"b": 2})
                """);

        long effectorCount =
            sigs.stream()
                .filter(
                    s ->
                        "effector".equals(s.direction())
                            && "SameEvent".equals(s.dataArchetypeName()))
                .count();
        assertEquals(1, effectorCount, "Duplicate effectors should be deduplicated");
      }
    }
  }

  // ========================================================================
  // Starlark
  // ========================================================================

  @Nested
  class Starlark {

    @Nested
    class ValidRules {

      @Test
      void minimalRule_onTriggerOnly() {
        String rule =
            """
            sys.receive("AlertEvent")
            sys.effect("NotificationEvent", {"level": "warn"})
            """;
        String trigger = service.validateStarlarkRule(rule);
        assertEquals("AlertEvent", trigger);
      }

      @Test
      void onWithAssignment() {
        String rule =
            """
            evt = sys.receive("IncomingOrder")
            sys.effect("OrderRecord", {"id": uuid7(), "data": evt})
            """;
        String trigger = service.validateStarlarkRule(rule);
        assertEquals("IncomingOrder", trigger);
      }

      @Test
      void multipleSysCalls() {
        String rule =
            """
            evt = sys.receive("PaymentRequest")
            sys.effect("PaymentRecord", {"amount": evt}).receive("PaymentAck")
            sys.effect("PaymentProcessed", {"status": "ok"})
            sys.effect("AccountBalance", {"delta": 100}).by("BalanceEffector")
            """;
        String trigger = service.validateStarlarkRule(rule);
        assertEquals("PaymentRequest", trigger);
      }

      @Test
      void withConditionalLogic() {
        String rule =
            """
            evt = sys.receive("ValidationRequest")
            if evt:
                sys.effect("ValidationOk", {"valid": True})
            """;
        String trigger = service.validateStarlarkRule(rule);
        assertEquals("ValidationRequest", trigger);
      }

      @Test
      void withForLoop() {
        String rule =
            """
            evt = sys.receive("BatchInput")
            items = [1, 2, 3]
            for item in items:
                sys.effect("ItemProcessed", {"item": item})
            """;
        assertEquals("BatchInput", service.validateStarlarkRule(rule));
      }

      @Test
      void withLocalVariables() {
        String rule =
            """
            evt = sys.receive("SomeEvent")
            x = 42
            name = "hello"
            sys.effect("Result", {"val": x, "name": name})
            """;
        assertEquals("SomeEvent", service.validateStarlarkRule(rule));
      }

      @Test
      void withBuiltinFunctions() {
        String rule =
            """
            evt = sys.receive("Input")
            length = len("hello")
            items = sorted([3, 1, 2])
            sys.effect("Output", {"len": length, "items": items})
            """;
        assertEquals("Input", service.validateStarlarkRule(rule));
      }

      @Test
      void withHostFunctions() {
        String rule =
            """
            evt = sys.receive("Input")
            ts = now()
            id = uuid7()
            matched = fullmatch("^abc.*", "abcdef")
            found = search("[0-9]+", "abc123")
            sys.effect("Output", {"ts": ts, "id": id})
            """;
        assertEquals("Input", service.validateStarlarkRule(rule));
      }

      @Test
      void allChainPatterns() {
        String rule =
            """
            sys.receive("Trigger")
            sys.effect("A", {})
            sys.effect("B", {}).by("BPort")
            sys.effect("C", {}).receive("CAck")
            sys.effect("D", {}).receive("DAck").on("DPort")
            sys.effect("E", {}).by("EPort").receive("EAck").on("EReceptor")
            """;
        assertEquals("Trigger", service.validateStarlarkRule(rule));
      }
    }

    @Nested
    class SyntaxErrors {

      @Test
      void nullRule_rejected() {
        RuleViolationException ex =
            assertThrows(RuleViolationException.class, () -> service.validateStarlarkRule(null));
        assertEquals(RuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
      }

      @Test
      void emptyRule_rejected() {
        RuleViolationException ex =
            assertThrows(RuleViolationException.class, () -> service.validateStarlarkRule(""));
        assertEquals(RuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
      }

      @Test
      void blankRule_rejected() {
        RuleViolationException ex =
            assertThrows(RuleViolationException.class, () -> service.validateStarlarkRule("   "));
        assertEquals(RuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
      }

      @Test
      void invalidSyntax_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class, () -> service.validateStarlarkRule("def foo(:::"));
        assertEquals(RuleType.MECHANISM_RULE_STARLARK_PARSING, ex.getRuleType());
        assertTrue(ex.getMessage().contains("syntax error"));
      }
    }

    @Nested
    class OnTriggerViolations {

      @Test
      void missingOnTrigger_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateStarlarkRule("sys.effect(\"X\", {})"));
        assertEquals(RuleType.MECHANISM_RULE_TRIGGER_AS_FIRST_STATEMENT, ex.getRuleType());
        assertTrue(ex.getMessage().contains("sys.receive("));
      }

      @Test
      void onWithNoArgs_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    sys.receive()
                    sys.effect("X", {})
                    """));
        assertEquals(RuleType.MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE, ex.getRuleType());
        assertTrue(ex.getMessage().contains("one positional string argument"));
      }

      @Test
      void onWithVariableArg_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    name = "Foo"
                    sys.receive(name)
                    sys.effect("X", {})
                    """));
        assertEquals(RuleType.MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE, ex.getRuleType());
        assertTrue(ex.getMessage().contains("sys.receive(") || ex.getMessage().contains("first"));
      }

      @Test
      void onWithEmptyString_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    sys.receive("")
                    sys.effect("X", {})
                    """));
        assertEquals(RuleType.MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE, ex.getRuleType());
        assertTrue(ex.getMessage().contains("empty"));
      }

      @Test
      void multipleOnCalls_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    sys.receive("First")
                    sys.receive("Second")
                    """));
        assertEquals(RuleType.MECHANISM_RULE_TRIGGER_AS_UNIQUE_STATEMENT, ex.getRuleType());
        assertTrue(ex.getMessage().contains("exactly one sys.receive()"));
      }
    }

    @Nested
    class LoadForbidden {

      @Test
      void loadStatement_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    load("module.bzl", "helper")
                    sys.receive("X")
                    sys.effect("Y", {})
                    """));
        assertEquals(RuleType.MECHANISM_RULE_STARLARK_CONSTRUCT_BLACKLIST, ex.getRuleType());
        assertTrue(ex.getMessage().contains("load()"));
      }
    }

    @Nested
    class SysCallValidation {

      @Test
      void sysEffectWithVariableArchetype_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    sys.receive("Input")
                    archetype = "DynamicType"
                    sys.effect(archetype, {})
                    """));
        assertEquals(RuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY, ex.getRuleType());
        assertTrue(ex.getMessage().contains("string literal"));
      }

      @Test
      void sysEffectWithNoArgs_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    sys.receive("Input")
                    sys.effect()
                    """));
        assertEquals(RuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY, ex.getRuleType());
        assertTrue(ex.getMessage().contains("1-2 positional"));
      }

      @Test
      void sysEffectWithTooManyArgs_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    sys.receive("Input")
                    sys.effect("A", {}, "extra")
                    """));
        assertEquals(RuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY, ex.getRuleType());
        assertTrue(ex.getMessage().contains("1-2 positional"));
      }

      @Test
      void chainByWithVariableArg_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    sys.receive("Input")
                    port = "MyPort"
                    sys.effect("Output", {}).by(port)
                    """));
        assertEquals(RuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY, ex.getRuleType());
        assertTrue(ex.getMessage().contains("string literal"));
      }

      @Test
      void chainReceiveWithNoArgs_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    sys.receive("Input")
                    sys.effect("Output", {}).receive()
                    """));
        assertEquals(RuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY, ex.getRuleType());
        assertTrue(ex.getMessage().contains(".receive()"));
      }

      @Test
      void chainOnWithoutReceive_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    sys.receive("Input")
                    sys.effect("Output", {}).on("MyPort")
                    """));
        assertEquals(RuleType.MECHANISM_RULE_SYS_FLUENT_API, ex.getRuleType());
        assertTrue(ex.getMessage().contains(".on()"));
      }

      @Test
      void chainWrongOrder_receiveBeforeBy_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    sys.receive("Input")
                    sys.effect("Output", {}).receive("Ack").by("Port")
                    """));
        assertEquals(RuleType.MECHANISM_RULE_SYS_FLUENT_API, ex.getRuleType());
        assertTrue(ex.getMessage().contains("Invalid chain order"));
      }

      @Test
      void chainUnknownMethod_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    sys.receive("Input")
                    sys.effect("Output", {}).then("X")
                    """));
        assertEquals(RuleType.MECHANISM_RULE_SYS_FLUENT_API, ex.getRuleType());
        assertTrue(ex.getMessage().contains("Unknown chain method"));
      }

      @Test
      void chainDuplicateReceive_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    sys.receive("Input")
                    sys.effect("Output", {}).receive("A").receive("B")
                    """));
        assertEquals(RuleType.MECHANISM_RULE_SYS_FLUENT_API, ex.getRuleType());
      }
    }

    @Nested
    class UnknownGlobals {

      @Test
      void unknownGlobal_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    sys.receive("Input")
                    result = unknown_func()
                    sys.effect("Output", {"r": result})
                    """));
        assertEquals(RuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST, ex.getRuleType());
        assertTrue(ex.getMessage().contains("Unknown globals"));
        assertTrue(ex.getMessage().contains("unknown_func"));
      }

      @Test
      void unknownVariable_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    sys.receive("Input")
                    sys.effect("Output", {"val": external_var})
                    """));
        assertEquals(RuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST, ex.getRuleType());
        assertTrue(ex.getMessage().contains("Unknown globals"));
      }
    }

    @Nested
    class SysIdProperty {

      @Test
      void sysId_valid() {
        String rule =
            """
            sys.receive("Input")
            sys.effect("Output", {"mechanismId": sys.id})
            """;
        assertEquals("Input", service.validateStarlarkRule(rule));
      }

      @Test
      void sysUnknownProperty_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    sys.receive("Input")
                    sys.effect("Output", {"name": sys.name})
                    """));
        assertEquals(RuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST, ex.getRuleType());
        assertTrue(ex.getMessage().contains("Unknown sys property"));
        assertTrue(ex.getMessage().contains("sys.name"));
      }

      @Test
      void sysUnknownProperty_version_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateStarlarkRule(
                        """
                    sys.receive("Input")
                    sys.effect("Output", {"v": sys.version})
                    """));
        assertEquals(RuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST, ex.getRuleType());
        assertTrue(ex.getMessage().contains("Unknown sys property"));
      }
    }

    @Nested
    class ExecutionBudget {

      @Test
      void withinBudget_valid() {
        StringBuilder sb = new StringBuilder("sys.receive(\"Trigger\")\n");
        for (int i = 0; i < 199; i++) {
          sb.append("sys.effect(\"E\", {\"i\": ").append(i).append("})\n");
        }
        assertEquals("Trigger", service.validateStarlarkRule(sb.toString()));
      }

      @Test
      void exceedsBudget_rejected() {
        StringBuilder sb = new StringBuilder("sys.receive(\"Trigger\")\n");
        for (int i = 0; i < MechanismService.MAX_RULE_STATEMENTS; i++) {
          sb.append("sys.effect(\"E\", {\"i\": ").append(i).append("})\n");
        }
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class, () -> service.validateStarlarkRule(sb.toString()));
        assertEquals(RuleType.MECHANISM_RULE_STARLARK_BUDGET, ex.getRuleType());
        assertTrue(ex.getMessage().contains("execution budget"));
        assertTrue(ex.getMessage().contains(String.valueOf(MechanismService.MAX_RULE_STATEMENTS)));
      }

      @Test
      void forLoopBody_countsTowardBudget() {
        StringBuilder sb = new StringBuilder("sys.receive(\"Trigger\")\nfor x in range(1):\n");
        for (int i = 0; i < MechanismService.MAX_RULE_STATEMENTS; i++) {
          sb.append("    sys.effect(\"E\", {\"i\": ").append(i).append("})\n");
        }
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class, () -> service.validateStarlarkRule(sb.toString()));
        assertEquals(RuleType.MECHANISM_RULE_STARLARK_BUDGET, ex.getRuleType());
        assertTrue(ex.getMessage().contains("execution budget"));
      }
    }
  }

  // ========================================================================
  // Statement Compliance (non-GSM extension schema)
  // ========================================================================

  @Nested
  class StatementCompliance {

    @Test
    void extensionSchemaViolation_rejected() {
      // Schema with a required tenant-extension property (not GSM-base:
      // structure/function/rule)
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "ExtMech");
      schema.put("type", "object");
      ObjectNode props = schema.putObject("properties");
      props.set("customField", MAPPER.createObjectNode().put("type", "integer"));
      schema.putArray("required").add("customField");

      DefinitionEntity archDef = mock(DefinitionEntity.class);
      when(archDef.getId()).thenReturn(UUID.randomUUID());
      cloud.poesis.sie.defman.entity.ArchetypeEntity archetype =
          mock(cloud.poesis.sie.defman.entity.ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(schema);
      when(archetype.getDefinition()).thenReturn(archDef);

      ObjectNode statement = MAPPER.createObjectNode(); // missing customField

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateStatement(statement, archetype));
      assertEquals(RuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE, ex.getRuleType());
      assertTrue(ex.getMessage().contains("tenant-extended"));
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
    void missingStructure_rejected() {
      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("function", "X");
      stmt.put("rule", "sys.receive(\"A\")\nsys.effect(\"B\", {})");

      assertThrows(RuleViolationException.class, () -> service.buildEntity(def, archetype, stmt));
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
  // AfterCreate (port auto-derivation)
  // ========================================================================

  @Nested
  class AfterCreate {

    @Test
    void derivesEffectorAndReceptor() {
      UUID mechDefId = UUID.randomUUID();
      DefinitionEntity mechDef = mock(DefinitionEntity.class);
      when(mechDef.getId()).thenReturn(mechDefId);

      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("rule", "sys.receive(\"InputType\")\nsys.effect(\"OutputType\", {})");

      MechanismEntity mechanism = mock(MechanismEntity.class);
      when(mechanism.getStatement()).thenReturn(stmt);
      when(mechanism.getDefinition()).thenReturn(mechDef);

      // Stub base typing archetypes
      ArchetypeEntity effArchetype = mockArchetypeWithTitle("EffectorArchetype");
      ArchetypeEntity recArchetype = mockArchetypeWithTitle("ReceptorArchetype");
      when(archetypeService.findInEffectBySchemaTitle("EffectorArchetype"))
          .thenReturn(effArchetype);
      when(archetypeService.findInEffectBySchemaTitle("ReceptorArchetype"))
          .thenReturn(recArchetype);

      // Stub data archetypes
      ArchetypeEntity inputType = mockArchetypeWithTitle("InputType");
      ArchetypeEntity outputType = mockArchetypeWithTitle("OutputType");
      when(archetypeService.findInEffectBySchemaTitle("InputType")).thenReturn(inputType);
      when(archetypeService.findInEffectBySchemaTitle("OutputType")).thenReturn(outputType);

      // No existing ports
      when(effectorService.findAllByMechanismDefinitionId(mechDefId)).thenReturn(List.of());
      when(receptorService.findAllByMechanismDefinitionId(mechDefId)).thenReturn(List.of());

      // Stub new definitions
      DefinitionEntity effDef = mock(DefinitionEntity.class);
      DefinitionEntity recDef = mock(DefinitionEntity.class);
      when(definitionService.create(DefinitionSubjectType.EFFECTOR)).thenReturn(effDef);
      when(definitionService.create(DefinitionSubjectType.RECEPTOR)).thenReturn(recDef);

      // Stub save
      EffectorEntity savedEff = mock(EffectorEntity.class);
      when(savedEff.getId()).thenReturn(UUID.randomUUID());
      when(savedEff.getStatement()).thenReturn(stmt);
      when(effectorService.save(any(EffectorEntity.class))).thenReturn(savedEff);

      ReceptorEntity savedRec = mock(ReceptorEntity.class);
      when(savedRec.getId()).thenReturn(UUID.randomUUID());
      when(savedRec.getStatement()).thenReturn(stmt);
      when(receptorService.save(any(ReceptorEntity.class))).thenReturn(savedRec);

      service.afterCreate(mechanism);

      verify(effectorService).save(any(EffectorEntity.class));
      verify(receptorService).save(any(ReceptorEntity.class));
    }

    @Test
    void baseArchetypesMissing_skips() {
      UUID mechDefId = UUID.randomUUID();
      DefinitionEntity mechDef = mock(DefinitionEntity.class);
      when(mechDef.getId()).thenReturn(mechDefId);

      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("rule", "sys.receive(\"X\")\nsys.effect(\"Y\", {})");

      MechanismEntity mechanism = mock(MechanismEntity.class);
      when(mechanism.getStatement()).thenReturn(stmt);
      when(mechanism.getDefinition()).thenReturn(mechDef);

      when(archetypeService.findInEffectBySchemaTitle("EffectorArchetype")).thenReturn(null);
      when(archetypeService.findInEffectBySchemaTitle("ReceptorArchetype")).thenReturn(null);

      // Should not throw — just skips
      assertDoesNotThrow(() -> service.afterCreate(mechanism));
    }

    @Test
    void dataArchetypeNotFound_skipsPort() {
      UUID mechDefId = UUID.randomUUID();
      DefinitionEntity mechDef = mock(DefinitionEntity.class);
      when(mechDef.getId()).thenReturn(mechDefId);

      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("rule", "sys.receive(\"MissingType\")\nsys.effect(\"AlsoMissing\", {})");

      MechanismEntity mechanism = mock(MechanismEntity.class);
      when(mechanism.getStatement()).thenReturn(stmt);
      when(mechanism.getDefinition()).thenReturn(mechDef);

      ArchetypeEntity effArchetype = mockArchetypeWithTitle("EffectorArchetype");
      ArchetypeEntity recArchetype = mockArchetypeWithTitle("ReceptorArchetype");
      when(archetypeService.findInEffectBySchemaTitle("EffectorArchetype"))
          .thenReturn(effArchetype);
      when(archetypeService.findInEffectBySchemaTitle("ReceptorArchetype"))
          .thenReturn(recArchetype);

      when(archetypeService.findInEffectBySchemaTitle("MissingType")).thenReturn(null);
      when(archetypeService.findInEffectBySchemaTitle("AlsoMissing")).thenReturn(null);

      when(effectorService.findAllByMechanismDefinitionId(mechDefId)).thenReturn(List.of());
      when(receptorService.findAllByMechanismDefinitionId(mechDefId)).thenReturn(List.of());

      assertDoesNotThrow(() -> service.afterCreate(mechanism));
    }

    @Test
    void existingPortDefinition_reused() {
      UUID mechDefId = UUID.randomUUID();
      DefinitionEntity mechDef = mock(DefinitionEntity.class);
      when(mechDef.getId()).thenReturn(mechDefId);

      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("rule", "sys.receive(\"InputType\")\nsys.effect(\"OutputType\", {})");

      MechanismEntity mechanism = mock(MechanismEntity.class);
      when(mechanism.getStatement()).thenReturn(stmt);
      when(mechanism.getDefinition()).thenReturn(mechDef);

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
      when(savedEff.getStatement()).thenReturn(stmt);
      when(effectorService.save(any(EffectorEntity.class))).thenReturn(savedEff);

      ReceptorEntity savedRec = mock(ReceptorEntity.class);
      when(savedRec.getId()).thenReturn(UUID.randomUUID());
      when(savedRec.getStatement()).thenReturn(stmt);
      when(receptorService.save(any(ReceptorEntity.class))).thenReturn(savedRec);

      service.afterCreate(mechanism);

      // Effector should use existing definition, not create new
      verify(effectorService).save(any(EffectorEntity.class));
    }

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
  }

  // ========================================================================
  // GetSubjectType / GetRepository
  // ========================================================================

  @Test
  void getSubjectType_returnsMechanism() {
    assertEquals(DefinitionSubjectType.MECHANISM, service.getSubjectType());
  }
}
