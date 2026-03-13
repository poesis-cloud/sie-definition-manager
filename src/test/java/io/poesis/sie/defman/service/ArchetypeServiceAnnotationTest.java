package io.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
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

import io.poesis.sie.defman.client.SchemaRegistryClient;
import io.poesis.sie.defman.entity.ArchetypeEntity;
import io.poesis.sie.defman.entity.DefinitionEntity;
import io.poesis.sie.defman.repository.ArchetypeRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchetypeServiceAnnotationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ArchetypeRepository archetypeRepo;

    private ArchetypeService service;

    @BeforeEach
    void setUp() {
        service = new ArchetypeService(
                archetypeRepo,
                mock(SchemaRegistryClient.class),
                mock(JdbcTemplate.class));
    }

    // ========================================================================
    // Unknown annotations
    // ========================================================================

    @Nested
    class UnknownAnnotations {

        @Test
        void unknownTopLevelAnnotation_rejected() {
            ObjectNode schema = schemaWithProperty("x", prop("string"));
            schema.put("$gsm:bogus", true);

            UUID defId = UUID.randomUUID();
            when(archetypeRepo.findAllByDefinition_IdOrderByTimestampDesc(defId)).thenReturn(List.of());

            assertThrows(IllegalArgumentException.class,
                    () -> service.validateArchetypeAnnotations(schema, defId));
        }

        @Test
        void unknownPropertyAnnotation_rejected() {
            ObjectNode propNode = prop("string");
            propNode.put("$gsm:foobar", true);
            ObjectNode schema = schemaWithProperty("x", propNode);

            UUID defId = UUID.randomUUID();
            when(archetypeRepo.findAllByDefinition_IdOrderByTimestampDesc(defId)).thenReturn(List.of());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateArchetypeAnnotations(schema, defId));
            assertTrue(ex.getMessage().contains("$gsm:foobar"));
        }

        @Test
        void topLevelAnnotationOnProperty_rejected() {
            ObjectNode propNode = prop("string");
            propNode.put("$gsm:sealed", true);
            ObjectNode schema = schemaWithProperty("x", propNode);

            UUID defId = UUID.randomUUID();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateArchetypeAnnotations(schema, defId));
            assertTrue(ex.getMessage().contains("top-level only"));
        }
    }

    // ========================================================================
    // $gsm:queryable
    // ========================================================================

    @Nested
    class QueryableAnnotation {

        @Test
        void queryableString_valid() {
            ObjectNode propNode = prop("string");
            propNode.put("$gsm:queryable", true);
            ObjectNode schema = schemaWithProperty("env", propNode);

            UUID defId = UUID.randomUUID();
            when(archetypeRepo.findAllByDefinition_IdOrderByTimestampDesc(defId)).thenReturn(List.of());

            assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
        }

        @Test
        void queryableObject_rejected() {
            ObjectNode propNode = prop("object");
            propNode.put("$gsm:queryable", true);
            ObjectNode schema = schemaWithProperty("data", propNode);

            UUID defId = UUID.randomUUID();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateArchetypeAnnotations(schema, defId));
            assertTrue(ex.getMessage().contains("$gsm:queryable"));
            assertTrue(ex.getMessage().contains("object"));
        }

        @Test
        void queryableNoType_rejected() {
            ObjectNode propNode = MAPPER.createObjectNode();
            propNode.put("$gsm:queryable", true);
            ObjectNode schema = schemaWithProperty("x", propNode);

            UUID defId = UUID.randomUUID();

            assertThrows(IllegalArgumentException.class,
                    () -> service.validateArchetypeAnnotations(schema, defId));
        }

        @Test
        void queryableArrayOfScalars_valid() {
            ObjectNode items = MAPPER.createObjectNode().put("type", "string");
            ObjectNode propNode = MAPPER.createObjectNode();
            propNode.put("type", "array");
            propNode.set("items", items);
            propNode.put("$gsm:queryable", true);
            ObjectNode schema = schemaWithProperty("tags", propNode);

            UUID defId = UUID.randomUUID();
            when(archetypeRepo.findAllByDefinition_IdOrderByTimestampDesc(defId)).thenReturn(List.of());

            assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
        }

        @Test
        void queryableArrayOfObjects_rejected() {
            ObjectNode items = MAPPER.createObjectNode().put("type", "object");
            ObjectNode propNode = MAPPER.createObjectNode();
            propNode.put("type", "array");
            propNode.set("items", items);
            propNode.put("$gsm:queryable", true);
            ObjectNode schema = schemaWithProperty("entries", propNode);

            UUID defId = UUID.randomUUID();

            assertThrows(IllegalArgumentException.class,
                    () -> service.validateArchetypeAnnotations(schema, defId));
        }

        @Test
        void tooManyQueryable_rejected() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("title", "TooMany");
            ObjectNode props = schema.putObject("properties");
            for (int i = 0; i < 9; i++) {
                ObjectNode p = props.putObject("p" + i);
                p.put("type", "string");
                p.put("$gsm:queryable", true);
            }

            UUID defId = UUID.randomUUID();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateArchetypeAnnotations(schema, defId));
            assertTrue(ex.getMessage().contains("Too many $gsm:queryable"));
        }
    }

    // ========================================================================
    // $gsm:sensitive + $gsm:queryable mutual exclusion
    // ========================================================================

    @Nested
    class SensitiveQueryableMutualExclusion {

        @Test
        void sensitiveAndQueryable_rejected() {
            ObjectNode propNode = prop("string");
            propNode.put("$gsm:sensitive", true);
            propNode.put("$gsm:queryable", true);
            ObjectNode schema = schemaWithProperty("secret", propNode);

            UUID defId = UUID.randomUUID();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateArchetypeAnnotations(schema, defId));
            assertTrue(ex.getMessage().contains("$gsm:sensitive"));
            assertTrue(ex.getMessage().contains("$gsm:queryable"));
        }

        @Test
        void sensitiveWithoutQueryable_valid() {
            ObjectNode propNode = prop("string");
            propNode.put("$gsm:sensitive", true);
            ObjectNode schema = schemaWithProperty("password", propNode);

            UUID defId = UUID.randomUUID();
            when(archetypeRepo.findAllByDefinition_IdOrderByTimestampDesc(defId)).thenReturn(List.of());

            assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
        }
    }

    // ========================================================================
    // $gsm:referential (authoring-time validation)
    // ========================================================================

    @Nested
    class ReferentialAnnotation {

        @Test
        void referentialWithValidSubjectType_valid() {
            ObjectNode ann = MAPPER.createObjectNode().put("subjectType", "STRUCTURE");
            ObjectNode propNode = prop("string");
            propNode.set("$gsm:referential", ann);
            ObjectNode schema = schemaWithProperty("ownerId", propNode);

            UUID defId = UUID.randomUUID();
            when(archetypeRepo.findAllByDefinition_IdOrderByTimestampDesc(defId)).thenReturn(List.of());

            assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
        }

        @Test
        void referentialWithInvalidSubjectType_rejected() {
            ObjectNode ann = MAPPER.createObjectNode().put("subjectType", "BANANA");
            ObjectNode propNode = prop("string");
            propNode.set("$gsm:referential", ann);
            ObjectNode schema = schemaWithProperty("ref", propNode);

            UUID defId = UUID.randomUUID();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateArchetypeAnnotations(schema, defId));
            assertTrue(ex.getMessage().contains("BANANA"));
        }

        @Test
        void referentialNotObject_rejected() {
            ObjectNode propNode = prop("string");
            propNode.put("$gsm:referential", true);
            ObjectNode schema = schemaWithProperty("ref", propNode);

            UUID defId = UUID.randomUUID();

            assertThrows(IllegalArgumentException.class,
                    () -> service.validateArchetypeAnnotations(schema, defId));
        }
    }

    // ========================================================================
    // $gsm:identityBound set immutability
    // ========================================================================

    @Nested
    class IdentityBoundSetImmutability {

        @Test
        void firstAscription_noCheck() {
            ObjectNode propNode = prop("string");
            propNode.put("$gsm:identityBound", true);
            ObjectNode schema = schemaWithProperty("purpose", propNode);

            UUID defId = UUID.randomUUID();
            when(archetypeRepo.findAllByDefinition_IdOrderByTimestampDesc(defId)).thenReturn(List.of());

            assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
        }

        @Test
        void sameIdentityBoundSet_valid() {
            UUID defId = UUID.randomUUID();

            ObjectNode existingProp = prop("string");
            existingProp.put("$gsm:identityBound", true);
            ObjectNode existingSchema = schemaWithProperty("purpose", existingProp);
            ArchetypeEntity existing = stubArchetypeWithSchema(existingSchema);
            when(archetypeRepo.findAllByDefinition_IdOrderByTimestampDesc(defId)).thenReturn(List.of(existing));

            ObjectNode newProp = prop("string");
            newProp.put("$gsm:identityBound", true);
            ObjectNode newSchema = schemaWithProperty("purpose", newProp);

            assertDoesNotThrow(() -> service.validateArchetypeAnnotations(newSchema, defId));
        }

        @Test
        void changedIdentityBoundSet_rejected() {
            UUID defId = UUID.randomUUID();

            ObjectNode existingProp = prop("string");
            existingProp.put("$gsm:identityBound", true);
            ObjectNode existingSchema = schemaWithProperty("purpose", existingProp);
            ArchetypeEntity existing = stubArchetypeWithSchema(existingSchema);
            when(archetypeRepo.findAllByDefinition_IdOrderByTimestampDesc(defId)).thenReturn(List.of(existing));

            ObjectNode newProp = prop("string");
            newProp.put("$gsm:identityBound", true);
            ObjectNode newSchema = schemaWithProperty("name", newProp);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateArchetypeAnnotations(newSchema, defId));
            assertTrue(ex.getMessage().contains("identityBound set immutability"));
        }
    }

    // ========================================================================
    // collectIdentityBoundFields (static utility)
    // ========================================================================

    @Nested
    class CollectIdentityBoundFields {

        @Test
        void collectsAnnotatedFields() {
            ObjectNode p1 = prop("string");
            p1.put("$gsm:identityBound", true);
            ObjectNode p2 = prop("string");
            ObjectNode p3 = prop("number");
            p3.put("$gsm:identityBound", true);

            ObjectNode schema = MAPPER.createObjectNode();
            ObjectNode props = schema.putObject("properties");
            props.set("alpha", p1);
            props.set("beta", p2);
            props.set("gamma", p3);

            Set<String> result = ArchetypeService.collectIdentityBoundFields(schema);
            assertEquals(Set.of("alpha", "gamma"), result);
        }

        @Test
        void noProperties_returnsEmpty() {
            ObjectNode schema = MAPPER.createObjectNode();
            Set<String> result = ArchetypeService.collectIdentityBoundFields(schema);
            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // Clean schema: no annotations → valid
    // ========================================================================

    @Test
    void cleanSchema_noAnnotations_valid() {
        ObjectNode schema = schemaWithProperty("env", prop("string"));

        UUID defId = UUID.randomUUID();
        when(archetypeRepo.findAllByDefinition_IdOrderByTimestampDesc(defId)).thenReturn(List.of());

        assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
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
