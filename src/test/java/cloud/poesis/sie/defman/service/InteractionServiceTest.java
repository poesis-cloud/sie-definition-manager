package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.EffectorEntity;
import cloud.poesis.sie.defman.entity.ReceptorEntity;
import cloud.poesis.sie.defman.repository.InteractionRepository;

/**
 * Tests Interaction-specific validation rules:
 * - Effector/Receptor archetype compatibility (GSM Interaction validation
 * rules)
 * - Referee references and identity-bound values
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InteractionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private EffectorService effectorService;

    @Mock
    private ReceptorService receptorService;

    private InteractionService service;

    @BeforeEach
    void setUp() {
        service = new InteractionService(
                mock(InteractionRepository.class),
                effectorService,
                receptorService);
    }

    // ========================================================================
    // Archetype compatibility (GSM Interaction validation rules)
    // ========================================================================

    @Nested
    class ArchetypeCompatibility {

        @Test
        void matchingArchetypes_valid() {
            UUID archetypeDefId = UUID.randomUUID();
            UUID effectorId = UUID.randomUUID();
            UUID receptorId = UUID.randomUUID();

            EffectorEntity effector = stubEffectorWithArchetypeDefId(effectorId, archetypeDefId);
            ReceptorEntity receptor = stubReceptorWithArchetypeDefId(receptorId, archetypeDefId);

            when(effectorService.findEntityById(effectorId)).thenReturn(effector);
            when(receptorService.findEntityById(receptorId)).thenReturn(receptor);

            ObjectNode statement = MAPPER.createObjectNode();
            statement.put("effector", effectorId.toString());
            statement.put("receptor", receptorId.toString());

            DefinitionEntity definition = mock(DefinitionEntity.class);
            ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);

            assertNotNull(service.buildEntity(definition, archetypeRef, statement));
        }

        @Test
        void mismatchedArchetypes_rejected() {
            UUID effArchetypeDefId = UUID.randomUUID();
            UUID recArchetypeDefId = UUID.randomUUID();
            UUID effectorId = UUID.randomUUID();
            UUID receptorId = UUID.randomUUID();

            EffectorEntity effector = stubEffectorWithArchetypeDefId(effectorId, effArchetypeDefId);
            ReceptorEntity receptor = stubReceptorWithArchetypeDefId(receptorId, recArchetypeDefId);

            when(effectorService.findEntityById(effectorId)).thenReturn(effector);
            when(receptorService.findEntityById(receptorId)).thenReturn(receptor);

            ObjectNode statement = MAPPER.createObjectNode();
            statement.put("effector", effectorId.toString());
            statement.put("receptor", receptorId.toString());

            DefinitionEntity definition = mock(DefinitionEntity.class);
            ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.buildEntity(definition, archetypeRef, statement));
            assertTrue(ex.getMessage().contains("archetype mismatch"));
        }
    }

    // ========================================================================
    // Identity-bound values
    // ========================================================================

    @Nested
    class IdentityBoundValues {

        @Test
        void effectorAndReceptorExtracted() {
            UUID effDefId = UUID.randomUUID();
            UUID recDefId = UUID.randomUUID();

            EffectorEntity effector = mock(EffectorEntity.class);
            DefinitionEntity effDef = mock(DefinitionEntity.class);
            when(effDef.getId()).thenReturn(effDefId);
            when(effector.getDefinition()).thenReturn(effDef);

            ReceptorEntity receptor = mock(ReceptorEntity.class);
            DefinitionEntity recDef = mock(DefinitionEntity.class);
            when(recDef.getId()).thenReturn(recDefId);
            when(receptor.getDefinition()).thenReturn(recDef);

            // Create an InteractionEntity mock directly
            var entity = mock(cloud.poesis.sie.defman.entity.InteractionEntity.class);
            when(entity.getEffector()).thenReturn(effector);
            when(entity.getReceptor()).thenReturn(receptor);

            var values = service.getIdentityBoundValues(entity);

            assertTrue(values.containsKey("effector"));
            assertTrue(values.containsKey("receptor"));
            assertTrue(values.get("effector").equals(effDefId));
            assertTrue(values.get("receptor").equals(recDefId));
        }
    }

    // ========================================================================
    // Referee references
    // ========================================================================

    @Nested
    class RefereeReferences {

        @Test
        void referencesEffectorAndReceptor() {
            EffectorEntity effector = mock(EffectorEntity.class);
            ReceptorEntity receptor = mock(ReceptorEntity.class);

            var entity = mock(cloud.poesis.sie.defman.entity.InteractionEntity.class);
            when(entity.getEffector()).thenReturn(effector);
            when(entity.getReceptor()).thenReturn(receptor);

            var refs = service.getRefereeReferences(entity);

            assertTrue(refs.size() == 2);
            assertTrue(refs.stream().anyMatch(r -> r.label().equals("effector")));
            assertTrue(refs.stream().anyMatch(r -> r.label().equals("receptor")));
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private EffectorEntity stubEffectorWithArchetypeDefId(UUID effectorId, UUID archetypeDefId) {
        DefinitionEntity effArchDef = mock(DefinitionEntity.class);
        when(effArchDef.getId()).thenReturn(archetypeDefId);

        ArchetypeEntity effArchetype = mock(ArchetypeEntity.class);
        when(effArchetype.getDefinition()).thenReturn(effArchDef);

        EffectorEntity effector = mock(EffectorEntity.class);
        when(effector.getId()).thenReturn(effectorId);
        when(effector.getOutputArchetype()).thenReturn(effArchetype);

        return effector;
    }

    private ReceptorEntity stubReceptorWithArchetypeDefId(UUID receptorId, UUID archetypeDefId) {
        DefinitionEntity recArchDef = mock(DefinitionEntity.class);
        when(recArchDef.getId()).thenReturn(archetypeDefId);

        ArchetypeEntity recArchetype = mock(ArchetypeEntity.class);
        when(recArchetype.getDefinition()).thenReturn(recArchDef);

        ReceptorEntity receptor = mock(ReceptorEntity.class);
        when(receptor.getId()).thenReturn(receptorId);
        when(receptor.getInputArchetype()).thenReturn(recArchetype);

        return receptor;
    }
}
