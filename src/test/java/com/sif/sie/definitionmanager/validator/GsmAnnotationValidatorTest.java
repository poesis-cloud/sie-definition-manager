package com.sif.sie.definitionmanager.validator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import com.sif.sie.definitionmanager.repository.ArchetypeRepository;
import com.sif.sie.definitionmanager.repository.DefinitionRepository;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;

@ExtendWith(MockitoExtension.class)
class GsmAnnotationValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private DefinitionRepository definitionRepo;

    @Mock
    private ArchetypeRepository archetypeRepo;

    private GsmAnnotationValidator validator;

    @BeforeEach
    void setUp() {
        validator = new GsmAnnotationValidator(definitionRepo, archetypeRepo);
    }

    // ========================================================================
    // Archetype authoring: unknown annotations
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
                    () -> validator.validateArchetypeAnnotations(schema, defId));
        }

        @Test
        void unknownPropertyAnnotation_rejected() {
            ObjectNode propNode = prop("string");
            propNode.put("$gsm:foobar", true);
            ObjectNode schema = schemaWithProperty("x", propNode);

            UUID defId = UUID.randomUUID();
            when(archetypeRepo.findAllByDefinition_IdOrderByTimestampDesc(defId)).thenReturn(List.of());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validator.validateArchetypeAnnotations(schema, defId));
            assertTrue(ex.getMessage().contains("$gsm:foobar"));
        }

        @Test
        void topLevelAnnotationOnProperty_rejected() {
            ObjectNode propNode = prop("string");
            propNode.put("$gsm:sealed", true);
            ObjectNode schema = schemaWithProperty("x", propNode);

            UUID defId = UUID.randomUUID();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validator.validateArchetypeAnnotations(schema, defId));
            assertTrue(ex.getMessage().contains("top-level only"));
        }
    }

    // ========================================================================
    // Archetype authoring: $gsm:queryable
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

            assertDoesNotThrow(() -> validator.validateArchetypeAnnotations(schema, defId));
        }

        @Test
        void queryableObject_rejected() {
            ObjectNode propNode = prop("object");
            propNode.put("$gsm:queryable", true);
            ObjectNode schema = schemaWithProperty("data", propNode);

            UUID defId = UUID.randomUUID();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validator.validateArchetypeAnnotations(schema, defId));
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
                    () -> validator.validateArchetypeAnnotations(schema, defId));
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

            assertDoesNotThrow(() -> validator.validateArchetypeAnnotations(schema, defId));
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
                    () -> validator.validateArchetypeAnnotations(schema, defId));
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
                    () -> validator.validateArchetypeAnnotations(schema, defId));
            assertTrue(ex.getMessage().contains("Too many $gsm:queryable"));
        }
    }

    // ========================================================================
    // Archetype authoring: $gsm:sensitive + $gsm:queryable mutual exclusion
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
                    () -> validator.validateArchetypeAnnotations(schema, defId));
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

            assertDoesNotThrow(() -> validator.validateArchetypeAnnotations(schema, defId));
        }
    }

    // ========================================================================
    // Archetype authoring: $gsm:referential
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

            assertDoesNotThrow(() -> validator.validateArchetypeAnnotations(schema, defId));
        }

        @Test
        void referentialWithInvalidSubjectType_rejected() {
            ObjectNode ann = MAPPER.createObjectNode().put("subjectType", "BANANA");
            ObjectNode propNode = prop("string");
            propNode.set("$gsm:referential", ann);
            ObjectNode schema = schemaWithProperty("ref", propNode);

            UUID defId = UUID.randomUUID();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validator.validateArchetypeAnnotations(schema, defId));
            assertTrue(ex.getMessage().contains("BANANA"));
        }

        @Test
        void referentialNotObject_rejected() {
            ObjectNode propNode = prop("string");
            propNode.put("$gsm:referential", true);
            ObjectNode schema = schemaWithProperty("ref", propNode);

            UUID defId = UUID.randomUUID();

            assertThrows(IllegalArgumentException.class,
                    () -> validator.validateArchetypeAnnotations(schema, defId));
        }
    }

    // ========================================================================
    // Archetype authoring: $gsm:identityBound set immutability
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

            assertDoesNotThrow(() -> validator.validateArchetypeAnnotations(schema, defId));
        }

        @Test
        void sameIdentityBoundSet_valid() {
            UUID defId = UUID.randomUUID();

            // Existing first ascription with same identity-bound set
            ObjectNode existingProp = prop("string");
            existingProp.put("$gsm:identityBound", true);
            ObjectNode existingSchema = schemaWithProperty("purpose", existingProp);
            ArchetypeEntity existing = stubArchetypeWithSchema(existingSchema);
            when(archetypeRepo.findAllByDefinition_IdOrderByTimestampDesc(defId)).thenReturn(List.of(existing));

            // New schema with same identity-bound set
            ObjectNode newProp = prop("string");
            newProp.put("$gsm:identityBound", true);
            ObjectNode newSchema = schemaWithProperty("purpose", newProp);

            assertDoesNotThrow(() -> validator.validateArchetypeAnnotations(newSchema, defId));
        }

        @Test
        void changedIdentityBoundSet_rejected() {
            UUID defId = UUID.randomUUID();

            // Existing first ascription: identity-bound on "purpose"
            ObjectNode existingProp = prop("string");
            existingProp.put("$gsm:identityBound", true);
            ObjectNode existingSchema = schemaWithProperty("purpose", existingProp);
            ArchetypeEntity existing = stubArchetypeWithSchema(existingSchema);
            when(archetypeRepo.findAllByDefinition_IdOrderByTimestampDesc(defId)).thenReturn(List.of(existing));

            // New schema: identity-bound on "name" instead
            ObjectNode newProp = prop("string");
            newProp.put("$gsm:identityBound", true);
            ObjectNode newSchema = schemaWithProperty("name", newProp);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validator.validateArchetypeAnnotations(newSchema, defId));
            assertTrue(ex.getMessage().contains("identityBound set immutability"));
        }
    }

    // ========================================================================
    // Ascription enforcement: $gsm:referential
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

            assertDoesNotThrow(() ->
                    validator.enforceOnAscription(statement, archetype, UUID.randomUUID()));
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
                    () -> validator.enforceOnAscription(statement, archetype, UUID.randomUUID()));
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
                    () -> validator.enforceOnAscription(statement, archetype, UUID.randomUUID()));
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
                    () -> validator.enforceOnAscription(statement, archetype, UUID.randomUUID()));
            assertTrue(ex.getMessage().contains("not a valid UUID"));
        }
    }

    // ========================================================================
    // Ascription enforcement: $gsm:deprecated (warning, not error)
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

            assertDoesNotThrow(() ->
                    validator.enforceOnAscription(statement, archetype, UUID.randomUUID()));
        }
    }

    // ========================================================================
    // Utility: collectIdentityBoundFields
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

            Set<String> result = GsmAnnotationValidator.collectIdentityBoundFields(schema);
            assertEquals(Set.of("alpha", "gamma"), result);
        }

        @Test
        void noProperties_returnsEmpty() {
            ObjectNode schema = MAPPER.createObjectNode();
            Set<String> result = GsmAnnotationValidator.collectIdentityBoundFields(schema);
            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // Clean schema: no annotations at all → valid
    // ========================================================================

    @Test
    void cleanSchema_noAnnotations_valid() {
        ObjectNode schema = schemaWithProperty("env", prop("string"));

        UUID defId = UUID.randomUUID();
        when(archetypeRepo.findAllByDefinition_IdOrderByTimestampDesc(defId)).thenReturn(List.of());

        assertDoesNotThrow(() -> validator.validateArchetypeAnnotations(schema, defId));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** Creates a simple property node with a type. */
    private ObjectNode prop(String type) {
        return MAPPER.createObjectNode().put("type", type);
    }

    /** Creates a schema with a single property. */
    private ObjectNode schemaWithProperty(String propName, ObjectNode propNode) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("title", "TestSchema");
        ObjectNode props = schema.putObject("properties");
        props.set(propName, propNode);
        return schema;
    }

    /** Stubs an ArchetypeEntity whose statement.schema returns the given schema. */
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
