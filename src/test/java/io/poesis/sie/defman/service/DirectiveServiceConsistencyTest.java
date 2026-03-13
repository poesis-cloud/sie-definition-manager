package io.poesis.sie.defman.service;

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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.poesis.sie.defman.entity.ArchetypeEntity;
import io.poesis.sie.defman.entity.DefinitionEntity;
import io.poesis.sie.defman.entity.DirectiveEntity;
import io.poesis.sie.defman.entity.StructureEntity;
import io.poesis.sie.defman.repository.DirectiveRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectiveServiceConsistencyTest {

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
    // No conflict: no siblings
    // ========================================================================

    @Test
    void noSiblings_noConflict() {
        DirectiveEntity directive = stubDirective("ENSURE", "MUST");
        when(directiveRepo.findAllByQualifier_Definition_IdAndPurpose_Definition_IdAndStatusIn(
                any(), any(), any()))
                .thenReturn(List.of());

        assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
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

        assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
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
                    () -> service.validateActivationUniqueness(directive));
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
                    () -> service.validateActivationUniqueness(directive));
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
                    () -> service.validateActivationUniqueness(directive));
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
                    () -> service.validateActivationUniqueness(directive));
            assertTrue(ex.getMessage().contains("modal contradiction"));
        }

        @Test
        void mustNotAndMust_contradiction() {
            DirectiveEntity directive = stubDirective("MAINTAIN", "MUST_NOT");
            DirectiveEntity sibling = stubDirective("MAINTAIN", "MUST");

            when(directiveRepo.findAllByQualifier_Definition_IdAndPurpose_Definition_IdAndStatusIn(
                    any(), any(), any()))
                    .thenReturn(List.of(sibling));

            assertThrows(IllegalArgumentException.class,
                    () -> service.validateActivationUniqueness(directive));
        }

        @Test
        void sameModalSameVerb_noConflict() {
            DirectiveEntity directive = stubDirective("ENSURE", "MUST");
            DirectiveEntity sibling = stubDirective("ENSURE", "MUST");

            when(directiveRepo.findAllByQualifier_Definition_IdAndPurpose_Definition_IdAndStatusIn(
                    any(), any(), any()))
                    .thenReturn(List.of(sibling));

            assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
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

        assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
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
}
