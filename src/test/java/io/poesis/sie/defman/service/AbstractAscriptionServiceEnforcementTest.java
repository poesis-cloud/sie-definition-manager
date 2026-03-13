package io.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.poesis.sie.defman.entity.ArchetypeEntity;
import io.poesis.sie.defman.entity.AscriptionEntity;
import io.poesis.sie.defman.entity.DefinitionEntity;
import io.poesis.sie.defman.repository.DefinitionRepository;
import io.poesis.sie.defman.type.AscriptionStatusType;
import io.poesis.sie.defman.type.DefinitionSubjectType;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AbstractAscriptionServiceEnforcementTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private DefinitionRepository definitionRepo;

    /** Minimal concrete subclass for testing the package-private base method. */
    private AbstractAscriptionService service;

    @BeforeEach
    void setUp() {
        service = new AbstractAscriptionService() {
            @Override
            public DefinitionSubjectType getSubjectType() {
                return DefinitionSubjectType.STRUCTURE;
            }

            @Override
            public AscriptionEntity buildEntity(DefinitionEntity def, ArchetypeEntity arch, JsonNode stmt) {
                return null;
            }

            @Override
            public AscriptionEntity save(AscriptionEntity entity) {
                return null;
            }

            @Override
            public Page<? extends AscriptionEntity> findAll(Pageable pageable) {
                return null;
            }

            @Override
            public Page<? extends AscriptionEntity> findAllByStatus(AscriptionStatusType s, Pageable p) {
                return null;
            }

            @Override
            public List<? extends AscriptionEntity> findAllByDefinitionId(UUID id) {
                return List.of();
            }

            @Override
            public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(UUID id,
                    Collection<AscriptionStatusType> s) {
                return List.of();
            }
        };
        ReflectionTestUtils.setField(service, "definitionRepository", definitionRepo);
    }

    // ========================================================================
    // $gsm:referential enforcement
    // ========================================================================

    @Nested
    class EnforceReferential {

        @Test
        void referentialPropertyExists_valid() {
            UUID refId = UUID.randomUUID();
            DefinitionEntity refDef = mock(DefinitionEntity.class);
            when(refDef.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
            when(definitionRepo.findById(refId)).thenReturn(Optional.of(refDef));

            ObjectNode ann = MAPPER.createObjectNode().put("subjectType", "STRUCTURE");
            ObjectNode propNode = prop("string");
            propNode.set("$gsm:referential", ann);
            ObjectNode archetypeSchema = schemaWithProperty("ownerId", propNode);

            ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
            ObjectNode statement = MAPPER.createObjectNode().put("ownerId", refId.toString());

            assertDoesNotThrow(() -> service.enforceGsmAnnotations(statement, archetype, UUID.randomUUID()));
        }

        @Test
        void referentialPropertyMissing_rejected() {
            UUID refId = UUID.randomUUID();
            when(definitionRepo.findById(refId)).thenReturn(Optional.empty());

            ObjectNode ann = MAPPER.createObjectNode();
            ObjectNode propNode = prop("string");
            propNode.set("$gsm:referential", ann);
            ObjectNode archetypeSchema = schemaWithProperty("ownerId", propNode);

            ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
            ObjectNode statement = MAPPER.createObjectNode().put("ownerId", refId.toString());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.enforceGsmAnnotations(statement, archetype, UUID.randomUUID()));
            assertTrue(ex.getMessage().contains("does not exist"));
        }

        @Test
        void referentialPropertyWrongSubjectType_rejected() {
            UUID refId = UUID.randomUUID();
            DefinitionEntity refDef = mock(DefinitionEntity.class);
            when(refDef.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
            when(definitionRepo.findById(refId)).thenReturn(Optional.of(refDef));

            ObjectNode ann = MAPPER.createObjectNode().put("subjectType", "STRUCTURE");
            ObjectNode propNode = prop("string");
            propNode.set("$gsm:referential", ann);
            ObjectNode archetypeSchema = schemaWithProperty("ownerId", propNode);

            ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
            ObjectNode statement = MAPPER.createObjectNode().put("ownerId", refId.toString());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.enforceGsmAnnotations(statement, archetype, UUID.randomUUID()));
            assertTrue(ex.getMessage().contains("subjectType"));
        }

        @Test
        void referentialPropertyNotUuid_rejected() {
            ObjectNode ann = MAPPER.createObjectNode();
            ObjectNode propNode = prop("string");
            propNode.set("$gsm:referential", ann);
            ObjectNode archetypeSchema = schemaWithProperty("ref", propNode);

            ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
            ObjectNode statement = MAPPER.createObjectNode().put("ref", "not-a-uuid");

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.enforceGsmAnnotations(statement, archetype, UUID.randomUUID()));
            assertTrue(ex.getMessage().contains("not a valid UUID"));
        }
    }

    // ========================================================================
    // $gsm:deprecated enforcement (warning, not error)
    // ========================================================================

    @Nested
    class EnforceDeprecated {

        @Test
        void deprecatedProperty_noError() {
            ObjectNode propNode = prop("string");
            propNode.put("$gsm:deprecated", true);
            ObjectNode archetypeSchema = schemaWithProperty("oldField", propNode);

            ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
            ObjectNode statement = MAPPER.createObjectNode().put("oldField", "value");

            assertDoesNotThrow(() -> service.enforceGsmAnnotations(statement, archetype, UUID.randomUUID()));
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private ObjectNode prop(String type) {
        return MAPPER.createObjectNode().put("type", type);
    }

    private ObjectNode schemaWithProperty(String propName, ObjectNode propNode) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("title", "TestSchema");
        ObjectNode props = schema.putObject("properties");
        props.set(propName, propNode);
        return schema;
    }

    private ArchetypeEntity stubArchetypeWithSchema(ObjectNode schema) {
        ObjectNode statement = MAPPER.createObjectNode();
        statement.set("schema", schema);

        ArchetypeEntity archetype = mock(ArchetypeEntity.class);
        when(archetype.getStatement()).thenReturn(statement);

        DefinitionEntity def = mock(DefinitionEntity.class);
        when(def.getId()).thenReturn(UUID.randomUUID());
        when(archetype.getDefinition()).thenReturn(def);

        return archetype;
    }
}
