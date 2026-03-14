package io.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.poesis.sie.defman.entity.ArchetypeEntity;
import io.poesis.sie.defman.entity.DefinitionEntity;
import io.poesis.sie.defman.entity.EffectorEntity;
import io.poesis.sie.defman.entity.MechanismEntity;
import io.poesis.sie.defman.repository.EffectorRepository;
import io.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import io.poesis.sie.defman.type.DefinitionSubjectType;

/**
 * Tests Effector lifecycle descriptors: identity-bound values,
 * referee references, and cascade target roles.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EffectorServiceTest {

    private EffectorService service;

    @BeforeEach
    void setUp() {
        service = new EffectorService(
                mock(EffectorRepository.class),
                mock(MechanismService.class),
                mock(ArchetypeService.class));
    }

    // ========================================================================
    // Identity-bound values
    // ========================================================================

    @Nested
    class IdentityBound {

        @Test
        void mechanismAndArchetypeExtracted() {
            UUID mechDefId = UUID.randomUUID();
            UUID archDefId = UUID.randomUUID();

            EffectorEntity entity = stubEffector(mechDefId, archDefId);

            Map<String, Object> values = service.getIdentityBoundValues(entity);

            assertEquals(mechDefId, values.get("mechanism"));
            assertEquals(archDefId, values.get("archetype"));
        }
    }

    // ========================================================================
    // Referee references
    // ========================================================================

    @Nested
    class RefereeReferences {

        @Test
        void referencesArchetypeOnly() {
            UUID mechDefId = UUID.randomUUID();
            UUID archDefId = UUID.randomUUID();

            EffectorEntity entity = stubEffector(mechDefId, archDefId);

            var refs = service.getRefereeReferences(entity);

            assertEquals(1, refs.size());
            assertEquals("archetype", refs.get(0).label());
        }
    }

    // ========================================================================
    // Cascade target roles
    // ========================================================================

    @Nested
    class CascadeRoles {

        @Test
        void constitutiveFromMechanism() {
            var roles = service.getCascadeTargetRoles();

            assertEquals(1, roles.size());
            assertTrue(roles.containsKey(DefinitionSubjectType.MECHANISM));
            assertEquals(AscriptionStatusTransitionCascadeType.CONSTITUTIVE,
                    roles.get(DefinitionSubjectType.MECHANISM));
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private EffectorEntity stubEffector(UUID mechDefId, UUID archDefId) {
        DefinitionEntity mechDef = mock(DefinitionEntity.class);
        when(mechDef.getId()).thenReturn(mechDefId);
        MechanismEntity mechanism = mock(MechanismEntity.class);
        when(mechanism.getDefinition()).thenReturn(mechDef);

        DefinitionEntity archDef = mock(DefinitionEntity.class);
        when(archDef.getId()).thenReturn(archDefId);
        ArchetypeEntity archetype = mock(ArchetypeEntity.class);
        when(archetype.getDefinition()).thenReturn(archDef);

        EffectorEntity entity = mock(EffectorEntity.class);
        when(entity.getMechanism()).thenReturn(mechanism);
        when(entity.getOutputArchetype()).thenReturn(archetype);

        return entity;
    }
}
