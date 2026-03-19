package cloud.poesis.sie.defman.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.poesis.sie.defman.dto.AscriptionDto;
import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.service.DataProtectionService;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;

/**
 * Tests for {@link AbstractController#toAscriptionDto} — specifically
 * the $gsm:dataProtection inTransit logic (GSM §8).
 */
class AbstractControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Minimal concrete subclass exposing the protected method. */
    private final AbstractController controller;

    AbstractControllerTest() {
        controller = new AbstractController(new DataProtectionService()) {
        };
    }

    // ========================================================================
    // $gsm:dataProtection inTransit (GSM §8)
    // ========================================================================

    @Nested
    class DataProtectionInTransit {

        @Test
        void hashInTransit_replacesValueWithHash() {
            ObjectNode archetypeSchema = MAPPER.createObjectNode();
            archetypeSchema.put("title", "TestSchema");
            ObjectNode props = archetypeSchema.putObject("properties");
            ObjectNode passwordProp = props.putObject("password");
            passwordProp.put("type", "string");
            ObjectNode dp = passwordProp.putObject("$gsm:dataProtection");
            dp.putObject("inTransit").putObject("hash").put("algorithm", "SHA-256");
            ObjectNode nameProp = props.putObject("name");
            nameProp.put("type", "string");

            ObjectNode statement = MAPPER.createObjectNode()
                    .put("password", "s3cret")
                    .put("name", "test-service");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            AscriptionDto dto = controller.toAscriptionDto(entity, entity.getArchetype());

            // Password should be hashed (64 hex chars for SHA-256)
            String hashed = dto.statement().get("password").asText();
            assertTrue(hashed.matches("[0-9a-f]{64}"), "Expected SHA-256 hex hash, got: " + hashed);
            assertEquals("test-service", dto.statement().get("name").asText());
        }

        @Test
        void maskInTransit_masksValue() {
            ObjectNode archetypeSchema = MAPPER.createObjectNode();
            archetypeSchema.put("title", "TestSchema");
            ObjectNode props = archetypeSchema.putObject("properties");
            ObjectNode phoneProp = props.putObject("phone");
            phoneProp.put("type", "string");
            ObjectNode dp = phoneProp.putObject("$gsm:dataProtection");
            ObjectNode mask = dp.putObject("inTransit").putObject("mask");
            mask.put("from", "RIGHT");
            ObjectNode with = mask.putObject("with");
            with.put("character", "*");
            with.put("occurrence", 4);

            ObjectNode statement = MAPPER.createObjectNode()
                    .put("phone", "555-1234");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            AscriptionDto dto = controller.toAscriptionDto(entity, entity.getArchetype());

            String masked = dto.statement().get("phone").asText();
            assertTrue(masked.endsWith("1234"), "Expected last 4 chars visible, got: " + masked);
            assertTrue(masked.contains("*"), "Expected masking characters, got: " + masked);
        }

        @Test
        void suppressionInTransit_removesField() {
            ObjectNode archetypeSchema = MAPPER.createObjectNode();
            archetypeSchema.put("title", "TestSchema");
            ObjectNode props = archetypeSchema.putObject("properties");
            ObjectNode secretProp = props.putObject("secret");
            secretProp.put("type", "string");
            ObjectNode dp = secretProp.putObject("$gsm:dataProtection");
            dp.putObject("inTransit").put("suppression", true);
            ObjectNode envProp = props.putObject("env");
            envProp.put("type", "string");

            ObjectNode statement = MAPPER.createObjectNode()
                    .put("secret", "top-secret")
                    .put("env", "prod");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            AscriptionDto dto = controller.toAscriptionDto(entity, entity.getArchetype());

            assertNull(dto.statement().get("secret"));
            assertEquals("prod", dto.statement().get("env").asText());
        }

        @Test
        void encryptionInTransit_silentlyIgnored() {
            ObjectNode archetypeSchema = MAPPER.createObjectNode();
            archetypeSchema.put("title", "TestSchema");
            ObjectNode props = archetypeSchema.putObject("properties");
            ObjectNode cardProp = props.putObject("card");
            cardProp.put("type", "string");
            ObjectNode dp = cardProp.putObject("$gsm:dataProtection");
            dp.putObject("inTransit").putObject("encryption").put("algorithm", "AES-256-GCM");

            ObjectNode statement = MAPPER.createObjectNode()
                    .put("card", "4111-1111-1111-1111");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            assertDoesNotThrow(
                    () -> controller.toAscriptionDto(entity, entity.getArchetype()));
        }

        @Test
        void noDataProtection_unchanged() {
            ObjectNode archetypeSchema = MAPPER.createObjectNode();
            archetypeSchema.put("title", "TestSchema");
            ObjectNode props = archetypeSchema.putObject("properties");
            ObjectNode nameProp = props.putObject("name");
            nameProp.put("type", "string");

            ObjectNode statement = MAPPER.createObjectNode()
                    .put("name", "test-service");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            AscriptionDto dto = controller.toAscriptionDto(entity, entity.getArchetype());

            assertEquals("test-service", dto.statement().get("name").asText());
        }

        @Test
        void onlyAtRest_noInTransitTransform() {
            ObjectNode archetypeSchema = MAPPER.createObjectNode();
            archetypeSchema.put("title", "TestSchema");
            ObjectNode props = archetypeSchema.putObject("properties");
            ObjectNode ssnProp = props.putObject("ssn");
            ssnProp.put("type", "string");
            ObjectNode dp = ssnProp.putObject("$gsm:dataProtection");
            dp.putObject("atRest").putObject("hash").put("algorithm", "SHA-256");
            // No inTransit → should not modify value in API response

            ObjectNode statement = MAPPER.createObjectNode()
                    .put("ssn", "already-hashed-at-rest-value");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            AscriptionDto dto = controller.toAscriptionDto(entity, entity.getArchetype());

            assertEquals("already-hashed-at-rest-value", dto.statement().get("ssn").asText());
        }

        @Test
        void noPropertiesInArchetype_passThrough() {
            ObjectNode archetypeStatement = MAPPER.createObjectNode();
            // no "properties" key

            ObjectNode statement = MAPPER.createObjectNode()
                    .put("password", "s3cret");

            AscriptionEntity entity = stubEntityWithCustomArchetypeStatement(archetypeStatement, statement);

            AscriptionDto dto = controller.toAscriptionDto(entity, entity.getArchetype());

            assertEquals("s3cret", dto.statement().get("password").asText());
        }

        @Test
        void dataProtectionPropertyNotInStatement_noError() {
            ObjectNode archetypeSchema = MAPPER.createObjectNode();
            archetypeSchema.put("title", "TestSchema");
            ObjectNode props = archetypeSchema.putObject("properties");
            ObjectNode passwordProp = props.putObject("password");
            passwordProp.put("type", "string");
            ObjectNode dp = passwordProp.putObject("$gsm:dataProtection");
            dp.putObject("inTransit").put("suppression", true);

            // Statement does NOT contain "password"
            ObjectNode statement = MAPPER.createObjectNode()
                    .put("name", "test");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            AscriptionDto dto = controller.toAscriptionDto(entity, entity.getArchetype());

            assertNull(dto.statement().get("password"));
            assertEquals("test", dto.statement().get("name").asText());
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AscriptionEntity stubEntity(ObjectNode archetypeSchema, ObjectNode statement) {
        return stubEntityWithCustomArchetypeStatement(archetypeSchema, statement);
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
        when(def.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);

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
