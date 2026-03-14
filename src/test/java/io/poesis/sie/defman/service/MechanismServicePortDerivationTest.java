package io.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.poesis.sie.defman.repository.EffectorRepository;
import io.poesis.sie.defman.repository.MechanismRepository;
import io.poesis.sie.defman.repository.ReceptorRepository;
import io.poesis.sie.defman.service.MechanismService.PortSignature;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.StarlarkFile;

/**
 * Tests for Mechanism port auto-derivation (GSM §Mechanism U3/U4/U12):
 * - collectPortSignatures() AST analysis
 * - Trigger receptor derivation
 * - sys.* effector derivation
 * - Closed-loop (assigned) feedback receptor derivation
 * - response= keyword receptor derivation
 */
class MechanismServicePortDerivationTest {

    private MechanismService service;

    @BeforeEach
    void setUp() {
        service = new MechanismService(
                mock(MechanismRepository.class),
                mock(StructureService.class),
                mock(ArchetypeService.class),
                mock(EffectorRepository.class),
                mock(ReceptorRepository.class));
    }

    private StarlarkFile parse(String rule) {
        return StarlarkFile.parse(ParserInput.fromString(rule, "<test>"), FileOptions.DEFAULT);
    }

    private Set<PortSignature> uniqueSignatures(String rule) {
        return Set.copyOf(service.collectPortSignatures(parse(rule)));
    }

    // ========================================================================
    // Basic: on() trigger produces receptor
    // ========================================================================

    @Nested
    class TriggerReceptor {

        @Test
        void onTrigger_producesReceptor() {
            List<PortSignature> sigs = service.collectPortSignatures(parse("""
                    on("AlertEvent")
                    sys.emit("NotificationEvent", {"level": "warn"})
                    """));

            assertTrue(sigs.stream().anyMatch(
                    s -> "receptor".equals(s.direction()) && "AlertEvent".equals(s.archetypeName())));
        }

        @Test
        void onAssigned_producesReceptor() {
            List<PortSignature> sigs = service.collectPortSignatures(parse("""
                    evt = on("IncomingOrder")
                    sys.emit("OrderAck", {"ok": True})
                    """));

            assertTrue(sigs.stream().anyMatch(
                    s -> "receptor".equals(s.direction()) && "IncomingOrder".equals(s.archetypeName())));
        }
    }

    // ========================================================================
    // sys.emit / sys.create / etc. produce effectors
    // ========================================================================

    @Nested
    class SysCallEffectors {

        @Test
        void sysEmit_producesEffector() {
            Set<PortSignature> sigs = uniqueSignatures("""
                    on("Trigger")
                    sys.emit("OutputEvent", {"data": 1})
                    """);

            assertTrue(sigs.contains(new PortSignature("effector", "OutputEvent")));
        }

        @Test
        void sysCreate_producesEffector() {
            Set<PortSignature> sigs = uniqueSignatures("""
                    on("Trigger")
                    sys.create("NewRecord", {"id": "x"})
                    """);

            assertTrue(sigs.contains(new PortSignature("effector", "NewRecord")));
        }

        @Test
        void sysModify_producesEffector() {
            Set<PortSignature> sigs = uniqueSignatures("""
                    on("Trigger")
                    sys.modify("ExistingRecord", {"id": "x"}, {"val": 42})
                    """);

            assertTrue(sigs.contains(new PortSignature("effector", "ExistingRecord")));
        }

        @Test
        void sysDelete_producesEffector() {
            Set<PortSignature> sigs = uniqueSignatures("""
                    on("Trigger")
                    sys.delete("OldRecord", {"id": "x"})
                    """);

            assertTrue(sigs.contains(new PortSignature("effector", "OldRecord")));
        }

        @Test
        void sysAcquire_producesEffector() {
            Set<PortSignature> sigs = uniqueSignatures("""
                    on("Trigger")
                    sys.acquire("ReadTarget", {"id": "x"})
                    """);

            assertTrue(sigs.contains(new PortSignature("effector", "ReadTarget")));
        }

        @Test
        void allSysMethods_eachProducesEffector() {
            Set<PortSignature> sigs = uniqueSignatures("""
                    on("Trigger")
                    sys.emit("A", {})
                    sys.create("B", {})
                    sys.modify("C", {}, {})
                    sys.delete("D", {})
                    sys.acquire("E", {})
                    """);

            for (String name : List.of("A", "B", "C", "D", "E")) {
                assertTrue(sigs.contains(new PortSignature("effector", name)),
                        "Expected effector for " + name);
            }
        }
    }

    // ========================================================================
    // Closed-loop (assigned) produces feedback receptor
    // ========================================================================

    @Nested
    class ClosedLoopReceptor {

        @Test
        void assignedSysCreate_producesEffectorAndReceptor() {
            Set<PortSignature> sigs = uniqueSignatures("""
                    on("Trigger")
                    result = sys.create("NewRecord", {"id": "x"})
                    """);

            assertTrue(sigs.contains(new PortSignature("effector", "NewRecord")));
            assertTrue(sigs.contains(new PortSignature("receptor", "NewRecord")));
        }

