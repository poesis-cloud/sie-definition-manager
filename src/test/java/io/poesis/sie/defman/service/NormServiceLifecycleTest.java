package io.poesis.sie.defman.service;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.poesis.sie.defman.entity.ArchetypeEntity;
import io.poesis.sie.defman.entity.DefinitionEntity;
import io.poesis.sie.defman.entity.NormEntity;
import io.poesis.sie.defman.entity.StructureEntity;
import io.poesis.sie.defman.repository.NormRepository;
import io.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import io.poesis.sie.defman.type.DefinitionSubjectType;

/**
 * Tests Norm lifecycle descriptors: identity-bound values,
 * referee references, and cascade target roles.
 * Complements NormServiceCelProfileTest (guard/predicate CEL validation).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NormServiceLifecycleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NormService service;

    @BeforeEach
    void setUp() {
        service = new NormService(
                mock(NormRepository.class),
                mock(StructureService.class),
                mock(ArchetypeService.class));
    }

    // ========================================================================
    // Identity-bound values
    // ========================================================================

    @Nested
    class IdentityBound {

        @Test
        void structureQualifierPredicateExtracted() {
            UUID structDefId = UUID.randomUUID();
            UUID qualDefId = UUID.randomUUID();

            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("predicate", "self.status == 'OK'");

            NormEntity entity = stubNorm(structDefId, qualDefId, stmt);

            var values = service.getIdentityBoundValues(entity);

            assertEquals(structDefId, values.get("structure"));
            assertEquals(qualDefId, values.get("qualifier"));
            assertEquals("self.status == 'OK'", values.get("predicate"));
        }

        @Test
        void noPredicate_structureAndQualifierOnly() {
            UUID structDefId = UUID.randomUUID();
            UUID qualDefId = UUID.randomUUID();

            ObjectNode stmt = MAPPER.createObjectNode();

            NormEntity entity = stubNorm(structDefId, qualDefId, stmt);

            var values = service.getIdentityBoundValues(entity);

            assertEquals(structDefId, values.get("structure"));
            assertEquals(qualDefId, values.get("qualifier"));
            assertEquals(2, values.size());
        }
    }

    // ========================================================================
    // Referee references
    // ========================================================================

    @Nested
    class RefereeReferences {

        @Test
        void referencesStructureAndQualifier() {
            UUID structDefId = UUID.randomUUID();
            UUID qualDefId = UUID.randomUUID();

            NormEntity entity = stubNorm(structDefId, qualDefId, MAPPER.createObjectNode());

            var refs = service.getRefereeReferences(entity);

            assertEquals(2, refs.size());
            assertTrue(refs.stream().anyMatch(r -> r.label().equals("structure")));
            assertTrue(refs.stream().anyMatch(r -> r.label().equals("qualifier")));
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

    private NormEntity stubNorm(UUID structDefId, UUID qualDefId, ObjectNode statement) {
        StructureEntity structure = mock(StructureEntity.class);
        DefinitionEntity structDef = mock(DefinitionEntity.class);
        when(structDef.getId()).thenReturn(structDefId);
        when(structure.getDefinition()).thenReturn(structDef);

        ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
        DefinitionEntity qualDef = mock(DefinitionEntity.class);
        when(qualDef.getId()).thenReturn(qualDefId);
        when(qualifier.getDefinition()).thenReturn(qualDef);

        NormEntity entity = mock(NormEntity.class);
        when(entity.getStructure()).thenReturn(structure);
        when(entity.getQualifier()).thenReturn(qualifier);
        when(entity.getStatement()).thenReturn(statement);

        return entity;
    }
}
