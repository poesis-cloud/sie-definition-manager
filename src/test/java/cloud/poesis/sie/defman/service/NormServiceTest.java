package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.NormEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.GsmRuleViolationException;
import cloud.poesis.sie.defman.repository.NormRepository;
import cloud.poesis.sie.defman.type.AscriptionCascadeType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.GsmRuleType;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NormServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NormService service;
    private ArchetypeService archetypeService;

    @BeforeEach
    void setUp() {
        archetypeService = mock(ArchetypeService.class);
        service = new NormService(
                mock(NormRepository.class),
                mock(StructureService.class),
                archetypeService);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private NormEntity stubNorm(UUID structDefId, UUID qualDefId, ObjectNode statement) {
        StructureEntity structure = mock(StructureEntity.class);
        DefinitionEntity structDef = mock(DefinitionEntity.class);
        when(structDef.getId()).thenReturn(structDefId);
        when(structure.getDefinition()).thenReturn(structDef);

        ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
        DefinitionEntity qualDef = mock(DefinitionEntity.class);
        when(qualDef.getId()).thenReturn(qualDefId);
        when(qualifier.getDefinition()).thenReturn(qualDef);

        NormEntity entity = mock(NormEntity.class);
        when(entity.getStructure()).thenReturn(structure);
        when(entity.getQualifier()).thenReturn(qualifier);
        when(entity.getStatement()).thenReturn(statement);

        return entity;
    }

    // ========================================================================
    // CelProfile
    // ========================================================================

    @Nested
    class CelProfile {

        @Nested
        class GuardProfile {

            @ParameterizedTest
            @NullAndEmptySource
            @ValueSource(strings = { "true", " true ", "  " })
            void unconditionalGuard_accepted(String guard) {
                assertDoesNotThrow(() -> service.validateGuard(guard));
            }

            @Test
            void singleAxisEquality_accepted() {
                assertDoesNotThrow(() -> service
                        .validateGuard("DeploymentProperties.environment == \"production\""));
            }

            @Test
            void singleAxisInequality_accepted() {
                assertDoesNotThrow(() -> service.validateGuard("PerformanceProperties.criticality >= 3"));
            }

            @Test
            void singleAxisSetMembership_accepted() {
                assertDoesNotThrow(() -> service
                        .validateGuard("DeploymentProperties.tier in [\"production\", \"staging\"]"));
            }

            @Test
            void singleAxisNegatedSetMembership_accepted() {
                assertDoesNotThrow(() -> service.validateGuard(
                        "!(DeploymentProperties.region in [\"cn-north-1\", \"cn-northwest-1\"])"));
            }

            @Test
            void singleAxisRegexMatch_accepted() {
                assertDoesNotThrow(() -> service
                        .validateGuard("ServiceProperties.name.matches(\"^payment-.*\")"));
            }

            @Test
            void multiAxisConjunction_accepted() {
                assertDoesNotThrow(() -> service.validateGuard(
                        "DeploymentProperties.environment == \"production\" "
                                + "&& ServiceProperties.classification == \"PII\""));
            }

            @Test
            void threeAxisConjunction_accepted() {
                assertDoesNotThrow(() -> service.validateGuard(
                        "DeploymentProperties.environment == \"production\" "
                                + "&& DataProperties.classification == \"confidential\" "
                                + "&& ServiceProperties.owner != \"deprecated-team\""));
            }

            @Test
            void disjunction_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuard(
                                "DeploymentProperties.env == \"prod\" "
                                        + "|| DeploymentProperties.env == \"staging\""));
                assertEquals(GsmRuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
                assertTrue(ex.getMessage().contains("||"));
                assertTrue(ex.getMessage().contains("forbidden"));
            }

            @Test
            void ternary_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuard(
                                "DeploymentProperties.env == \"prod\" ? true : false"));
                assertEquals(GsmRuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
                assertTrue(ex.getMessage().contains("ternary"));
            }

            @Test
            void arithmetic_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuard("PerformanceProperties.score + 1 > 5"));
                assertEquals(GsmRuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
                assertTrue(ex.getMessage().contains("arithmetic"));
            }

            @Test
            void crossPropertyComparison_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuard(
                                "PerformanceProperties.actual > PerformanceProperties.budget"));
                assertEquals(GsmRuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
                assertTrue(ex.getMessage().contains("cross-property")
                        || ex.getMessage().contains("duplicate axis"));
            }

            @Test
            void duplicateAxis_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuard(
                                "DeploymentProperties.env == \"prod\" "
                                        + "&& DeploymentProperties.env == \"staging\""));
                assertEquals(GsmRuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
                assertTrue(ex.getMessage().contains("duplicate axis"));
            }

            @Test
            void forbiddenFunction_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuard("DeploymentProperties.tags.size() > 0"));
                assertEquals(GsmRuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
                assertTrue(ex.getMessage().contains("matches()") || ex.getMessage().contains("forbidden"));
            }

            @Test
            void syntaxError_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuard("DeploymentProperties.env ==== \"prod\""));
                assertEquals(GsmRuleType.NORM_GUARD_CEL_PARSING, ex.getRuleType());
                assertTrue(ex.getMessage().contains("parse error"));
            }

            // NORM_GUARD_COMPARISON_CONSISTENCY

            @Test
            void inListSingleElement_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuard("DeploymentProperties.env in [\"prod\"]"));
                assertEquals(GsmRuleType.NORM_GUARD_COMPARISON_CONSISTENCY, ex.getRuleType());
                assertTrue(ex.getMessage().contains(">= 2"));
            }

            @Test
            void inListMixedTypes_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuard(
                                "DeploymentProperties.tier in [\"prod\", 1]"));
                assertEquals(GsmRuleType.NORM_GUARD_COMPARISON_CONSISTENCY, ex.getRuleType());
                assertTrue(ex.getMessage().contains("type-homogeneous"));
            }

            @Test
            void inListDuplicates_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuard(
                                "DeploymentProperties.tier in [\"prod\", \"staging\", \"prod\"]"));
                assertEquals(GsmRuleType.NORM_GUARD_COMPARISON_CONSISTENCY, ex.getRuleType());
                assertTrue(ex.getMessage().contains("Duplicate"));
            }

            // NORM_GUARD_ARCHETYPE_REFERENCE_RESOLUTION

            @Test
            void guardArchetypeNotFound_rejected() {
                when(archetypeService.findInEffectByTitle("NonExistent")).thenReturn(Optional.empty());

                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuardReferences(
                                "NonExistent.environment == \"production\""));
                assertEquals(GsmRuleType.NORM_GUARD_ARCHETYPE_REFERENCE_RESOLUTION, ex.getRuleType());
                assertTrue(ex.getMessage().contains("NonExistent"));
            }

            // NORM_GUARD_PROPERTY_PATH_RESOLUTION

            @Test
            void guardPropertyNotInSchema_rejected() {
                ObjectNode schema = MAPPER.createObjectNode();
                schema.put("title", "DeploymentProperties");
                schema.putObject("properties")
                        .set("region", MAPPER.createObjectNode().put("type", "string"));

                ArchetypeEntity archetype = mock(ArchetypeEntity.class);
                when(archetype.getStatement()).thenReturn(schema);
                when(archetypeService.findInEffectByTitle("DeploymentProperties"))
                        .thenReturn(Optional.of(archetype));

                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuardReferences(
                                "DeploymentProperties.nonExistentProp == \"x\""));
                assertEquals(GsmRuleType.NORM_GUARD_PROPERTY_PATH_RESOLUTION, ex.getRuleType());
                assertTrue(ex.getMessage().contains("nonExistentProp"));
            }
        }

        @Nested
        class PredicateProfile {

            @Test
            void emptyPredicate_rejected() {
                GsmRuleViolationException ex1 = assertThrows(GsmRuleViolationException.class,
                        () -> service.validatePredicate(null));
                assertEquals(GsmRuleType.NORM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex1.getRuleType());

                GsmRuleViolationException ex2 = assertThrows(GsmRuleViolationException.class,
                        () -> service.validatePredicate(""));
                assertEquals(GsmRuleType.NORM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex2.getRuleType());

                GsmRuleViolationException ex3 = assertThrows(GsmRuleViolationException.class,
                        () -> service.validatePredicate("   "));
                assertEquals(GsmRuleType.NORM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex3.getRuleType());
            }

            @Test
            void simpleThreshold_accepted() {
                assertDoesNotThrow(() -> service.validatePredicate("self.encryptionLevel >= \"AES-128\""));
            }

            @Test
            void booleanAnd_accepted() {
                assertDoesNotThrow(() -> service.validatePredicate(
                        "self.tlsEnabled == true && self.tlsVersion >= \"1.2\""));
            }

            @Test
            void disjunction_accepted() {
                assertDoesNotThrow(() -> service.validatePredicate(
                        "self.authMethod == \"mTLS\" || self.authMethod == \"OAuth2\""));
            }

            @Test
            void negation_accepted() {
                assertDoesNotThrow(() -> service.validatePredicate("!self.allowsAnonymousAccess"));
            }

            @Test
            void crossPropertyComparison_accepted() {
                assertDoesNotThrow(() -> service.validatePredicate("self.p99Latency < self.budget"));
            }

            @Test
            void arithmeticThreshold_accepted() {
                assertDoesNotThrow(() -> service.validatePredicate("self.p99Latency < self.budget * 1.1"));
            }

            @Test
            void setMembership_accepted() {
                assertDoesNotThrow(() -> service.validatePredicate(
                        "self.cipherSuite in [\"TLS_AES_128_GCM_SHA256\", \"TLS_AES_256_GCM_SHA384\"]"));
            }

            @Test
            void stringFunctions_accepted() {
                assertDoesNotThrow(() -> service.validatePredicate(
                        "self.schema.matches(\"^https://.*\") && !self.owner.endsWith(\"-deprecated\")"));
            }

            @Test
            void collectionMacroAll_accepted() {
                assertDoesNotThrow(() -> service.validatePredicate("self.ports.all(p, p >= 1024)"));
            }

            @Test
            void collectionMacroExists_accepted() {
                assertDoesNotThrow(() -> service.validatePredicate(
                        "self.certifications.exists(c, c == \"SOC2\" || c == \"ISO27001\")"));
            }

            @Test
            void hasFieldPresence_accepted() {
                assertDoesNotThrow(() -> service.validatePredicate("has(self.retentionPolicy)"));
            }

            @Test
            void sizeCheck_accepted() {
                assertDoesNotThrow(() -> service.validatePredicate("self.tags.size() >= 1"));
            }

            @Test
            void ternary_accepted() {
                assertDoesNotThrow(() -> service.validatePredicate(
                        "(self.tier == \"critical\" ? self.p99Latency < 100 : self.p99Latency < 500)"));
            }

            @Test
            void typeConversion_accepted() {
                assertDoesNotThrow(() -> service.validatePredicate("double(self.monthlyBudget) > 0.0"));
            }

            @Test
            void nonDeterministicNow_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validatePredicate("self.lastReview > now()"));
                assertEquals(GsmRuleType.NORM_PREDICATE_AS_DETERMINISTIC_EXPRESSION, ex.getRuleType());
                assertTrue(ex.getMessage().contains("non-deterministic"));
            }

            @Test
            void nonDeterministicUuid_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validatePredicate("self.id == uuid()"));
                assertEquals(GsmRuleType.NORM_PREDICATE_AS_DETERMINISTIC_EXPRESSION, ex.getRuleType());
                assertTrue(ex.getMessage().contains("non-deterministic"));
            }

            @Test
            void explicitArchetypeName_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validatePredicate("SecurityProperties.tlsEnabled == true"));
                assertEquals(GsmRuleType.NORM_PREDICATE_AS_ARCHETYPE_BOUND_EXPRESSION, ex.getRuleType());
                assertTrue(ex.getMessage().contains("self"));
            }

            @Test
            void syntaxError_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validatePredicate("self.value >>>= 5"));
                assertEquals(GsmRuleType.NORM_PREDICATE_CEL_PARSING, ex.getRuleType());
            }

            // NORM_PREDICATE_AS_BOOLEAN_RESULT

            @Test
            void arithmeticTopLevel_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validatePredicate("self.a + self.b"));
                assertEquals(GsmRuleType.NORM_PREDICATE_AS_BOOLEAN_RESULT, ex.getRuleType());
                assertTrue(ex.getMessage().contains("arithmetic"));
            }

            @Test
            void nonBooleanConstant_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validatePredicate("42"));
                assertEquals(GsmRuleType.NORM_PREDICATE_AS_BOOLEAN_RESULT, ex.getRuleType());
                assertTrue(ex.getMessage().contains("non-boolean constant"));
            }

            // NORM_PREDICATE_PROPERTY_PATH_RESOLUTION

            @Test
            void predicatePropertyNotInQualifier_rejected() {
                ObjectNode schema = MAPPER.createObjectNode();
                schema.put("title", "SecurityProperties");
                schema.putObject("properties")
                        .set("tlsEnabled", MAPPER.createObjectNode().put("type", "boolean"));

                ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
                when(qualifier.getStatement()).thenReturn(schema);

                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validatePredicatePropertyPaths(
                                "self.nonExistentField > 0", qualifier));
                assertEquals(GsmRuleType.NORM_PREDICATE_PROPERTY_PATH_RESOLUTION, ex.getRuleType());
                assertTrue(ex.getMessage().contains("nonExistentField"));
            }
        }
    }

    // ========================================================================
    // Tolerance Mode Consistency
    // ========================================================================

    @Nested
    class ToleranceMode {

        @Test
        void instantaneous_withTemporalWindow_rejected() {
            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("toleranceMode", "INSTANTANEOUS");
            stmt.put("temporalWindow", "PT5M");

            GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                    () -> NormService.validateToleranceModeConsistency(stmt));
            assertEquals(GsmRuleType.NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
            assertTrue(ex.getMessage().contains("INSTANTANEOUS"));
        }

        @Test
        void aggregated_missingWindow_rejected() {
            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("toleranceMode", "AGGREGATED");
            stmt.put("temporalAggregation", "P99");

            GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                    () -> NormService.validateToleranceModeConsistency(stmt));
            assertEquals(GsmRuleType.NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
            assertTrue(ex.getMessage().contains("AGGREGATED"));
        }

        @Test
        void aggregated_missingAggregation_rejected() {
            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("toleranceMode", "AGGREGATED");
            stmt.put("temporalWindow", "PT5M");

            GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                    () -> NormService.validateToleranceModeConsistency(stmt));
            assertEquals(GsmRuleType.NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
            assertTrue(ex.getMessage().contains("AGGREGATED"));
        }

        @Test
        void aggregated_withSustainedThreshold_rejected() {
            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("toleranceMode", "AGGREGATED");
            stmt.put("temporalWindow", "PT5M");
            stmt.put("temporalAggregation", "P99");
            stmt.put("sustainedThreshold", 0.99);

            GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                    () -> NormService.validateToleranceModeConsistency(stmt));
            assertEquals(GsmRuleType.NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
            assertTrue(ex.getMessage().contains("forbids sustainedThreshold"));
        }

        @Test
        void sustained_missingFields_rejected() {
            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("toleranceMode", "SUSTAINED");
            stmt.put("temporalWindow", "PT5M");

            GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                    () -> NormService.validateToleranceModeConsistency(stmt));
            assertEquals(GsmRuleType.NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
            assertTrue(ex.getMessage().contains("SUSTAINED"));
        }

        @Test
        void sustained_thresholdOutOfRange_rejected() {
            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("toleranceMode", "SUSTAINED");
            stmt.put("temporalWindow", "PT5M");
            stmt.put("temporalAggregation", "AVG");
            stmt.put("sustainedThreshold", 1.5);

            GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                    () -> NormService.validateToleranceModeConsistency(stmt));
            assertEquals(GsmRuleType.NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
            assertTrue(ex.getMessage().contains("[0, 1]"));
        }
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @Nested
    class Lifecycle {

        @Nested
        class IdentityBound {

            @Test
            void structureQualifierPredicateExtracted() {
                UUID structDefId = UUID.randomUUID();
                UUID qualDefId = UUID.randomUUID();

                ObjectNode stmt = MAPPER.createObjectNode();
                stmt.put("predicate", "self.status == 'OK'");

                NormEntity entity = stubNorm(structDefId, qualDefId, stmt);

                var values = service.getIdentityBoundValues(entity);

                assertEquals(structDefId, values.get("structure"));
                assertEquals(qualDefId, values.get("qualifier"));
                assertEquals("self.status == 'OK'", values.get("predicate"));
            }

            @Test
            void noPredicate_structureAndQualifierOnly() {
                UUID structDefId = UUID.randomUUID();
                UUID qualDefId = UUID.randomUUID();

                ObjectNode stmt = MAPPER.createObjectNode();

                NormEntity entity = stubNorm(structDefId, qualDefId, stmt);

                var values = service.getIdentityBoundValues(entity);

                assertEquals(structDefId, values.get("structure"));
                assertEquals(qualDefId, values.get("qualifier"));
                assertEquals(2, values.size());
            }
        }

        @Nested
        class RefereeReferences {

            @Test
            void referencesStructureAndQualifier() {
                UUID structDefId = UUID.randomUUID();
                UUID qualDefId = UUID.randomUUID();

                NormEntity entity = stubNorm(structDefId, qualDefId, MAPPER.createObjectNode());

                var refs = service.getRefereeReferences(entity);

                assertEquals(2, refs.size());
                assertTrue(refs.stream().anyMatch(r -> r.label().equals("structure")));
                assertTrue(refs.stream().anyMatch(r -> r.label().equals("qualifier")));
            }
        }

        @Nested
        class CascadeRoles {

            @Test
            void governingFromStructure() {
                var roles = service.getCascadeTargetRoles();

                assertEquals(1, roles.size());
                assertTrue(roles.containsKey(DefinitionSubjectType.STRUCTURE));
                assertEquals(AscriptionCascadeType.GOVERNING,
                        roles.get(DefinitionSubjectType.STRUCTURE));
            }
        }
    }
}
