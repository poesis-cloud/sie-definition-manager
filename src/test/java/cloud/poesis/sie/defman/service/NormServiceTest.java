package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.NormEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.repository.NormRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.RuleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NormServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private NormRepository normRepo;

    @Mock
    private StructureService structureService;

    @Mock
    private ArchetypeService archetypeService;

    private NormService service;

    @BeforeEach
    void setUp() {
        service = new NormService(
                normRepo,
                structureService,
                archetypeService,
                mock(DefinitionService.class),
                mock(AscriptionStatusTransitionService.class),
                mock(AscriptionRepository.class),
                mock(EntityManager.class),
                mock(DataProtectionService.class));
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
                assertDoesNotThrow(
                        () -> service.validateGuard("DeploymentProperties.environment == \"production\""));
            }

            @Test
            void singleAxisInequality_accepted() {
                assertDoesNotThrow(() -> service.validateGuard("PerformanceProperties.criticality >= 3"));
            }

            @Test
            void singleAxisSetMembership_accepted() {
                assertDoesNotThrow(
                        () -> service.validateGuard(
                                "DeploymentProperties.tier in [\"production\", \"staging\"]"));
            }

            @Test
            void singleAxisNegatedSetMembership_accepted() {
                assertDoesNotThrow(
                        () -> service.validateGuard(
                                "!(DeploymentProperties.region in [\"cn-north-1\", \"cn-northwest-1\"])"));
            }

            @Test
            void singleAxisRegexMatch_accepted() {
                assertDoesNotThrow(
                        () -> service.validateGuard("ServiceProperties.name.matches(\"^payment-.*\")"));
            }

            @Test
            void multiAxisConjunction_accepted() {
                assertDoesNotThrow(
                        () -> service.validateGuard(
                                "DeploymentProperties.environment == \"production\" "
                                        + "&& ServiceProperties.classification == \"PII\""));
            }

            @Test
            void threeAxisConjunction_accepted() {
                assertDoesNotThrow(
                        () -> service.validateGuard(
                                "DeploymentProperties.environment == \"production\" "
                                        + "&& DataProperties.classification == \"confidential\" "
                                        + "&& ServiceProperties.owner != \"deprecated-team\""));
            }

            @Test
            void disjunction_rejected() {
                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateGuard(
                                "DeploymentProperties.env == \"prod\" "
                                        + "|| DeploymentProperties.env == \"staging\""));
                assertEquals(RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
                assertTrue(ex.getMessage().contains("||"));
                assertTrue(ex.getMessage().contains("forbidden"));
            }

            @Test
            void ternary_rejected() {
                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateGuard("DeploymentProperties.env == \"prod\" ? true : false"));
                assertEquals(RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
                assertTrue(ex.getMessage().contains("ternary"));
            }

            @Test
            void arithmetic_rejected() {
                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateGuard("PerformanceProperties.score + 1 > 5"));
                assertEquals(RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
                assertTrue(ex.getMessage().contains("arithmetic"));
            }

            @Test
            void crossPropertyComparison_rejected() {
                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateGuard(
                                "PerformanceProperties.actual > PerformanceProperties.budget"));
                assertEquals(RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
                assertTrue(
                        ex.getMessage().contains("cross-property")
                                || ex.getMessage().contains("duplicate axis"));
            }

            @Test
            void duplicateAxis_rejected() {
                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateGuard(
                                "DeploymentProperties.env == \"prod\" "
                                        + "&& DeploymentProperties.env == \"staging\""));
                assertEquals(RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
                assertTrue(ex.getMessage().contains("duplicate axis"));
            }

            @Test
            void forbiddenFunction_rejected() {
                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateGuard("DeploymentProperties.tags.size() > 0"));
                assertEquals(RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
                assertTrue(ex.getMessage().contains("matches()") || ex.getMessage().contains("forbidden"));
            }

            @Test
            void syntaxError_rejected() {
                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateGuard("DeploymentProperties.env ==== \"prod\""));
                assertEquals(RuleType.NORM_GUARD_CEL_PARSING, ex.getRuleType());
                assertTrue(ex.getMessage().contains("parse error"));
            }

            // NORM_GUARD_COMPARISON_CONSISTENCY

            @Test
            void inListSingleElement_rejected() {
                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateGuard("DeploymentProperties.env in [\"prod\"]"));
                assertEquals(RuleType.NORM_GUARD_COMPARISON_CONSISTENCY, ex.getRuleType());
                assertTrue(ex.getMessage().contains(">= 2"));
            }

            @Test
            void inListMixedTypes_rejected() {
                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateGuard("DeploymentProperties.tier in [\"prod\", 1]"));
                assertEquals(RuleType.NORM_GUARD_COMPARISON_CONSISTENCY, ex.getRuleType());
                assertTrue(ex.getMessage().contains("type-homogeneous"));
            }

            @Test
            void inListDuplicates_rejected() {
                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateGuard(
                                "DeploymentProperties.tier in [\"prod\", \"staging\", \"prod\"]"));
                assertEquals(RuleType.NORM_GUARD_COMPARISON_CONSISTENCY, ex.getRuleType());
                assertTrue(ex.getMessage().contains("Duplicate"));
            }

            // NORM_GUARD_ARCHETYPE_REFERENCE_RESOLUTION

            @Test
            void guardArchetypeNotFound_rejected() {
                when(archetypeService.findInEffectByTitle("NonExistent")).thenReturn(Optional.empty());

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateGuardReferences("NonExistent.environment == \"production\""));
                assertEquals(RuleType.NORM_GUARD_ARCHETYPE_REFERENCE_RESOLUTION, ex.getRuleType());
                assertTrue(ex.getMessage().contains("NonExistent"));
            }

            // NORM_GUARD_PROPERTY_PATH_RESOLUTION

            @Test
            void guardPropertyNotInSchema_rejected() {
                ObjectNode schema = MAPPER.createObjectNode();
                schema.put("title", "DeploymentProperties");
                schema
                        .putObject("properties")
                        .set("region", MAPPER.createObjectNode().put("type", "string"));

                ArchetypeEntity archetype = mock(ArchetypeEntity.class);
                when(archetype.getStatement()).thenReturn(schema);
                when(archetypeService.findInEffectByTitle("DeploymentProperties"))
                        .thenReturn(Optional.of(archetype));

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateGuardReferences(
                                "DeploymentProperties.nonExistentProp == \"x\""));
                assertEquals(RuleType.NORM_GUARD_PROPERTY_PATH_RESOLUTION, ex.getRuleType());
                assertTrue(ex.getMessage().contains("nonExistentProp"));
            }
        }

        @Nested
        class PredicateProfile {

            @Test
            void emptyPredicate_rejected() {
                RuleViolationException ex1 = assertThrows(RuleViolationException.class,
                        () -> service.validatePredicate(null));
                assertEquals(RuleType.NORM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex1.getRuleType());

                RuleViolationException ex2 = assertThrows(RuleViolationException.class,
                        () -> service.validatePredicate(""));
                assertEquals(RuleType.NORM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex2.getRuleType());

                RuleViolationException ex3 = assertThrows(RuleViolationException.class,
                        () -> service.validatePredicate("   "));
                assertEquals(RuleType.NORM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex3.getRuleType());
            }

            @Test
            void simpleThreshold_accepted() {
                assertDoesNotThrow(() -> service.validatePredicate("self.encryptionLevel >= \"AES-128\""));
            }

            @Test
            void booleanAnd_accepted() {
                assertDoesNotThrow(
                        () -> service.validatePredicate("self.tlsEnabled == true && self.tlsVersion >= \"1.2\""));
            }

            @Test
            void disjunction_accepted() {
                assertDoesNotThrow(
                        () -> service.validatePredicate(
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
                assertDoesNotThrow(
                        () -> service.validatePredicate(
                                "self.cipherSuite in [\"TLS_AES_128_GCM_SHA256\", \"TLS_AES_256_GCM_SHA384\"]"));
            }

            @Test
            void stringFunctions_accepted() {
                assertDoesNotThrow(
                        () -> service.validatePredicate(
                                "self.schema.matches(\"^https://.*\") && !self.owner.endsWith(\"-deprecated\")"));
            }

            @Test
            void collectionMacroAll_accepted() {
                assertDoesNotThrow(() -> service.validatePredicate("self.ports.all(p, p >= 1024)"));
            }

            @Test
            void collectionMacroExists_accepted() {
                assertDoesNotThrow(
                        () -> service.validatePredicate(
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
                assertDoesNotThrow(
                        () -> service.validatePredicate(
                                "(self.tier == \"critical\" ? self.p99Latency < 100 : self.p99Latency < 500)"));
            }

            @Test
            void typeConversion_accepted() {
                assertDoesNotThrow(() -> service.validatePredicate("double(self.monthlyBudget) > 0.0"));
            }

            @Test
            void nonDeterministicNow_rejected() {
                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validatePredicate("self.lastReview > now()"));
                assertEquals(RuleType.NORM_PREDICATE_AS_DETERMINISTIC_EXPRESSION, ex.getRuleType());
                assertTrue(ex.getMessage().contains("non-deterministic"));
            }

            @Test
            void nonDeterministicUuid_rejected() {
                RuleViolationException ex = assertThrows(
                        RuleViolationException.class, () -> service.validatePredicate("self.id == uuid()"));
                assertEquals(RuleType.NORM_PREDICATE_AS_DETERMINISTIC_EXPRESSION, ex.getRuleType());
                assertTrue(ex.getMessage().contains("non-deterministic"));
            }

            @Test
            void explicitArchetypeName_rejected() {
                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validatePredicate("SecurityProperties.tlsEnabled == true"));
                assertEquals(RuleType.NORM_PREDICATE_AS_ARCHETYPE_BOUND_EXPRESSION, ex.getRuleType());
                assertTrue(ex.getMessage().contains("self"));
            }

            @Test
            void syntaxError_rejected() {
                RuleViolationException ex = assertThrows(
                        RuleViolationException.class, () -> service.validatePredicate("self.value >>>= 5"));
                assertEquals(RuleType.NORM_PREDICATE_CEL_PARSING, ex.getRuleType());
            }

            // NORM_PREDICATE_AS_BOOLEAN_RESULT

            @Test
            void arithmeticTopLevel_rejected() {
                RuleViolationException ex = assertThrows(
                        RuleViolationException.class, () -> service.validatePredicate("self.a + self.b"));
                assertEquals(RuleType.NORM_PREDICATE_AS_BOOLEAN_RESULT, ex.getRuleType());
                assertTrue(ex.getMessage().contains("arithmetic"));
            }

            @Test
            void nonBooleanConstant_rejected() {
                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validatePredicate("42"));
                assertEquals(RuleType.NORM_PREDICATE_AS_BOOLEAN_RESULT, ex.getRuleType());
                assertTrue(ex.getMessage().contains("non-boolean constant"));
            }

            // NORM_PREDICATE_PROPERTY_PATH_RESOLUTION

            @Test
            void predicatePropertyNotInQualifier_rejected() {
                ObjectNode schema = MAPPER.createObjectNode();
                schema.put("title", "SecurityProperties");
                schema
                        .putObject("properties")
                        .set("tlsEnabled", MAPPER.createObjectNode().put("type", "boolean"));

                ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
                when(qualifier.getStatement()).thenReturn(schema);

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validatePredicatePropertyPaths("self.nonExistentField > 0", qualifier));
                assertEquals(RuleType.NORM_PREDICATE_PROPERTY_PATH_RESOLUTION, ex.getRuleType());
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

            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> NormService.validateToleranceModeConsistency(stmt));
            assertEquals(RuleType.NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
            assertTrue(ex.getMessage().contains("INSTANTANEOUS"));
        }

        @Test
        void aggregated_missingWindow_rejected() {
            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("toleranceMode", "AGGREGATED");
            stmt.put("temporalAggregation", "P99");

            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> NormService.validateToleranceModeConsistency(stmt));
            assertEquals(RuleType.NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
            assertTrue(ex.getMessage().contains("AGGREGATED"));
        }

        @Test
        void aggregated_missingAggregation_rejected() {
            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("toleranceMode", "AGGREGATED");
            stmt.put("temporalWindow", "PT5M");

            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> NormService.validateToleranceModeConsistency(stmt));
            assertEquals(RuleType.NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
            assertTrue(ex.getMessage().contains("AGGREGATED"));
        }

        @Test
        void aggregated_withSustainedThreshold_rejected() {
            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("toleranceMode", "AGGREGATED");
            stmt.put("temporalWindow", "PT5M");
            stmt.put("temporalAggregation", "P99");
            stmt.put("sustainedThreshold", 0.99);

            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> NormService.validateToleranceModeConsistency(stmt));
            assertEquals(RuleType.NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
            assertTrue(ex.getMessage().contains("forbids sustainedThreshold"));
        }

        @Test
        void sustained_missingFields_rejected() {
            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("toleranceMode", "SUSTAINED");
            stmt.put("temporalWindow", "PT5M");

            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> NormService.validateToleranceModeConsistency(stmt));
            assertEquals(RuleType.NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
            assertTrue(ex.getMessage().contains("SUSTAINED"));
        }

        @Test
        void sustained_thresholdOutOfRange_rejected() {
            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("toleranceMode", "SUSTAINED");
            stmt.put("temporalWindow", "PT5M");
            stmt.put("temporalAggregation", "AVG");
            stmt.put("sustainedThreshold", 1.5);

            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> NormService.validateToleranceModeConsistency(stmt));
            assertEquals(RuleType.NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
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
                assertEquals(
                        AscriptionStatusTransitionCascadeType.GOVERNING,
                        roles.get(DefinitionSubjectType.STRUCTURE));
            }
        }
    }

    // ========================================================================
    // BuildEntity
    // ========================================================================

    @Nested
    class BuildEntity {

        @Test
        void validStatement_returnsEntity() {
            UUID structId = UUID.randomUUID();
            UUID qualId = UUID.randomUUID();

            StructureEntity structure = mock(StructureEntity.class);
            when(structureService.findEntityById(structId)).thenReturn(structure);

            ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
            ObjectNode qualSchema = MAPPER.createObjectNode();
            qualSchema.put("title", "TestQual");
            qualSchema
                    .putObject("properties")
                    .set("status", MAPPER.createObjectNode().put("type", "string"));
            when(qualifier.getStatement()).thenReturn(qualSchema);
            when(archetypeService.findEntityById(qualId)).thenReturn(qualifier);

            DefinitionEntity def = mock(DefinitionEntity.class);
            ArchetypeEntity archetype = mock(ArchetypeEntity.class);

            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("structure", structId.toString());
            stmt.put("qualifier", qualId.toString());
            stmt.put("predicate", "self.status == \"OK\"");
            stmt.put("toleranceMode", "INSTANTANEOUS");

            NormEntity result = service.buildEntity(def, archetype, stmt);
            assertEquals(def, result.getDefinition());
            assertEquals(structure, result.getStructure());
            assertEquals(qualifier, result.getQualifier());
        }

        @Test
        void missingStructure_rejected() {
            DefinitionEntity def = mock(DefinitionEntity.class);
            ArchetypeEntity archetype = mock(ArchetypeEntity.class);
            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("qualifier", UUID.randomUUID().toString());
            stmt.put("predicate", "self.x > 1");
            stmt.put("toleranceMode", "INSTANTANEOUS");

            assertThrows(RuleViolationException.class, () -> service.buildEntity(def, archetype, stmt));
        }

        @Test
        void missingQualifier_rejected() {
            UUID structId = UUID.randomUUID();
            StructureEntity structure = mock(StructureEntity.class);
            when(structureService.findEntityById(structId)).thenReturn(structure);

            DefinitionEntity def = mock(DefinitionEntity.class);
            ArchetypeEntity archetype = mock(ArchetypeEntity.class);
            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("structure", structId.toString());
            stmt.put("predicate", "self.x > 1");
            stmt.put("toleranceMode", "INSTANTANEOUS");

            assertThrows(RuleViolationException.class, () -> service.buildEntity(def, archetype, stmt));
        }

        @Test
        void withGuard_validatesGuardReferencesAndPredicate() {
            UUID structId = UUID.randomUUID();
            UUID qualId = UUID.randomUUID();

            StructureEntity structure = mock(StructureEntity.class);
            when(structureService.findEntityById(structId)).thenReturn(structure);

            ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
            ObjectNode qualSchema = MAPPER.createObjectNode();
            qualSchema.put("title", "TestQual");
            qualSchema
                    .putObject("properties")
                    .set("status", MAPPER.createObjectNode().put("type", "string"));
            when(qualifier.getStatement()).thenReturn(qualSchema);
            when(archetypeService.findEntityById(qualId)).thenReturn(qualifier);

            // Guard references DeploymentProps — stub the archetype lookup
            ArchetypeEntity deployArch = mock(ArchetypeEntity.class);
            ObjectNode deploySchema = MAPPER.createObjectNode();
            deploySchema.put("title", "DeploymentProperties");
            deploySchema
                    .putObject("properties")
                    .set("environment", MAPPER.createObjectNode().put("type", "string"));
            when(deployArch.getStatement()).thenReturn(deploySchema);
            when(archetypeService.findInEffectByTitle("DeploymentProperties"))
                    .thenReturn(Optional.of(deployArch));

            DefinitionEntity def = mock(DefinitionEntity.class);
            ArchetypeEntity archetype = mock(ArchetypeEntity.class);
            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("structure", structId.toString());
            stmt.put("qualifier", qualId.toString());
            stmt.put("guard", "DeploymentProperties.environment == \"production\"");
            stmt.put("predicate", "self.status == \"OK\"");
            stmt.put("toleranceMode", "INSTANTANEOUS");

            NormEntity result = service.buildEntity(def, archetype, stmt);
            assertEquals(def, result.getDefinition());
        }

        @Test
        void withGuard_trueDefault_skipsGuardReferences() {
            UUID structId = UUID.randomUUID();
            UUID qualId = UUID.randomUUID();

            StructureEntity structure = mock(StructureEntity.class);
            when(structureService.findEntityById(structId)).thenReturn(structure);

            ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
            ObjectNode qualSchema = MAPPER.createObjectNode();
            qualSchema.put("title", "TestQual");
            qualSchema
                    .putObject("properties")
                    .set("status", MAPPER.createObjectNode().put("type", "string"));
            when(qualifier.getStatement()).thenReturn(qualSchema);
            when(archetypeService.findEntityById(qualId)).thenReturn(qualifier);

            DefinitionEntity def = mock(DefinitionEntity.class);
            ArchetypeEntity archetype = mock(ArchetypeEntity.class);
            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("structure", structId.toString());
            stmt.put("qualifier", qualId.toString());
            stmt.put("guard", "true");
            stmt.put("predicate", "self.status == \"OK\"");
            stmt.put("toleranceMode", "INSTANTANEOUS");

            NormEntity result = service.buildEntity(def, archetype, stmt);
            assertEquals(def, result.getDefinition());
        }
    }

    // ========================================================================
    // FindCascadeTargetsFrom
    // ========================================================================

    @Nested
    class FindCascadeTargetsFromTests {

        @Test
        void structureSource_delegatesToRepo() {
            UUID sourceId = UUID.randomUUID();
            NormEntity n1 = mock(NormEntity.class);
            when(normRepo.findAllByStructureId(sourceId)).thenReturn(List.of(n1));

            var result = service.findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, sourceId);
            assertEquals(1, result.size());
        }

        @Test
        void nonStructureSource_returnsEmpty() {
            var result = service.findCascadeTargetsFrom(DefinitionSubjectType.NORM, UUID.randomUUID());
            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // GetSubjectType / GetRepository
    // ========================================================================

    @Test
    void getSubjectType_returnsNorm() {
        assertEquals(DefinitionSubjectType.NORM, service.getSubjectType());
    }

    // ========================================================================
    // ToleranceModeEdgeCases
    // ========================================================================

    @Nested
    class ToleranceModeEdgeCases {

        @Test
        void noToleranceMode_accepted() {
            ObjectNode stmt = MAPPER.createObjectNode();
            assertDoesNotThrow(() -> NormService.validateToleranceModeConsistency(stmt));
        }

        @Test
        void unknownToleranceMode_accepted() {
            ObjectNode stmt = MAPPER.createObjectNode();
            stmt.put("toleranceMode", "UNKNOWN_MODE");
            // unknown mode falls through to default → no exception
            assertDoesNotThrow(() -> NormService.validateToleranceModeConsistency(stmt));
        }
    }

    // ========================================================================
    // GuardExprEdgeCases
    // ========================================================================

    @Nested
    class GuardExprEdgeCases {

        @Test
        void bareFunctionCall_rejected() {
            // A bare function call (non-targeted, non-comparison) → rejected
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateGuard("timestamp(\"2024-01-01T00:00:00Z\")"));
            assertEquals(RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
        }

        @Test
        void guardOperandWithNonArithmeticFunction_rejected() {
            // Function call in comparison operand that is not arithmetic
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateGuard("DeploymentProps.x == size(\"abc\")"));
            assertEquals(RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
        }

        @Test
        void inListWithBooleans_accepted() {
            // in-list with boolean values exercises constantToString BOOLEAN_VALUE branch
            assertDoesNotThrow(() -> service.validateGuard("DeploymentProps.active in [true, false]"));
        }

        @Test
        void inListWithDoubles_accepted() {
            // in-list with double values exercises constantToString DOUBLE_VALUE branch
            assertDoesNotThrow(() -> service.validateGuard("DeploymentProps.score in [1.5, 2.5, 3.5]"));
        }
    }

    // ========================================================================
    // PredicateExprEdgeCases
    // ========================================================================

    @Nested
    class PredicateExprEdgeCases {

        @Test
        void listExprInPredicate_accepted() {
            // LIST branch in validatePredicateExpr
            assertDoesNotThrow(
                    () -> service.validatePredicate("self.tags.exists(t, t in [\"a\", \"b\"])"));
        }

        @Test
        void unknownFunctionAtTopLevel_accepted() {
            // validatePredicateBooleanResult → unknown function → accept (DYN)
            assertDoesNotThrow(() -> service.validatePredicate("self.items.someCustomFunc()"));
        }
    }
}
