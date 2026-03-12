package com.sif.sie.definitionmanager.validator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
import com.sif.sie.definitionmanager.entity.DefinitionEntity;
import com.sif.sie.definitionmanager.entity.DirectiveEntity;
import com.sif.sie.definitionmanager.entity.StructureEntity;
import com.sif.sie.definitionmanager.repository.DirectiveRepository;

@ExtendWith(MockitoExtension.class)
class DirectiveConsistencyValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private DirectiveRepository directiveRepo;

    private DirectiveConsistencyValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DirectiveConsistencyValidator(directiveRepo);
    }

    // ========================================================================
    // No conflict: no siblings
    // ========================================================================

    @Test
    void noSiblings_noConflict() {
        DirectiveEntity directive = stubDirective("ENSURE", "MUST");
        when(directiveRepo.findAllByQualifier_Definition_IdAndPurpose_Definition_IdAndStatusIn(
                any(), any(), any()))
                .thenReturn(List.of());

        assertDoesNotThrow(() -> validator.validate(directive));
    }

    // ========================================================================
    // No conflict: different verbs (non-contradictory)
    // ========================================================================

    @Test
    void differentNonContradictoryVerbs_noConflict() {
        DirectiveEntity directive = stubDirective("ENSURE", "MUST");
        DirectiveEntity sibling = stubDirective("MAXIMIZE", "SHOULD");

        when(directiveRepo.findAllByQualifier_Definition_IdAndPurpose_Definition_IdAndStatusIn(
                any(), any(), any()))
                .thenReturn(List.of(sibling));

        assertDoesNotThrow(() -> validator.validate(directive));
    }

    // ========================================================================
    // Verb contradiction: ENSURE + PREVENT
    // ========================================================================

    @Nested
    class VerbContradiction {

        @Test
        void ensureAndPrevent_contradiction() {
            DirectiveEntity directive = stubDirective("ENSURE", "MUST");
            DirectiveEntity sibling = stubDirective("PREVENT", "MUST");

            when(directiveRepo.findAllByQualifier_Definition_IdAndPurpose_Definition_IdAndStatusIn(
                    any(), any(), any()))
                    .thenReturn(List.of(sibling));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validator.validate(directive));
            assertTrue(ex.getMessage().contains("contradiction"));
            assertTrue(ex.getMessage().contains("ENSURE"));
            assertTrue(ex.getMessage().contains("PREVENT"));
        }

        @Test
        void preventAndEnsure_contradiction() {
            DirectiveEntity directive = stubDirective("PREVENT", "SHOULD");
            DirectiveEntity sibling = stubDirective("ENSURE", "MAY");

            when(directiveRepo.findAllByQualifier_Definition_IdAndPurpose_Definition_IdAndStatusIn(
                    any(), any(), any()))
                    .thenReturn(List.of(sibling));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validator.validate(directive));
            assertTrue(ex.getMessage().contains("contradiction"));
        }
    }

    // ========================================================================
    // Modal contradiction: MUST + MUST_NOT on same verb
    // ========================================================================

    @Nested
    class ModalContradiction {

        @Test
        void mustAndMustNot_contradiction() {
            DirectiveEntity directive = stubDirective("ENSURE", "MUST");
            DirectiveEntity sibling = stubDirective("ENSURE", "MUST_NOT");

            when(directiveRepo.findAllByQualifier_Definition_IdAndPurpose_Definition_IdAndStatusIn(
                    any(), any(), any()))
                    .thenReturn(List.of(sibling));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validator.validate(directive));
            assertTrue(ex.getMessage().contains("modal contradiction"));
        }

        @Test
        void shouldAndShouldNot_contradiction() {
            DirectiveEntity directive = stubDirective("MAXIMIZE", "SHOULD");
            DirectiveEntity sibling = stubDirective("MAXIMIZE", "SHOULD_NOT");

            when(directiveRepo.findAllByQualifier_Definition_IdAndPurpose_Definition_IdAndStatusIn(
                    any(), any(), any()))
                    .thenReturn(List.of(sibling));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validator.validate(directive));
            assertTrue(ex.getMessage().contains("modal contradiction"));
        }

        @Test
        void mustNotAndMust_contradiction() {
            DirectiveEntity directive = stubDirective("MAINTAIN", "MUST_NOT");
            DirectiveEntity sibling = stubDirective("MAINTAIN", "MUST");

            when(directiveRepo.findAllByQualifier_Definition_IdAndPurpose_Definition_IdAndStatusIn(
                    any(), any(), any()))
                    .thenReturn(List.of(sibling));

            assertThrows(IllegalArgumentException.class, () -> validator.validate(directive));
        }

        @Test
        void sameModalSameVerb_noConflict() {
            DirectiveEntity directive = stubDirective("ENSURE", "MUST");
            DirectiveEntity sibling = stubDirective("ENSURE", "MUST");

            when(directiveRepo.findAllByQualifier_Definition_IdAndPurpose_Definition_IdAndStatusIn(
                    any(), any(), any()))
                    .thenReturn(List.of(sibling));

            assertDoesNotThrow(() -> validator.validate(directive));
        }
    }

    // ========================================================================
    // Same Definition ID (self) — skipped
    // ========================================================================

    @Test
    void sameDefinition_skipped() {
        UUID sharedDefId = UUID.randomUUID();
        DirectiveEntity directive = stubDirectiveWithDefId("ENSURE", "MUST", sharedDefId);
        DirectiveEntity sibling = stubDirectiveWithDefId("PREVENT", "MUST", sharedDefId);

        when(directiveRepo.findAllByQualifier_Definition_IdAndPurpose_Definition_IdAndStatusIn(
                any(), any(), any()))
                .thenReturn(List.of(sibling));

        // Same definition ID → sibling is skipped → no contradiction
        assertDoesNotThrow(() -> validator.validate(directive));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Creates a stubbed DirectiveEntity with unique Definition IDs for
     * qualifier/purpose.
     */
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
}
