package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.entity.ReceptorEntity;
import cloud.poesis.sie.defman.exception.GsmRuleViolationException;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.repository.ReceptorRepository;
import cloud.poesis.sie.defman.type.AscriptionCascadeType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.GsmRuleType;
import jakarta.persistence.EntityManager;

/**
 * Tests Receptor lifecycle descriptors: identity-bound values,
 * referee references, and cascade target roles.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReceptorServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReceptorService service;

    @BeforeEach
    void setUp() {
        service = new ReceptorService(
                mock(ReceptorRepository.class),
                mock(MechanismService.class),
                mock(ArchetypeService.class),
                mock(DefinitionService.class),
                mock(AscriptionStatusTransitionService.class),
                mock(AscriptionRepository.class),
                mock(EntityManager.class),
                mock(DataProtectionService.class));
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

            ReceptorEntity entity = stubReceptor(mechDefId, archDefId);

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

            ReceptorEntity entity = stubReceptor(mechDefId, archDefId);

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
            assertEquals(AscriptionCascadeType.CONSTITUTIVE,
                    roles.get(DefinitionSubjectType.MECHANISM));
        }
    }

    // ========================================================================
    // Statement Compliance
    // ========================================================================

    @Nested
    class StatementCompliance {

        @Test
        void missingRequiredField_rejected() {
            DefinitionEntity def = mock(DefinitionEntity.class);
            ArchetypeEntity archetype = mock(ArchetypeEntity.class);
            ObjectNode emptyStatement = MAPPER.createObjectNode();

            GsmRuleViolationException ex = assertThrows(
                    GsmRuleViolationException.class,
                    () -> service.buildEntity(def, archetype, emptyStatement));
            assertEquals(GsmRuleType.RECEPTOR_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
            assertTrue(ex.getMessage().contains("mechanism"));
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private ReceptorEntity stubReceptor(UUID mechDefId, UUID archDefId) {
        DefinitionEntity mechDef = mock(DefinitionEntity.class);
        when(mechDef.getId()).thenReturn(mechDefId);
        MechanismEntity mechanism = mock(MechanismEntity.class);
        when(mechanism.getDefinition()).thenReturn(mechDef);

        DefinitionEntity archDef = mock(DefinitionEntity.class);
        when(archDef.getId()).thenReturn(archDefId);
        ArchetypeEntity archetype = mock(ArchetypeEntity.class);
        when(archetype.getDefinition()).thenReturn(archDef);

        ReceptorEntity entity = mock(ReceptorEntity.class);
        when(entity.getMechanism()).thenReturn(mechanism);
        when(entity.getInputArchetype()).thenReturn(archetype);

        return entity;
    }
}
