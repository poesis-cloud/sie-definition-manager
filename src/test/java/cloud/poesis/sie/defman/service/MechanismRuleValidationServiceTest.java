package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.service.MechanismRuleValidationService.PortSignature;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
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
class MechanismRuleValidationServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private ArchetypeService archetypeService;

  private MechanismRuleValidationService service;

  @BeforeEach
  void setUp() {
    service = new MechanismRuleValidationService(archetypeService);
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private Set<PortSignature> uniqueSignatures(String rule) {
    return Set.copyOf(service.collectPortSignatures(rule));
  }

  // ========================================================================
  // PortDerivation (signature collection from AST)
  // ========================================================================

  @Nested
  class PortDerivation {

    @Nested
    class TriggerReceptor {

      @Test
      void onTrigger_producesReceptor() {
        List<PortSignature> sigs =
            service.collectPortSignatures(
                """
                sys.receive("AlertEvent")
                sys.effect("NotificationEvent", {"level": "warn"})
                """);

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
                """
                evt = sys.receive("IncomingOrder")
                sys.effect("OrderAck", {"ok": True})
                """);

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
                """
                sys.receive("Trigger")
                sys.effect("NewRecord", {"id": "x"})
                """);

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
  // Starlark validation
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
      void invalidSyntax_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class, () -> service.validateStarlarkRule("def foo(:::"));
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_PARSING, ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_TRIGGER_AS_FIRST_STATEMENT,
            ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE,
            ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE,
            ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE,
            ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_TRIGGER_AS_UNIQUE_STATEMENT,
            ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_CONSTRUCT_BLACKLIST,
            ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY, ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY, ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY, ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY, ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY, ex.getRuleType());
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
        assertEquals(AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API, ex.getRuleType());
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
        assertEquals(AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API, ex.getRuleType());
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
        assertEquals(AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API, ex.getRuleType());
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
        assertEquals(AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API, ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST,
            ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST,
            ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST,
            ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST,
            ex.getRuleType());
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
        for (int i = 0; i < MechanismRuleValidationService.MAX_RULE_STATEMENTS; i++) {
          sb.append("sys.effect(\"E\", {\"i\": ").append(i).append("})\n");
        }
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class, () -> service.validateStarlarkRule(sb.toString()));
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_BUDGET, ex.getRuleType());
        assertTrue(ex.getMessage().contains("execution budget"));
        assertTrue(
            ex.getMessage()
                .contains(String.valueOf(MechanismRuleValidationService.MAX_RULE_STATEMENTS)));
      }

      @Test
      void forLoopBody_countsTowardBudget() {
        StringBuilder sb = new StringBuilder("sys.receive(\"Trigger\")\nfor x in range(1):\n");
        for (int i = 0; i < MechanismRuleValidationService.MAX_RULE_STATEMENTS; i++) {
          sb.append("    sys.effect(\"E\", {\"i\": ").append(i).append("})\n");
        }
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class, () -> service.validateStarlarkRule(sb.toString()));
        assertEquals(
            AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_BUDGET, ex.getRuleType());
        assertTrue(ex.getMessage().contains("execution budget"));
      }
    }
  }

  // ========================================================================
  // Receive chain .on() validation
  // ========================================================================

  @Nested
  class ReceiveChainOnValidation {

    @Test
    void receiveOnWithNoArgs_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateStarlarkRule(
                      """
                  sys.receive("Trigger").on()
                  sys.effect("X", {})
                  """));
      assertEquals(
          AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY, ex.getRuleType());
      assertTrue(ex.getMessage().contains(".on()"));
      assertTrue(ex.getMessage().contains("exactly 1 argument"));
    }

    @Test
    void receiveOnWithNonStringArg_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateStarlarkRule(
                      """
                  name = "Port"
                  sys.receive("Trigger").on(name)
                  sys.effect("X", {})
                  """));
      assertEquals(
          AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY, ex.getRuleType());
      assertTrue(ex.getMessage().contains("string literal"));
    }

    @Test
    void receiveUnknownChainMethod_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateStarlarkRule(
                      """
                  sys.receive("Trigger").then("X")
                  sys.effect("Y", {})
                  """));
      assertEquals(AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API, ex.getRuleType());
      assertTrue(ex.getMessage().contains("Unknown chain method on sys.receive()"));
      assertTrue(ex.getMessage().contains("then"));
    }

    @Test
    void receiveWithValidOn_accepted() {
      String trigger =
          service.validateStarlarkRule(
              """
              sys.receive("Trigger").on("ReceptorPort")
              sys.effect("X", {})
              """);
      assertEquals("Trigger", trigger);
    }
  }

  // ========================================================================
  // Dict literal conformance (V11)
  // ========================================================================

  @Nested
  class DictLiteralConformance {

    @Test
    void dictKeyInSchema_noError() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "PersonArchetype");
      ObjectNode props = schema.putObject("properties");
      props.putObject("name").put("type", "string");
      props.putObject("age").put("type", "integer");

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(schema);
      DefinitionEntity archDef = mock(DefinitionEntity.class);
      when(archDef.getId()).thenReturn(UUID.randomUUID());
      when(archetype.getDefinition()).thenReturn(archDef);
      when(archetypeService.findInEffectBySchemaTitle("PersonArchetype")).thenReturn(archetype);

      assertDoesNotThrow(
          () ->
              service.validateStarlarkRule(
                  """
                  sys.receive("Trigger")
                  sys.effect("PersonArchetype", {"name": "John", "age": 30})
                  """));
    }

    @Test
    void dictKeyNotInSchema_stillValid() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "PersonArchetype");
      ObjectNode props = schema.putObject("properties");
      props.putObject("name").put("type", "string");

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(schema);
      DefinitionEntity archDef = mock(DefinitionEntity.class);
      when(archDef.getId()).thenReturn(UUID.randomUUID());
      when(archetype.getDefinition()).thenReturn(archDef);
      when(archetypeService.findInEffectBySchemaTitle("PersonArchetype")).thenReturn(archetype);

      assertDoesNotThrow(
          () ->
              service.validateStarlarkRule(
                  """
                  sys.receive("Trigger")
                  sys.effect("PersonArchetype", {"name": "ok", "unknownField": True})
                  """));
    }

    @Test
    void archetypeNotInEffect_skipsValidation() {
      when(archetypeService.findInEffectBySchemaTitle("SomeType")).thenReturn(null);
      assertDoesNotThrow(
          () ->
              service.validateStarlarkRule(
                  """
                  sys.receive("Trigger")
                  sys.effect("SomeType", {"key": "val"})
                  """));
    }

    @Test
    void schemaWithoutProperties_skipsValidation() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "BareType");

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(schema);
      DefinitionEntity archDef = mock(DefinitionEntity.class);
      when(archDef.getId()).thenReturn(UUID.randomUUID());
      when(archetype.getDefinition()).thenReturn(archDef);
      when(archetypeService.findInEffectBySchemaTitle("BareType")).thenReturn(archetype);

      assertDoesNotThrow(
          () ->
              service.validateStarlarkRule(
                  """
                  sys.receive("Trigger")
                  sys.effect("BareType", {"key": "val"})
                  """));
    }

    @Test
    void singleArgEffect_skipsDictCheck() {
      assertDoesNotThrow(
          () ->
              service.validateStarlarkRule(
                  """
                  sys.receive("Trigger")
                  sys.effect("SomeArch")
                  """));
    }

    @Test
    void dictLiteralInForBody_validated() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "ItemEvent");
      ObjectNode props = schema.putObject("properties");
      props.putObject("item").put("type", "integer");

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(schema);
      DefinitionEntity archDef = mock(DefinitionEntity.class);
      when(archDef.getId()).thenReturn(UUID.randomUUID());
      when(archetype.getDefinition()).thenReturn(archDef);
      when(archetypeService.findInEffectBySchemaTitle("ItemEvent")).thenReturn(archetype);

      assertDoesNotThrow(
          () ->
              service.validateStarlarkRule(
                  """
                  sys.receive("Trigger")
                  items = [1, 2]
                  for x in items:
                      sys.effect("ItemEvent", {"item": x, "bonus": True})
                  """));
    }

    @Test
    void dictLiteralInAssignment_validated() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "ResultType");
      ObjectNode props = schema.putObject("properties");
      props.putObject("data").put("type", "string");

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(schema);
      DefinitionEntity archDef = mock(DefinitionEntity.class);
      when(archDef.getId()).thenReturn(UUID.randomUUID());
      when(archetype.getDefinition()).thenReturn(archDef);
      when(archetypeService.findInEffectBySchemaTitle("ResultType")).thenReturn(archetype);

      assertDoesNotThrow(
          () ->
              service.validateStarlarkRule(
                  """
                  sys.receive("Trigger")
                  result = sys.effect("ResultType", {"data": "ok"})
                  """));
    }
  }

  // ========================================================================
  // Collect unknown globals — for-loop body coverage
  // ========================================================================

  @Nested
  class UnknownGlobalsForLoopBody {

    @Test
    void unknownInForBodyAssignment_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateStarlarkRule(
                      """
                  sys.receive("Trigger")
                  data = [1, 2, 3]
                  for x in data:
                      result = forbidden_func()
                      sys.effect("Out", {"val": result})
                  """));
      assertEquals(
          AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST, ex.getRuleType());
      assertTrue(ex.getMessage().contains("forbidden_func"));
    }

    @Test
    void unknownInForCollection_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateStarlarkRule(
                      """
                  sys.receive("Trigger")
                  for x in external_list:
                      sys.effect("Out", {"val": x})
                  """));
      assertEquals(
          AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST, ex.getRuleType());
      assertTrue(ex.getMessage().contains("external_list"));
    }

    @Test
    void unknownInListExpression_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateStarlarkRule(
                      """
                  sys.receive("Trigger")
                  sys.effect("Out", {"list": [forbidden_var]})
                  """));
      assertEquals(
          AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST, ex.getRuleType());
      assertTrue(ex.getMessage().contains("forbidden_var"));
    }

    @Test
    void unknownInDictExpression_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateStarlarkRule(
                      """
                  sys.receive("Trigger")
                  sys.effect("Out", {forbidden_key: "val"})
                  """));
      assertEquals(
          AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST, ex.getRuleType());
      assertTrue(ex.getMessage().contains("forbidden_key"));
    }
  }
}
