package cloud.poesis.sie.defman.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.dto.AscriptionDto;
import cloud.poesis.sie.defman.dto.AscriptionStatusTransitionDto;
import cloud.poesis.sie.defman.dto.DefinitionDto;
import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.service.DataProtectionService;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import cloud.poesis.sie.defman.type.RuleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

/**
 * Tests for {@link AbstractController#mapEntityToAscriptionDto} — specifically
 * the
 * $gsm:dataProtection inTransit logic (GSM §8).
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

            ObjectNode statement = MAPPER.createObjectNode().put("password", "s3cret").put("name", "test-service");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            AscriptionDto dto = controller.mapEntityToAscriptionDto(entity, entity.getArchetype());

            // Password should be hashed (64 hex chars for SHA-256)
            String hashed = dto.getStatement().get("password").asText();
            assertTrue(hashed.matches("[0-9a-f]{64}"), "Expected SHA-256 hex hash, got: " + hashed);
            assertEquals("test-service", dto.getStatement().get("name").asText());
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

            ObjectNode statement = MAPPER.createObjectNode().put("phone", "555-1234");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            AscriptionDto dto = controller.mapEntityToAscriptionDto(entity, entity.getArchetype());

            String masked = dto.getStatement().get("phone").asText();
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

            ObjectNode statement = MAPPER.createObjectNode().put("secret", "top-secret").put("env", "prod");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            AscriptionDto dto = controller.mapEntityToAscriptionDto(entity, entity.getArchetype());

            assertNull(dto.getStatement().get("secret"));
            assertEquals("prod", dto.getStatement().get("env").asText());
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

            ObjectNode statement = MAPPER.createObjectNode().put("card", "4111-1111-1111-1111");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            assertDoesNotThrow(() -> controller.mapEntityToAscriptionDto(entity, entity.getArchetype()));
        }

        @Test
        void noDataProtection_unchanged() {
            ObjectNode archetypeSchema = MAPPER.createObjectNode();
            archetypeSchema.put("title", "TestSchema");
            ObjectNode props = archetypeSchema.putObject("properties");
            ObjectNode nameProp = props.putObject("name");
            nameProp.put("type", "string");

            ObjectNode statement = MAPPER.createObjectNode().put("name", "test-service");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            AscriptionDto dto = controller.mapEntityToAscriptionDto(entity, entity.getArchetype());

            assertEquals("test-service", dto.getStatement().get("name").asText());
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

            ObjectNode statement = MAPPER.createObjectNode().put("ssn", "already-hashed-at-rest-value");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            AscriptionDto dto = controller.mapEntityToAscriptionDto(entity, entity.getArchetype());

            assertEquals("already-hashed-at-rest-value", dto.getStatement().get("ssn").asText());
        }

        @Test
        void noPropertiesInArchetype_passThrough() {
            ObjectNode archetypeStatement = MAPPER.createObjectNode();
            // no "properties" key

            ObjectNode statement = MAPPER.createObjectNode().put("password", "s3cret");

            AscriptionEntity entity = stubEntityWithCustomArchetypeStatement(archetypeStatement, statement);

            AscriptionDto dto = controller.mapEntityToAscriptionDto(entity, entity.getArchetype());

            assertEquals("s3cret", dto.getStatement().get("password").asText());
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
            ObjectNode statement = MAPPER.createObjectNode().put("name", "test");

            AscriptionEntity entity = stubEntity(archetypeSchema, statement);

            AscriptionDto dto = controller.mapEntityToAscriptionDto(entity, entity.getArchetype());

            assertNull(dto.getStatement().get("password"));
            assertEquals("test", dto.getStatement().get("name").asText());
        }
    }

    // ========================================================================
    // mapEntityToAscriptionStatusTransitionDto
    // ========================================================================

    @Nested
    class MapEntityToAscriptionStatusTransitionDtoTests {

        @Test
        void mapsAllFields() {
            UUID transitionId = UUID.randomUUID();
            UUID ascriptionId = UUID.randomUUID();
            Instant ts = Instant.parse("2025-01-15T10:30:00Z");

            AscriptionEntity asc = mock(AscriptionEntity.class);
            when(asc.getId()).thenReturn(ascriptionId);

            AscriptionStatusTransitionEntity transition = mock(AscriptionStatusTransitionEntity.class);
            when(transition.getId()).thenReturn(transitionId);
            when(transition.getAscription()).thenReturn(asc);
            when(transition.getPreStatus()).thenReturn(AscriptionStatusType.DRAFT);
            when(transition.getPostStatus()).thenReturn(AscriptionStatusType.PROPOSED);
            when(transition.getTimestamp()).thenReturn(ts);

            AscriptionStatusTransitionDto dto = controller.mapEntityToAscriptionStatusTransitionDto(transition);

            assertEquals(transitionId, dto.getTransitionId());
            assertEquals(ascriptionId, dto.getAscriptionId());
            assertEquals(AscriptionStatusType.DRAFT, dto.getPreStatus());
            assertEquals(AscriptionStatusType.PROPOSED, dto.getPostStatus());
            assertEquals(ts, dto.getTimestamp());
        }

        @Test
        void handlesNullPreStatus() {
            AscriptionEntity asc = mock(AscriptionEntity.class);
            when(asc.getId()).thenReturn(UUID.randomUUID());

            AscriptionStatusTransitionEntity transition = mock(AscriptionStatusTransitionEntity.class);
            when(transition.getId()).thenReturn(UUID.randomUUID());
            when(transition.getAscription()).thenReturn(asc);
            when(transition.getPreStatus()).thenReturn(null);
            when(transition.getPostStatus()).thenReturn(AscriptionStatusType.DRAFT);
            when(transition.getTimestamp()).thenReturn(Instant.now());

            AscriptionStatusTransitionDto dto = controller.mapEntityToAscriptionStatusTransitionDto(transition);

            assertNull(dto.getPreStatus());
            assertEquals(AscriptionStatusType.DRAFT, dto.getPostStatus());
        }
    }

    // ========================================================================
    // mapEntityToDefinitionDto
    // ========================================================================

    @Nested
    class MapEntityToDefinitionDtoTests {

        @Test
        void mapsAllFields() {
            UUID defId = UUID.randomUUID();
            DefinitionEntity def = mock(DefinitionEntity.class);
            when(def.getId()).thenReturn(defId);
            when(def.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);

            DefinitionDto dto = controller.mapEntityToDefinitionDto(def);

            assertEquals(defId, dto.getId());
            assertEquals(DefinitionSubjectType.MECHANISM, dto.getSubjectType());
        }

        @Test
        void mapsEachSubjectType() {
            for (DefinitionSubjectType type : DefinitionSubjectType.values()) {
                DefinitionEntity def = mock(DefinitionEntity.class);
                when(def.getId()).thenReturn(UUID.randomUUID());
                when(def.getSubjectType()).thenReturn(type);

                DefinitionDto dto = controller.mapEntityToDefinitionDto(def);
                assertEquals(type, dto.getSubjectType());
            }
        }
    }

    // ========================================================================
    // Exception Handlers
    // ========================================================================

    @Nested
    class ExceptionHandlerTests {

        @Test
        void ruleViolation_badRequest() {
            RuleViolationException ex = new RuleViolationException(
                    RuleType.STRUCTURE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                    "Statement does not match schema");
            ProblemDetail pd = controller.mapRuleViolationExceptionToProblemDetail(ex);

            assertEquals(400, pd.getStatus());
            assertEquals("Statement does not match schema", pd.getDetail());
            assertEquals(ex.getTitle(), pd.getTitle());
        }

        @Test
        void ruleViolation_conflict() {
            RuleViolationException ex = new RuleViolationException(
                    RuleType.ASCRIPTION_STATUS_TRANSITION_PATH, "Invalid transition path");
            ProblemDetail pd = controller.mapRuleViolationExceptionToProblemDetail(ex);

            assertEquals(409, pd.getStatus());
            assertEquals("Invalid transition path", pd.getDetail());
        }

        @Test
        void ruleViolation_internalServerError_delegatesToGenericHandler() {
            RuleViolationException ex = new RuleViolationException(
                    RuleType.DEFINITION_ASCRIPTIONS_ALWAYS_PRESENT, "Invariant violated");
            ProblemDetail pd = controller.mapRuleViolationExceptionToProblemDetail(ex);

            assertEquals(500, pd.getStatus());
            assertEquals("Unexpected server error", pd.getDetail());
            assertEquals("Internal server error", pd.getTitle());
        }

        @Test
        void ruleViolation_withSite_includesExtensions() {
            Map<String, Object> site = Map.of("definitionId", UUID.randomUUID().toString());
            RuleViolationException ex = new RuleViolationException(RuleType.NORM_GUARD_CEL_PARSING, "CEL parse error",
                    site);
            ProblemDetail pd = controller.mapRuleViolationExceptionToProblemDetail(ex);

            assertEquals(400, pd.getStatus());
            assertNotNull(pd.getProperties());
            assertTrue(pd.getProperties().containsKey("definitionId"));
            assertTrue(pd.getProperties().containsKey("rule"));
            assertTrue(pd.getProperties().containsKey("ruleDescription"));
        }

        @Test
        void resourceNotFound_returns404() {
            ResourceNotFoundException ex = new ResourceNotFoundException(PrimitiveType.DEFINITION, UUID.randomUUID());
            ProblemDetail pd = controller.mapResourceNotFoundExceptionToProblemDetail(ex);

            assertEquals(404, pd.getStatus());
            assertEquals("Not found", pd.getTitle());
            assertNotNull(pd.getProperties());
            assertTrue(pd.getProperties().containsKey("resourceType"));
            assertTrue(pd.getProperties().containsKey("resourceId"));
        }

        @Test
        void illegalArgument_returns400() {
            IllegalArgumentException ex = new IllegalArgumentException("bad param");
            ProblemDetail pd = controller.mapIllegalArgumentExceptionToProblemDetail(ex);

            assertEquals(400, pd.getStatus());
            assertEquals("bad param", pd.getDetail());
            assertEquals("Invalid request parameter", pd.getTitle());
        }

        @Test
        void genericException_returns500() {
            Exception ex = new RuntimeException("unexpected");
            ProblemDetail pd = controller.mapExceptionToProblemDetail(ex);

            assertEquals(500, pd.getStatus());
            assertEquals("Unexpected server error", pd.getDetail());
            assertEquals("Internal server error", pd.getTitle());
        }

        @Test
        void allBadRequestRuleTypes_mapToBadRequest() {
            RuleType[] badRequestTypes = {
                    RuleType.STRUCTURE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                    RuleType.MECHANISM_RULE_STARLARK_PARSING,
                    RuleType.EFFECTOR_MECHANISM_REFERENCE_INTEGRITY,
                    RuleType.RECEPTOR_MECHANISM_REFERENCE_INTEGRITY,
                    RuleType.INTERACTION_EFFECTOR_RECEPTOR_COMPATIBILITY,
                    RuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
                    RuleType.DIRECTIVE_STRUCTURE_REFERENCE_INTEGRITY,
                    RuleType.NORM_GUARD_CEL_PARSING,
                    RuleType.NORM_PREDICATE_CEL_PARSING,
                    RuleType.ARCHETYPE_ALLOF_CHAIN_EXCLUSIVE_BASE_CONVERGENCE,
                    RuleType.ARCHETYPE_VALIDATION_CEL_PARSING,
            };
            for (RuleType rt : badRequestTypes) {
                RuleViolationException ex = new RuleViolationException(rt, "test");
                ProblemDetail pd = controller.mapRuleViolationExceptionToProblemDetail(ex);
                assertEquals(400, pd.getStatus(), "Expected 400 for " + rt);
            }
        }

        @Test
        void allConflictRuleTypes_mapToConflict() {
            RuleType[] conflictTypes = {
                    RuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
                    RuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION,
                    RuleType.DIRECTIVE_VERB_COMPATIBILITY,
                    RuleType.DIRECTIVE_MODAL_COMPATIBILITY,
                    RuleType.ASCRIPTION_STATUS_TRANSITION_PATH,
                    RuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
                    RuleType.ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_CONSTITUENTS,
                    RuleType.ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_SUBJECTS,
                    RuleType.ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_DEPENDENTS,
                    RuleType.ASCRIPTION_STATUS_TRANSITION_APPROVAL_CONVERGENCE,
                    RuleType.ASCRIPTION_STATUS_TRANSITION_ACTIVATION_HANDOFF,
                    RuleType.ASCRIPTION_STATUS_TRANSITION_TERMINAL_IMMUTABILITY,
            };
            for (RuleType rt : conflictTypes) {
                RuleViolationException ex = new RuleViolationException(rt, "test");
                ProblemDetail pd = controller.mapRuleViolationExceptionToProblemDetail(ex);
                assertEquals(409, pd.getStatus(), "Expected 409 for " + rt);
            }
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AscriptionEntity stubEntity(ObjectNode archetypeSchema, ObjectNode statement) {
        return stubEntityWithCustomArchetypeStatement(archetypeSchema, statement);
    }

    private AscriptionEntity stubEntityWithCustomArchetypeStatement(
            ObjectNode archetypeStmt, ObjectNode statement) {
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
