package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.repository.EffectorRepository;
import cloud.poesis.sie.defman.repository.MechanismRepository;
import cloud.poesis.sie.defman.repository.ReceptorRepository;
import cloud.poesis.sie.defman.service.MechanismService.PortSignature;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.RuleType;
import jakarta.persistence.EntityManager;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.StarlarkFile;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MechanismServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private MechanismRepository mechanismRepo;

    @Mock
    private EffectorRepository effectorRepo;

    @Mock
    private ReceptorRepository receptorRepo;

    private MechanismService service;

    @BeforeEach
    void setUp() {
        service = new MechanismService(
                mechanismRepo,
                mock(StructureService.class),
                mock(ArchetypeService.class),
                effectorRepo,
                receptorRepo,
                mock(DefinitionService.class),
                mock(AscriptionStatusTransitionService.class),
                mock(AscriptionRepository.class),
                mock(EntityManager.class),
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
        stmt.put("rule", "on(\"X\")\nsys.emit(\"Y\", {})");

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

                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateActivationUniqueness(entity));
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
            void emptyFunction_rejected() {
                UUID structureDefId = UUID.randomUUID();
                UUID thisDefId = UUID.randomUUID();
                MechanismEntity entity = stubMechanism("", structureDefId, thisDefId);

                stubGenerativeModeValid(thisDefId);

                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateActivationUniqueness(entity));
                assertEquals(RuleType.MECHANISM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
                assertTrue(ex.getMessage().contains("must not be empty"));
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
                assertEquals(AscriptionStatusTransitionCascadeType.GOVERNING,
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

                assertTrue(sigs.contains(new PortSignature("receptor", "PaymentRequest")));
                assertTrue(sigs.contains(new PortSignature("effector", "PaymentRecord")));
                assertTrue(sigs.contains(new PortSignature("receptor", "PaymentRecord")));
                assertTrue(sigs.contains(new PortSignature("effector", "PaymentProcessed")));
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

    // ========================================================================
    // Starlark
    // ========================================================================

    @Nested
    class Starlark {

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

        @Nested
        class SyntaxErrors {

            @Test
            void nullRule_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule(null));
                assertEquals(RuleType.MECHANISM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
            }

            @Test
            void emptyRule_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule(""));
                assertEquals(RuleType.MECHANISM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
            }

            @Test
            void blankRule_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("   "));
                assertEquals(RuleType.MECHANISM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
            }

            @Test
            void invalidSyntax_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("def foo(:::"));
                assertEquals(RuleType.MECHANISM_RULE_STARLARK_PARSING, ex.getRuleType());
                assertTrue(ex.getMessage().contains("syntax error"));
            }
        }

        @Nested
        class OnTriggerViolations {

            @Test
            void missingOnTrigger_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("sys.emit(\"X\", {})"));
                assertEquals(RuleType.MECHANISM_RULE_TRIGGER_AS_FIRST_STATEMENT, ex.getRuleType());
                assertTrue(ex.getMessage().contains("on("));
            }

            @Test
            void onWithNoArgs_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on()
                                sys.emit("X", {})
                                """));
                assertEquals(RuleType.MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE, ex.getRuleType());
                assertTrue(ex.getMessage().contains("one positional string argument"));
            }

            @Test
            void onWithVariableArg_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                name = "Foo"
                                on(name)
                                sys.emit("X", {})
                                """));
                assertEquals(RuleType.MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE, ex.getRuleType());
                assertTrue(ex.getMessage().contains("on(") || ex.getMessage().contains("first"));
            }

            @Test
            void onWithEmptyString_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("")
                                sys.emit("X", {})
                                """));
                assertEquals(RuleType.MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE, ex.getRuleType());
                assertTrue(ex.getMessage().contains("empty"));
            }

            @Test
            void multipleOnCalls_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("First")
                                on("Second")
                                """));
                assertEquals(RuleType.MECHANISM_RULE_TRIGGER_AS_UNIQUE_STATEMENT, ex.getRuleType());
                assertTrue(ex.getMessage().contains("exactly one on()"));
            }
        }

        @Nested
        class LoadForbidden {

            @Test
            void loadStatement_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                load("module.bzl", "helper")
                                on("X")
                                sys.emit("Y", {})
                                """));
                assertEquals(RuleType.MECHANISM_RULE_STARLARK_CONSTRUCT_BLACKLIST, ex.getRuleType());
                assertTrue(ex.getMessage().contains("load()"));
            }
        }

        @Nested
        class SysCallValidation {

            @Test
            void sysEmitWithVariableArchetype_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("Input")
                                archetype = "DynamicType"
                                sys.emit(archetype, {})
                                """));
                assertEquals(RuleType.MECHANISM_RULE_SYS_EMIT_METHOD_ARITY, ex.getRuleType());
                assertTrue(ex.getMessage().contains("string literal"));
            }

            @Test
            void sysCreateWithVariableArchetype_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("Input")
                                name = "SomeType"
                                sys.create(name, {})
                                """));
                assertEquals(RuleType.MECHANISM_RULE_SYS_CREATE_METHOD_ARITY, ex.getRuleType());
                assertTrue(ex.getMessage().contains("string literal"));
            }

            @Test
            void sysEmitWithNoArgs_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("Input")
                                sys.emit()
                                """));
                assertEquals(RuleType.MECHANISM_RULE_SYS_EMIT_METHOD_ARITY, ex.getRuleType());
                assertTrue(ex.getMessage().contains("requires at least one"));
            }

            @Test
            void sysModifyWithNoArgs_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("Input")
                                sys.modify()
                                """));
                assertEquals(RuleType.MECHANISM_RULE_SYS_MODIFY_METHOD_ARITY, ex.getRuleType());
                assertTrue(ex.getMessage().contains("requires at least one"));
            }

            @Test
            void sysModifyWithVariableArchetype_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("Input")
                                name = "SomeType"
                                sys.modify(name, {}, {})
                                """));
                assertEquals(RuleType.MECHANISM_RULE_SYS_MODIFY_METHOD_ARITY, ex.getRuleType());
                assertTrue(ex.getMessage().contains("string literal"));
            }

            @Test
            void sysDeleteWithNoArgs_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("Input")
                                sys.delete()
                                """));
                assertEquals(RuleType.MECHANISM_RULE_SYS_DELETE_METHOD_ARITY, ex.getRuleType());
                assertTrue(ex.getMessage().contains("requires at least one"));
            }

            @Test
            void sysDeleteWithVariableArchetype_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("Input")
                                name = "SomeType"
                                sys.delete(name, {})
                                """));
                assertEquals(RuleType.MECHANISM_RULE_SYS_DELETE_METHOD_ARITY, ex.getRuleType());
                assertTrue(ex.getMessage().contains("string literal"));
            }

            @Test
            void sysAcquireWithNoArgs_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("Input")
                                sys.acquire()
                                """));
                assertEquals(RuleType.MECHANISM_RULE_SYS_ACQUIRE_METHOD_ARITY, ex.getRuleType());
                assertTrue(ex.getMessage().contains("requires at least one"));
            }

            @Test
            void sysAcquireWithVariableArchetype_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("Input")
                                name = "SomeType"
                                sys.acquire(name, {})
                                """));
                assertEquals(RuleType.MECHANISM_RULE_SYS_ACQUIRE_METHOD_ARITY, ex.getRuleType());
                assertTrue(ex.getMessage().contains("string literal"));
            }

            @Test
            void responseOnNonEmit_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("Input")
                                sys.create("Output", {}, response="Resp")
                                """));
                assertEquals(RuleType.MECHANISM_RULE_SYS_EMIT_METHOD_RESPONSE, ex.getRuleType());
                assertTrue(ex.getMessage().contains("response"));
            }

            @Test
            void responseNotStringLiteral_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("Input")
                                resp = "Resp"
                                sys.emit("Output", {}, response=resp)
                                """));
                assertEquals(RuleType.MECHANISM_RULE_SYS_EMIT_METHOD_RESPONSE, ex.getRuleType());
                assertTrue(ex.getMessage().contains("string literal"));
            }

            @Test
            void responseEmpty_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("Input")
                                sys.emit("Output", {}, response="")
                                """));
                assertEquals(RuleType.MECHANISM_RULE_SYS_EMIT_METHOD_RESPONSE, ex.getRuleType());
                assertTrue(ex.getMessage().contains("empty"));
            }
        }

        @Nested
        class UnknownGlobals {

            @Test
            void unknownGlobal_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("Input")
                                result = unknown_func()
                                sys.emit("Output", {"r": result})
                                """));
                assertEquals(RuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST, ex.getRuleType());
                assertTrue(ex.getMessage().contains("Unknown globals"));
                assertTrue(ex.getMessage().contains("unknown_func"));
            }

            @Test
            void unknownVariable_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("Input")
                                sys.emit("Output", {"val": external_var})
                                """));
                assertEquals(RuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST, ex.getRuleType());
                assertTrue(ex.getMessage().contains("Unknown globals"));
            }
        }

        @Nested
        class SysIdProperty {

            @Test
            void sysId_valid() {
                String rule = """
                        on("Input")
                        sys.emit("Output", {"mechanismId": sys.id})
                        """;
                assertEquals("Input", service.validateStarlarkRule(rule));
            }

            @Test
            void sysUnknownProperty_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("Input")
                                sys.emit("Output", {"name": sys.name})
                                """));
                assertEquals(RuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST, ex.getRuleType());
                assertTrue(ex.getMessage().contains("Unknown sys property"));
                assertTrue(ex.getMessage().contains("sys.name"));
            }

            @Test
            void sysUnknownProperty_version_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule("""
                                on("Input")
                                sys.emit("Output", {"v": sys.version})
                                """));
                assertEquals(RuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST, ex.getRuleType());
                assertTrue(ex.getMessage().contains("Unknown sys property"));
            }
        }

        @Nested
        class ExecutionBudget {

            @Test
            void withinBudget_valid() {
                StringBuilder sb = new StringBuilder("on(\"Trigger\")\n");
                for (int i = 0; i < 199; i++) {
                    sb.append("sys.emit(\"E\", {\"i\": ").append(i).append("})\n");
                }
                assertEquals("Trigger", service.validateStarlarkRule(sb.toString()));
            }

            @Test
            void exceedsBudget_rejected() {
                StringBuilder sb = new StringBuilder("on(\"Trigger\")\n");
                for (int i = 0; i < MechanismService.MAX_RULE_STATEMENTS; i++) {
                    sb.append("sys.emit(\"E\", {\"i\": ").append(i).append("})\n");
                }
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule(sb.toString()));
                assertEquals(RuleType.MECHANISM_RULE_STARLARK_BUDGET, ex.getRuleType());
                assertTrue(ex.getMessage().contains("execution budget"));
                assertTrue(ex.getMessage().contains(String.valueOf(MechanismService.MAX_RULE_STATEMENTS)));
            }

            @Test
            void forLoopBody_countsTowardBudget() {
                StringBuilder sb = new StringBuilder("on(\"Trigger\")\nfor x in range(1):\n");
                for (int i = 0; i < MechanismService.MAX_RULE_STATEMENTS; i++) {
                    sb.append("    sys.emit(\"E\", {\"i\": ").append(i).append("})\n");
                }
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateStarlarkRule(sb.toString()));
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
            cloud.poesis.sie.defman.entity.ArchetypeEntity archetype = mock(
                    cloud.poesis.sie.defman.entity.ArchetypeEntity.class);
            when(archetype.getStatement()).thenReturn(schema);
            when(archetype.getDefinition()).thenReturn(archDef);

            ObjectNode statement = MAPPER.createObjectNode(); // missing customField

            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateStatement(statement, archetype));
            assertEquals(RuleType.MECHANISM_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE, ex.getRuleType());
            assertTrue(ex.getMessage().contains("tenant-extended"));
        }
    }
}
