package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

/**
 * Tests Archetype activation uniqueness (schema.title uniqueness).
 * Complements ArchetypeServiceAllOfChainTest and
 * ArchetypeServiceAnnotationTest.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchetypeServiceActivationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ArchetypeRepository archetypeRepo;

    private ArchetypeService service;

    @BeforeEach
    void setUp() {
        service = new ArchetypeService(
                archetypeRepo,
                mock(JdbcTemplate.class));
    }

    // ========================================================================
    // Activation uniqueness: schema.title (GSM Archetype validation rules)
    // ========================================================================

    @Nested
    class ActivationUniqueness {

        @Test
        void uniqueTitle_valid() {
            UUID thisDefId = UUID.randomUUID();
            ArchetypeEntity entity = stubArchetype("SecurityProperties", thisDefId);

            when(archetypeRepo.findAllByStatusIn(
                    List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
        }

        @Test
        void duplicateTitle_differentDefinition_rejected() {
            UUID thisDefId = UUID.randomUUID();
            UUID otherDefId = UUID.randomUUID();

            ArchetypeEntity entity = stubArchetype("SecurityProperties", thisDefId);
            ArchetypeEntity existing = stubArchetype("SecurityProperties", otherDefId);

            when(archetypeRepo.findAllByStatusIn(
                    List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
                    .thenReturn(List.of(existing));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateActivationUniqueness(entity));
            assertTrue(ex.getMessage().contains("SecurityProperties"));
            assertTrue(ex.getMessage().contains("duplicates"));
        }

        @Test
        void sameTitle_sameDefinition_valid() {
            UUID defId = UUID.randomUUID();

            ArchetypeEntity entity = stubArchetype("SecurityProperties", defId);
            ArchetypeEntity existing = stubArchetype("SecurityProperties", defId);

            when(archetypeRepo.findAllByStatusIn(
                    List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
                    .thenReturn(List.of(existing));

            assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
        }

        @Test
        void emptyTitle_rejected() {
            UUID thisDefId = UUID.randomUUID();
            ArchetypeEntity entity = stubArchetype("", thisDefId);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateActivationUniqueness(entity));
            assertTrue(ex.getMessage().contains("must not be"));
        }

        @Test
        void missingSchema_rejected() {
            UUID thisDefId = UUID.randomUUID();
            ArchetypeEntity entity = stubArchetypeNoSchema(thisDefId);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateActivationUniqueness(entity));
            assertTrue(ex.getMessage().contains("must not be"));
        }

        @Test
        void differentTitle_valid() {
            UUID thisDefId = UUID.randomUUID();
            UUID otherDefId = UUID.randomUUID();

            ArchetypeEntity entity = stubArchetype("SecurityProperties", thisDefId);
            ArchetypeEntity existing = stubArchetype("PerformanceProperties", otherDefId);

            when(archetypeRepo.findAllByStatusIn(
                    List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
                    .thenReturn(List.of(existing));

            assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
        }
    }

    // ========================================================================
    // Identity-bound values (Archetype)
    // ========================================================================

    @Nested
    class IdentityBound {

        @Test
        void schemaTitleExtracted() {
            ArchetypeEntity entity = stubArchetype("SecurityProperties", UUID.randomUUID());
            var values = service.getIdentityBoundValues(entity);

            assertTrue(values.containsKey("schema.title"));
            assertTrue(values.get("schema.title").equals("SecurityProperties"));
        }

        @Test
        void noSchema_emptyMap() {
            ArchetypeEntity entity = stubArchetypeNoSchema(UUID.randomUUID());
            var values = service.getIdentityBoundValues(entity);

            assertTrue(values.isEmpty());
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private ArchetypeEntity stubArchetype(String title, UUID defId) {
        DefinitionEntity def = mock(DefinitionEntity.class);
        when(def.getId()).thenReturn(defId);

        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("title", title);
        ObjectNode stmt = MAPPER.createObjectNode();
        stmt.set("schema", schema);

        ArchetypeEntity entity = mock(ArchetypeEntity.class);
        when(entity.getId()).thenReturn(UUID.randomUUID());
        when(entity.getDefinition()).thenReturn(def);
        when(entity.getStatement()).thenReturn(stmt);

        return entity;
    }

    private ArchetypeEntity stubArchetypeNoSchema(UUID defId) {
        DefinitionEntity def = mock(DefinitionEntity.class);
        when(def.getId()).thenReturn(defId);

        ObjectNode stmt = MAPPER.createObjectNode();

        ArchetypeEntity entity = mock(ArchetypeEntity.class);
        when(entity.getId()).thenReturn(UUID.randomUUID());
        when(entity.getDefinition()).thenReturn(def);
        when(entity.getStatement()).thenReturn(stmt);

        return entity;
    }
}
