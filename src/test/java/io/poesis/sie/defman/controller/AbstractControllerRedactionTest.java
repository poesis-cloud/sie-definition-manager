package io.poesis.sie.defman.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.poesis.sie.defman.dto.AscriptionDto;
import io.poesis.sie.defman.entity.ArchetypeEntity;
import io.poesis.sie.defman.entity.AscriptionEntity;
import io.poesis.sie.defman.entity.DefinitionEntity;
import io.poesis.sie.defman.type.AscriptionStatusType;
import io.poesis.sie.defman.type.DefinitionSubjectType;

/**
 * Tests for {@link AbstractController#toAscriptionDto} — specifically
 * the $gsm:sensitive redaction logic (GSM §8 V8).
 */
class AbstractControllerRedactionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Minimal concrete subclass exposing the protected method. */
    private final AbstractController controller = new AbstractController() {};

    // ========================================================================
    // $gsm:sensitive redaction (GSM §8 V8)
    // ========================================================================

    @Nested
    class SensitiveRedaction {

        @Test
        void sensitiveProperty_redacted() {
            ObjectNode archetypeSchema = MAPPER.createObjectNode();
            archetypeSchema.put("title", "TestSchema");
            ObjectNode props = archetypeSchema.putObject("properties");
            ObjectNode passwordProp = props.putObject("password");
            passwordProp.put("type", "string");
            passwordProp.put("$gsm:sensitive", true);
            ObjectNode nameProp = props.putObject("name");
            nameProp.put("type", "string");

            ObjectNode statement = MAPPER.createObjectNode()
                    .put("password", "s3cret")
                    .put("name", "test-service");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            AscriptionDto dto = controller.toAscriptionDto(DefinitionSubjectType.STRUCTURE, entity);

            assertEquals("[REDACTED]", dto.statement().get("password").asText());
            assertEquals("test-service", dto.statement().get("name").asText());
        }

        @Test
        void multipleSensitiveProperties_allRedacted() {
            ObjectNode archetypeSchema = MAPPER.createObjectNode();
            archetypeSchema.put("title", "TestSchema");
            ObjectNode props = archetypeSchema.putObject("properties");
            ObjectNode p1 = props.putObject("apiKey");
            p1.put("type", "string");
            p1.put("$gsm:sensitive", true);
            ObjectNode p2 = props.putObject("secret");
            p2.put("type", "string");
            p2.put("$gsm:sensitive", true);
            ObjectNode p3 = props.putObject("env");
            p3.put("type", "string");

            ObjectNode statement = MAPPER.createObjectNode()
                    .put("apiKey", "key-123")
                    .put("secret", "top-secret")
                    .put("env", "prod");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            AscriptionDto dto = controller.toAscriptionDto(DefinitionSubjectType.STRUCTURE, entity);

            assertEquals("[REDACTED]", dto.statement().get("apiKey").asText());
            assertEquals("[REDACTED]", dto.statement().get("secret").asText());
            assertEquals("prod", dto.statement().get("env").asText());
        }

        @Test
        void noSensitiveProperties_unchanged() {
            ObjectNode archetypeSchema = MAPPER.createObjectNode();
            archetypeSchema.put("title", "TestSchema");
            ObjectNode props = archetypeSchema.putObject("properties");
            ObjectNode nameProp = props.putObject("name");
            nameProp.put("type", "string");

            ObjectNode statement = MAPPER.createObjectNode()
                    .put("name", "test-service");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            AscriptionDto dto = controller.toAscriptionDto(DefinitionSubjectType.STRUCTURE, entity);

            assertEquals("test-service", dto.statement().get("name").asText());
        }

        @Test
        void noSchemaInArchetype_passThrough() {
            ObjectNode archetypeStatement = MAPPER.createObjectNode();
            // no "schema" key

            ObjectNode statement = MAPPER.createObjectNode()
                    .put("password", "s3cret");

            AscriptionEntity entity = stubEntityWithCustomArchetypeStatement(archetypeStatement, statement);

            AscriptionDto dto = controller.toAscriptionDto(DefinitionSubjectType.STRUCTURE, entity);

            assertEquals("s3cret", dto.statement().get("password").asText());
        }

        @Test
        void sensitivePropertyNotInStatement_noError() {
            ObjectNode archetypeSchema = MAPPER.createObjectNode();
            archetypeSchema.put("title", "TestSchema");
            ObjectNode props = archetypeSchema.putObject("properties");
            ObjectNode passwordProp = props.putObject("password");
            passwordProp.put("type", "string");
            passwordProp.put("$gsm:sensitive", true);

            // Statement does NOT contain "password"
            ObjectNode statement = MAPPER.createObjectNode()
                    .put("name", "test");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            AscriptionDto dto = controller.toAscriptionDto(DefinitionSubjectType.STRUCTURE, entity);

            assertNull(dto.statement().get("password"));
            assertEquals("test", dto.statement().get("name").asText());
        }

        @Test
        void nullStatement_passThrough() {
            ObjectNode archetypeSchema = MAPPER.createObjectNode();
            archetypeSchema.put("title", "TestSchema");

            AscriptionEntity entity = stubEntity(archetypeSchema, null);

            AscriptionDto dto = controller.toAscriptionDto(DefinitionSubjectType.STRUCTURE, entity);

            assertNull(dto.statement());
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AscriptionEntity stubEntity(ObjectNode archetypeSchema, ObjectNode statement) {
        ObjectNode archetypeStmt = MAPPER.createObjectNode();
        archetypeStmt.set("schema", archetypeSchema);
        return stubEntityWithCustomArchetypeStatement(archetypeStmt, statement);
    }

    private AscriptionEntity stubEntityWithCustomArchetypeStatement(ObjectNode archetypeStmt, ObjectNode statement) {
        DefinitionEntity archetypeDef = mock(DefinitionEntity.class);
        when(archetypeDef.getId()).thenReturn(UUID.randomUUID());

        ArchetypeEntity archetype = mock(ArchetypeEntity.class);
        when(archetype.getId()).thenReturn(UUID.randomUUID());
        when(archetype.getDefinition()).thenReturn(archetypeDef);
        when(archetype.getStatement()).thenReturn(archetypeStmt);

        DefinitionEntity def = mock(DefinitionEntity.class);
        when(def.getId()).thenReturn(UUID.randomUUID());

        AscriptionEntity entity = mock(AscriptionEntity.class);
        when(entity.getId()).thenReturn(UUID.randomUUID());
        when(entity.getDefinition()).thenReturn(def);
        when(entity.getArchetype()).thenReturn(archetype);
        when(entity.getStatement()).thenReturn(statement);
        when(entity.getVersion()).thenReturn(1);
        when(entity.getStatus()).thenReturn(AscriptionStatusType.ACTIVE);
        when(entity.getTimestamp()).thenReturn(Instant.now());

        return entity;
    }
}
