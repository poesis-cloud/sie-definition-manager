package io.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.poesis.sie.defman.repository.EffectorRepository;
import io.poesis.sie.defman.repository.MechanismRepository;
import io.poesis.sie.defman.repository.ReceptorRepository;

class MechanismServiceStarlarkTest {

    private MechanismService service;

    @BeforeEach
    void setUp() {
        service = new MechanismService(
                mock(MechanismRepository.class),
                mock(StructureService.class),
                mock(EffectorRepository.class),
                mock(ReceptorRepository.class));
    }

    // ========================================================================
    // Happy paths
    // ========================================================================

    @Nested
    class ValidRules {

        @Test
        void minimalRule_onTriggerOnly() {
            String rule = """
                    on("AlertEvent")
                    sys.emit("NotificationEvent", {"level": "warn"})
                    """;
            String trigger = service.validateStarlarkRule(rule);
            assertEquals("AlertEvent", trigger);
        }

        @Test
        void onWithAssignment() {
            String rule = """
                    evt = on("IncomingOrder")
                    sys.create("OrderRecord", {"id": uuid7(), "data": evt})
                    """;
            String trigger = service.validateStarlarkRule(rule);
            assertEquals("IncomingOrder", trigger);
        }

        @Test
        void multipleSysCalls() {
            String rule = """
                    evt = on("PaymentRequest")
                    result = sys.create("PaymentRecord", {"amount": evt})
                    sys.emit("PaymentProcessed", {"status": "ok"})
                    sys.modify("AccountBalance", {"id": "x"}, {"delta": 100})
                    """;
            String trigger = service.validateStarlarkRule(rule);
            assertEquals("PaymentRequest", trigger);
        }

        @Test
        void withConditionalLogic() {
            String rule = """
                    evt = on("ValidationRequest")
                    if evt:
                        sys.emit("ValidationOk", {"valid": True})
                    """;
            String trigger = service.validateStarlarkRule(rule);
            assertEquals("ValidationRequest", trigger);
        }

        @Test
        void withForLoop() {
            String rule = """
                    evt = on("BatchInput")
                    items = [1, 2, 3]
                    for item in items:
                        sys.emit("ItemProcessed", {"item": item})
                    """;
            assertEquals("BatchInput", service.validateStarlarkRule(rule));
        }

        @Test
        void withLocalVariables() {
            String rule = """
                    evt = on("SomeEvent")
                    x = 42
                    name = "hello"
                    sys.emit("Result", {"val": x, "name": name})
                    """;
            assertEquals("SomeEvent", service.validateStarlarkRule(rule));
        }

        @Test
        void withBuiltinFunctions() {
            String rule = """
                    evt = on("Input")
                    length = len("hello")
                    items = sorted([3, 1, 2])
                    sys.emit("Output", {"len": length, "items": items})
                    """;
            assertEquals("Input", service.validateStarlarkRule(rule));
        }

        @Test
        void withHostFunctions() {
            String rule = """
                    evt = on("Input")
                    ts = now()
                    id = uuid7()
                    matched = fullmatch("^abc.*", "abcdef")
                    found = search("[0-9]+", "abc123")
                    sys.emit("Output", {"ts": ts, "id": id})
                    """;
            assertEquals("Input", service.validateStarlarkRule(rule));
        }

        @Test
        void allSysMethods() {
            String rule = """
                    on("Trigger")
                    sys.emit("A", {})
                    sys.create("B", {})
                    sys.modify("C", {}, {})
                    sys.delete("D", {})
                    sys.acquire("E", {})
                    """;
            assertEquals("Trigger", service.validateStarlarkRule(rule));
        }
    }

    // ========================================================================
    // Syntax errors
    // ========================================================================

    @Nested
    class SyntaxErrors {

        @Test
        void nullRule_rejected() {
            assertThrows(IllegalArgumentException.class, () -> service.validateStarlarkRule(null));
        }

        @Test
        void emptyRule_rejected() {
            assertThrows(IllegalArgumentException.class, () -> service.validateStarlarkRule(""));
        }

        @Test
        void blankRule_rejected() {
            assertThrows(IllegalArgumentException.class, () -> service.validateStarlarkRule("   "));
        }

        @Test
        void invalidSyntax_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateStarlarkRule("def foo(:::"));
            assertTrue(ex.getMessage().contains("syntax error"));
        }
    }

    // ========================================================================
    // on() trigger violations
    // ========================================================================

    @Nested
    class OnTriggerViolations {

        @Test
        void missingOnTrigger_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateStarlarkRule("sys.emit(\"X\", {})"));
            assertTrue(ex.getMessage().contains("on("));
        }

        @Test
        void onWithNoArgs_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateStarlarkRule("""
                            on()
                            sys.emit("X", {})
                            """));
            assertTrue(ex.getMessage().contains("one positional string argument"));
        }

        @Test
        void onWithVariableArg_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateStarlarkRule("""
                            name = "Foo"
                            on(name)
                            sys.emit("X", {})
                            """));
            assertTrue(ex.getMessage().contains("on(") || ex.getMessage().contains("first"));
        }

        @Test
        void onWithEmptyString_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateStarlarkRule("""
                            on("")
                            sys.emit("X", {})
                            """));
            assertTrue(ex.getMessage().contains("empty"));
        }

        @Test
        void multipleOnCalls_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateStarlarkRule("""
                            on("First")
                            on("Second")
                            """));
            assertTrue(ex.getMessage().contains("exactly one on()"));
        }
    }

    // ========================================================================
    // load() forbidden
    // ========================================================================

    @Nested
    class LoadForbidden {

        @Test
        void loadStatement_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateStarlarkRule("""
                            load("module.bzl", "helper")
                            on("X")
                            sys.emit("Y", {})
                            """));
            assertTrue(ex.getMessage().contains("load()"));
        }
    }

    // ========================================================================
    // sys.*() literal argument validation
    // ========================================================================

    @Nested
    class SysCallValidation {

        @Test
        void sysEmitWithVariableArchetype_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateStarlarkRule("""
                            on("Input")
                            archetype = "DynamicType"
                            sys.emit(archetype, {})
                            """));
            assertTrue(ex.getMessage().contains("string literal"));
        }

        @Test
        void sysCreateWithVariableArchetype_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateStarlarkRule("""
                            on("Input")
                            name = "SomeType"
                            sys.create(name, {})
                            """));
            assertTrue(ex.getMessage().contains("string literal"));
        }

        @Test
        void sysEmitWithNoArgs_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateStarlarkRule("""
                            on("Input")
                            sys.emit()
                            """));
            assertTrue(ex.getMessage().contains("requires at least one"));
        }
    }

    // ========================================================================
    // Unknown globals
    // ========================================================================

    @Nested
    class UnknownGlobals {

        @Test
        void unknownGlobal_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateStarlarkRule("""
                            on("Input")
                            result = unknown_func()
                            sys.emit("Output", {"r": result})
                            """));
            assertTrue(ex.getMessage().contains("Unknown globals"));
            assertTrue(ex.getMessage().contains("unknown_func"));
        }

        @Test
        void unknownVariable_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateStarlarkRule("""
                            on("Input")
                            sys.emit("Output", {"val": external_var})
                            """));
            assertTrue(ex.getMessage().contains("Unknown globals"));
        }
    }
}
