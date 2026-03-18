package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.exception.GsmRuleViolationException;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.DirectiveEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.repository.DirectiveRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectiveServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private DirectiveRepository directiveRepo;

    private DirectiveService service;

    @BeforeEach
    void setUp() {
        service = new DirectiveService(
                directiveRepo,
                mock(StructureService.class),
                mock(ArchetypeService.class));
    }

    // ========================================================================
    // Consistency (verb/modal contradiction)
    // ========================================================================

    @Nested
    class Consistency {

        @Test
        void noSiblings_noConflict() {
            DirectiveEntity directive = stubDirective("ENSURE", "MUST");
            when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                    any(), any(), any()))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
        }

        @Test
        void differentNonContradictoryVerbs_noConflict() {
            DirectiveEntity directive = stubDirective("ENSURE", "MUST");
            DirectiveEntity sibling = stubDirective("MAXIMIZE", "SHOULD");

            when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                    any(), any(), any()))
                    .thenReturn(List.of(sibling));

            assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
        }

        @Test
        void sameDefinition_skipped() {
            UUID sharedDefId = UUID.randomUUID();
            DirectiveEntity directive = stubDirectiveWithDefId("ENSURE", "MUST", sharedDefId);
            DirectiveEntity sibling = stubDirectiveWithDefId("PREVENT", "MUST", sharedDefId);

            when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                    any(), any(), any()))
                    .thenReturn(List.of(sibling));

            assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
        }

        @Nested
        class VerbContradiction {

            @Test
            void ensureAndPrevent_contradiction() {
                DirectiveEntity directive = stubDirective("ENSURE", "MUST");
                DirectiveEntity sibling = stubDirective("PREVENT", "MUST");

                when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                        any(), any(), any()))
                        .thenReturn(List.of(sibling));

                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateActivationUniqueness(directive));
                assertTrue(ex.getMessage().contains("contradiction"));
                assertTrue(ex.getMessage().contains("ENSURE"));
                assertTrue(ex.getMessage().contains("PREVENT"));
            }

            @Test
            void preventAndEnsure_contradiction() {
                DirectiveEntity directive = stubDirective("PREVENT", "SHOULD");
                DirectiveEntity sibling = stubDirective("ENSURE", "MAY");

                when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                        any(), any(), any()))
                        .thenReturn(List.of(sibling));

                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateActivationUniqueness(directive));
                assertTrue(ex.getMessage().contains("contradiction"));
            }
        }

        @Nested
        class ModalContradiction {

            @Test
            void mustAndMustNot_contradiction() {
                DirectiveEntity directive = stubDirective("ENSURE", "MUST");
                DirectiveEntity sibling = stubDirective("ENSURE", "MUST_NOT");

                when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                        any(), any(), any()))
                        .thenReturn(List.of(sibling));

                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateActivationUniqueness(directive));
                assertTrue(ex.getMessage().contains("modal contradiction"));
            }

            @Test
            void shouldAndShouldNot_contradiction() {
                DirectiveEntity directive = stubDirective("MAXIMIZE", "SHOULD");
                DirectiveEntity sibling = stubDirective("MAXIMIZE", "SHOULD_NOT");

                when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                        any(), any(), any()))
                        .thenReturn(List.of(sibling));

                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateActivationUniqueness(directive));
                assertTrue(ex.getMessage().contains("modal contradiction"));
            }

            @Test
            void mustNotAndMust_contradiction() {
                DirectiveEntity directive = stubDirective("MAINTAIN", "MUST_NOT");
                DirectiveEntity sibling = stubDirective("MAINTAIN", "MUST");

                when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                        any(), any(), any()))
                        .thenReturn(List.of(sibling));

                assertThrows(GsmRuleViolationException.class,
                        () -> service.validateActivationUniqueness(directive));
            }

            @Test
            void sameModalSameVerb_noConflict() {
                DirectiveEntity directive = stubDirective("ENSURE", "MUST");
                DirectiveEntity sibling = stubDirective("ENSURE", "MUST");

                when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                        any(), any(), any()))
                        .thenReturn(List.of(sibling));

                assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
            }
        }

        @Nested
        class ModalPrecedence {

            @Test
            void mustOverridesShould_noException() {
                DirectiveEntity directive = stubDirective("ENSURE", "MUST");
                DirectiveEntity sibling = stubDirective("ENSURE", "SHOULD");

                when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                        any(), any(), any()))
                        .thenReturn(List.of(sibling));

                assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
            }

            @Test
            void shouldOverridesMay_noException() {
                DirectiveEntity directive = stubDirective("MAXIMIZE", "SHOULD");
                DirectiveEntity sibling = stubDirective("MAXIMIZE", "MAY");

                when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                        any(), any(), any()))
                        .thenReturn(List.of(sibling));

                assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
            }

            @Test
            void mustNotOverridesShouldNot_noException() {
                DirectiveEntity directive = stubDirective("MAINTAIN", "MUST_NOT");
                DirectiveEntity sibling = stubDirective("MAINTAIN", "SHOULD_NOT");

                when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                        any(), any(), any()))
                        .thenReturn(List.of(sibling));

                assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
            }

            @Test
            void sameTierDifferentModal_noException() {
                DirectiveEntity directive = stubDirective("OPTIMIZE", "MAY");
                DirectiveEntity sibling = stubDirective("OPTIMIZE", "MAY");

                when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                        any(), any(), any()))
                        .thenReturn(List.of(sibling));

                assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
            }

            @Test
            void differentVerbsDifferentTiers_noConflict() {
                DirectiveEntity directive = stubDirective("ENSURE", "MUST");
                DirectiveEntity sibling = stubDirective("OPTIMIZE", "MAY");

                when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                        any(), any(), any()))
                        .thenReturn(List.of(sibling));

                assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
            }
        }
    }

    // ========================================================================
    // Lifecycle (identity-bound, referee, cascade)
    // ========================================================================

    @Nested
    class Lifecycle {

        @Nested
        class IdentityBound {

            @Test
            void structureQualifierPurposeExtracted() {
                UUID structDefId = UUID.randomUUID();
                UUID qualDefId = UUID.randomUUID();
                UUID purposeDefId = UUID.randomUUID();

                DirectiveEntity entity = stubDirectiveLifecycle(structDefId, qualDefId, purposeDefId);

                var values = service.getIdentityBoundValues(entity);

                assertEquals(structDefId, values.get("structure"));
                assertEquals(qualDefId, values.get("qualifier"));
                assertEquals(purposeDefId, values.get("purpose"));
            }
        }

        @Nested
        class RefereeReferences {

            @Test
            void referencesStructureQualifierPurpose() {
                UUID structDefId = UUID.randomUUID();
                UUID qualDefId = UUID.randomUUID();
                UUID purposeDefId = UUID.randomUUID();

                DirectiveEntity entity = stubDirectiveLifecycle(structDefId, qualDefId, purposeDefId);

                var refs = service.getRefereeReferences(entity);

                assertEquals(3, refs.size());
                assertTrue(refs.stream().anyMatch(r -> r.label().equals("structure")));
                assertTrue(refs.stream().anyMatch(r -> r.label().equals("qualifier")));
                assertTrue(refs.stream().anyMatch(r -> r.label().equals("purpose")));
            }
        }

        @Nested
        class CascadeRoles {

            @Test
            void governingFromStructure() {
                var roles = service.getCascadeTargetRoles();

                assertEquals(1, roles.size());
                assertTrue(roles.containsKey(DefinitionSubjectType.STRUCTURE));
                assertEquals(AscriptionStatusTransitionCascadeType.GOVERNING,
                        roles.get(DefinitionSubjectType.STRUCTURE));
            }
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private DirectiveEntity stubDirective(String verb, String modal) {
        return stubDirectiveWithDefId(verb, modal, UUID.randomUUID());
    }

    private DirectiveEntity stubDirectiveWithDefId(String verb, String modal, UUID definitionId) {
        UUID qualifierDefId = UUID.randomUUID();
        UUID purposeDefId = UUID.randomUUID();

        DefinitionEntity defEntity = mock(DefinitionEntity.class);
        when(defEntity.getId()).thenReturn(definitionId);

        DefinitionEntity qualifierDef = mock(DefinitionEntity.class);
        when(qualifierDef.getId()).thenReturn(qualifierDefId);
        ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
        when(qualifier.getDefinition()).thenReturn(qualifierDef);

        DefinitionEntity purposeDef = mock(DefinitionEntity.class);
        when(purposeDef.getId()).thenReturn(purposeDefId);
        StructureEntity purpose = mock(StructureEntity.class);
        when(purpose.getDefinition()).thenReturn(purposeDef);

        ObjectNode stmt = MAPPER.createObjectNode()
                .put("verb", verb)
                .put("modal", modal);

        DirectiveEntity directive = mock(DirectiveEntity.class);
        when(directive.getDefinition()).thenReturn(defEntity);
        when(directive.getQualifier()).thenReturn(qualifier);
        when(directive.getPurpose()).thenReturn(purpose);
        when(directive.getStatement()).thenReturn(stmt);
        when(directive.getId()).thenReturn(UUID.randomUUID());

        return directive;
    }

    private DirectiveEntity stubDirectiveLifecycle(UUID structDefId, UUID qualDefId, UUID purposeDefId) {
        StructureEntity structure = mock(StructureEntity.class);
        DefinitionEntity structDef = mock(DefinitionEntity.class);
        when(structDef.getId()).thenReturn(structDefId);
        when(structure.getDefinition()).thenReturn(structDef);

        ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
        DefinitionEntity qualDef = mock(DefinitionEntity.class);
        when(qualDef.getId()).thenReturn(qualDefId);
        when(qualifier.getDefinition()).thenReturn(qualDef);

        StructureEntity purpose = mock(StructureEntity.class);
        DefinitionEntity purposeDef = mock(DefinitionEntity.class);
        when(purposeDef.getId()).thenReturn(purposeDefId);
        when(purpose.getDefinition()).thenReturn(purposeDef);

        DirectiveEntity entity = mock(DirectiveEntity.class);
        when(entity.getStructure()).thenReturn(structure);
        when(entity.getQualifier()).thenReturn(qualifier);
        when(entity.getPurpose()).thenReturn(purpose);

        return entity;
    }
}
