package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.DirectiveEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.repository.DirectiveRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;

/**
 * Tests Directive lifecycle descriptors: identity-bound values,
 * referee references, and cascade target roles.
 * Complements DirectiveServiceConsistencyTest (verb/modal contradiction).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectiveServiceLifecycleTest {

    private DirectiveService service;

    @BeforeEach
    void setUp() {
        service = new DirectiveService(
                mock(DirectiveRepository.class),
                mock(StructureService.class),
                mock(ArchetypeService.class));
    }

    // ========================================================================
    // Identity-bound values
    // ========================================================================

    @Nested
    class IdentityBound {

        @Test
        void structureQualifierPurposeExtracted() {
            UUID structDefId = UUID.randomUUID();
            UUID qualDefId = UUID.randomUUID();
            UUID purposeDefId = UUID.randomUUID();

            DirectiveEntity entity = stubDirective(structDefId, qualDefId, purposeDefId);

            var values = service.getIdentityBoundValues(entity);

            assertEquals(structDefId, values.get("structure"));
            assertEquals(qualDefId, values.get("qualifier"));
            assertEquals(purposeDefId, values.get("purpose"));
        }
    }

    // ========================================================================
    // Referee references
    // ========================================================================

    @Nested
    class RefereeReferences {

        @Test
        void referencesStructureQualifierPurpose() {
            UUID structDefId = UUID.randomUUID();
            UUID qualDefId = UUID.randomUUID();
            UUID purposeDefId = UUID.randomUUID();

            DirectiveEntity entity = stubDirective(structDefId, qualDefId, purposeDefId);

            var refs = service.getRefereeReferences(entity);

            assertEquals(3, refs.size());
            assertTrue(refs.stream().anyMatch(r -> r.label().equals("structure")));
            assertTrue(refs.stream().anyMatch(r -> r.label().equals("qualifier")));
            assertTrue(refs.stream().anyMatch(r -> r.label().equals("purpose")));
        }
    }

    // ========================================================================
    // Cascade target roles
    // ========================================================================

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

    // ========================================================================
    // Helpers
    // ========================================================================

    private DirectiveEntity stubDirective(UUID structDefId, UUID qualDefId, UUID purposeDefId) {
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