        @Test
        void assignedSysModify_producesEffectorAndReceptor() {
            Set<PortSignature> sigs = uniqueSignatures("""
                    on("Trigger")
                    result = sys.modify("ExistingRecord", {"id": "x"}, {"val": 1})
                    """);

            assertTrue(sigs.contains(new PortSignature("effector", "ExistingRecord")));
            assertTrue(sigs.contains(new PortSignature("receptor", "ExistingRecord")));
        }

        @Test
        void unassignedSysCreate_noFeedbackReceptor() {
            List<PortSignature> sigs = service.collectPortSignatures(parse("""
                    on("Trigger")
                    sys.create("NewRecord", {"id": "x"})
                    """));

            long receptorCount = sigs.stream()
                    .filter(s -> "receptor".equals(s.direction()) && "NewRecord".equals(s.archetypeName()))
                    .count();
            assertEquals(0, receptorCount, "Unassigned sys.create should not produce feedback receptor");
        }
    }

    // ========================================================================
    // response= keyword produces response receptor
    // ========================================================================

    @Nested
    class ResponseKeywordReceptor {

        @Test
        void sysEmitWithResponse_producesResponseReceptor() {
            Set<PortSignature> sigs = uniqueSignatures("""
                    on("Trigger")
                    sys.emit("Request", {}, response="ResponseType")
                    """);

            assertTrue(sigs.contains(new PortSignature("effector", "Request")));
            assertTrue(sigs.contains(new PortSignature("receptor", "ResponseType")));
        }
    }

    // ========================================================================
    // For-loop body ports
    // ========================================================================

    @Nested
    class ForLoopPorts {

        @Test
        void sysCallInsideForLoop_producesEffector() {
            Set<PortSignature> sigs = uniqueSignatures("""
                    on("Batch")
                    items = [1, 2, 3]
                    for item in items:
                        sys.emit("ItemEvent", {"item": item})
                    """);

            assertTrue(sigs.contains(new PortSignature("effector", "ItemEvent")));
        }

        @Test
        void assignedSysCallInForLoop_producesClosedLoop() {
            Set<PortSignature> sigs = uniqueSignatures("""
                    on("Batch")
                    items = [1, 2, 3]
                    for item in items:
                        result = sys.create("ItemRecord", {"item": item})
                    """);

            assertTrue(sigs.contains(new PortSignature("effector", "ItemRecord")));
            assertTrue(sigs.contains(new PortSignature("receptor", "ItemRecord")));
        }
    }

    // ========================================================================
    // Combined: full rule with multiple port types
    // ========================================================================

    @Nested
    class CombinedSignatures {

        @Test
        void complexRule_allPortTypes() {
            Set<PortSignature> sigs = uniqueSignatures("""
                    evt = on("PaymentRequest")
                    record = sys.create("PaymentRecord", {"amount": 100})
                    sys.emit("PaymentProcessed", {"status": "ok"})
                    sys.emit("ExternalNotify", {}, response="NotifyAck")
                    """);

            // Trigger receptor
            assertTrue(sigs.contains(new PortSignature("receptor", "PaymentRequest")));
            // Create effector + feedback receptor (assigned)
            assertTrue(sigs.contains(new PortSignature("effector", "PaymentRecord")));
            assertTrue(sigs.contains(new PortSignature("receptor", "PaymentRecord")));
            // Emit effector (unassigned — no feedback receptor)
            assertTrue(sigs.contains(new PortSignature("effector", "PaymentProcessed")));
            // Emit with response= keyword
            assertTrue(sigs.contains(new PortSignature("effector", "ExternalNotify")));
            assertTrue(sigs.contains(new PortSignature("receptor", "NotifyAck")));
        }

        @Test
        void complexRule_correctCounts() {
            Set<PortSignature> sigs = uniqueSignatures("""
                    evt = on("PaymentRequest")
                    record = sys.create("PaymentRecord", {"amount": 100})
                    sys.emit("PaymentProcessed", {"status": "ok"})
                    """);

            long effectors = sigs.stream().filter(s -> "effector".equals(s.direction())).count();
            long receptors = sigs.stream().filter(s -> "receptor".equals(s.direction())).count();

            assertEquals(2, effectors, "PaymentRecord + PaymentProcessed effectors");
            assertEquals(2, receptors, "PaymentRequest trigger + PaymentRecord feedback receptors");
        }
    }

    // ========================================================================
    // Deduplication: same archetype+direction only once
    // ========================================================================

    @Nested
    class Deduplication {

        @Test
        void duplicateEffectors_deduplicatedInUniqueSet() {
            Set<PortSignature> sigs = uniqueSignatures("""
                    on("Trigger")
                    sys.emit("SameEvent", {"a": 1})
                    sys.emit("SameEvent", {"b": 2})
                    """);

            long effectorCount = sigs.stream()
                    .filter(s -> "effector".equals(s.direction()) && "SameEvent".equals(s.archetypeName()))
                    .count();
            assertEquals(1, effectorCount, "Duplicate effectors should be deduplicated");
        }
    }
}
