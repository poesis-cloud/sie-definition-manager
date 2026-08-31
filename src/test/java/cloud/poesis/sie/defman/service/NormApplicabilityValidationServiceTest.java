package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import java.util.List;
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
class NormApplicabilityValidationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEPLOYMENT_REF = "ref(\"gsmarc://tenant/DeploymentProperties/v1\")";
    private static final String PERFORMANCE_REF = "ref(\"gsmarc://tenant/PerformanceProperties/v1\")";
    private static final String SERVICE_REF = "ref(\"gsmarc://tenant/ServiceProperties/v1\")";
    private static final String DATA_REF = "ref(\"gsmarc://tenant/DataProperties/v1\")";
    private static final String DEPLOYMENT_FACET_ID = "gsmarc://tenant/DeploymentEnvironmentFacet/v1";

    @Mock
    private ArchetypeService archetypeService;

    private NormApplicabilityValidationService service;

    @BeforeEach
    void setUp() {
        CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();
        service = new NormApplicabilityValidationService(
                archetypeService, new ArchetypeCompositionValidationService(), celCompiler);
    }

    // ========================================================================
    // ApplicabilityProfile
    // ========================================================================

    @Nested
    class ApplicabilityProfile {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "true", " true ", "  " })
        void unconditionalApplicability_accepted(String applicability) {
            assertDoesNotThrow(() -> service.validateApplicability(applicability));
        }

        @Test
        void archetypeUriAxisEquality_accepted() {
            assertDoesNotThrow(
                    () -> service.validateApplicability(
                            "ref(\"gsmarc://tenant/DeploymentProperties/v1\").environment == \"production\""));
        }

        @Test
        void bareTitleAxisEquality_rejected() {
            RuleViolationException exception = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(
                            "DeploymentProperties.environment == \"production\""));

            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    exception.getRuleType());
            assertTrue(exception.getMessage().contains("Bare title"));
        }

        @ParameterizedTest
        @ValueSource(strings = { "false", "environment", "DeploymentProperties.environment" })
        void nonPredicateExpression_rejected(String applicability) {
            RuleViolationException exception = assertThrows(
                    RuleViolationException.class, () -> service.validateApplicability(applicability));

            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    exception.getRuleType());
        }

        @Test
        void malformedRefIdentity_rejected() {
            RuleViolationException exception = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability("ref(\"not-a-gsmarc-id\").environment == 1"));

            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    exception.getRuleType());
        }

        @Test
        void nonLiteralRefArgument_rejected() {
            RuleViolationException exception = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability("ref(archetypeId).environment == 1"));

            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    exception.getRuleType());
        }

        @Test
        void applicabilityReferences_areDistinctInEncounterOrder() {
            assertEquals(
                    List.of(
                            "gsmarc://tenant/DeploymentProperties/v1", "gsmarc://tenant/ServiceProperties/v2"),
                    service.extractApplicabilityReferences(
                            DEPLOYMENT_REF
                                    + ".environment == \"production\" && "
                                    + "ref(\"gsmarc://tenant/ServiceProperties/v2\").classification == \"PII\" && "
                                    + DEPLOYMENT_REF
                                    + ".region == \"us-east-1\""));
        }

        @Test
        void singleAxisInequality_accepted() {
            assertDoesNotThrow(
                    () -> service.validateApplicability(PERFORMANCE_REF + ".criticality >= 3"));
        }

        @Test
        void singleAxisSetMembership_accepted() {
            assertDoesNotThrow(
                    () -> service.validateApplicability(
                            DEPLOYMENT_REF + ".tier in [\"production\", \"staging\"]"));
        }

        @Test
        void singleAxisNegatedSetMembership_accepted() {
            assertDoesNotThrow(
                    () -> service.validateApplicability(
                            "!(" + DEPLOYMENT_REF + ".region in [\"cn-north-1\", \"cn-northwest-1\"])"));
        }

        @Test
        void negatedConjunction_rejected() {
            RuleViolationException exception = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(
                            "!("
                                    + DEPLOYMENT_REF
                                    + ".environment == \"production\" && "
                                    + SERVICE_REF
                                    + ".classification == \"PII\")"));

            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    exception.getRuleType());
            assertTrue(exception.getMessage().contains("compound"));
        }

        @Test
        void singleAxisRegexMatch_accepted() {
            assertDoesNotThrow(
                    () -> service.validateApplicability(SERVICE_REF + ".name.matches(\"^payment-.*\")"));
        }

        @Test
        void multiAxisConjunction_accepted() {
            assertDoesNotThrow(
                    () -> service.validateApplicability(
                            DEPLOYMENT_REF
                                    + ".environment == \"production\" && "
                                    + SERVICE_REF
                                    + ".classification == \"PII\""));
        }

        @Test
        void threeAxisConjunction_accepted() {
            assertDoesNotThrow(
                    () -> service.validateApplicability(
                            DEPLOYMENT_REF
                                    + ".environment == \"production\" && "
                                    + DATA_REF
                                    + ".classification == \"confidential\" && "
                                    + SERVICE_REF
                                    + ".owner != \"deprecated-team\""));
        }

        @Test
        void disjunction_rejected() {
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(
                            DEPLOYMENT_REF
                                    + ".env == \"prod\" || "
                                    + DEPLOYMENT_REF
                                    + ".env == \"staging\""));
            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    ex.getRuleType());
            assertTrue(ex.getMessage().contains("||"));
            assertTrue(ex.getMessage().contains("forbidden"));
        }

        @Test
        void ternary_rejected() {
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(
                            DEPLOYMENT_REF + ".env == \"prod\" ? true : false"));
            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    ex.getRuleType());
            assertTrue(ex.getMessage().contains("ternary"));
        }

        @Test
        void arithmetic_rejected() {
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(PERFORMANCE_REF + ".score + 1 > 5"));
            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    ex.getRuleType());
            assertTrue(ex.getMessage().contains("arithmetic"));
        }

        @Test
        void crossPropertyComparison_rejected() {
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(
                            PERFORMANCE_REF + ".actual > " + PERFORMANCE_REF + ".budget"));
            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    ex.getRuleType());
            assertTrue(ex.getMessage().contains("Found axes"));
        }

        @Test
        void sameAxisSelfComparison_rejected() {
            RuleViolationException exception = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(
                            DEPLOYMENT_REF + ".environment == " + DEPLOYMENT_REF + ".environment"));

            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    exception.getRuleType());
            assertTrue(exception.getMessage().contains("static literal"));
        }

        @ParameterizedTest
        @ValueSource(strings = { "ref(\"gsmarc://tenant/ServiceProperties/v1\").pattern", "42", "true" })
        void matchesNonStringLiteral_rejected(String argument) {
            RuleViolationException exception = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(SERVICE_REF + ".name.matches(" + argument + ")"));

            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    exception.getRuleType());
            assertTrue(exception.getMessage().contains("string literal"));
        }

        @Test
        void mixedBareTitleAndUriAxis_reportsBareTitle() {
            RuleViolationException exception = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(
                            "DeploymentProperties.environment == " + DEPLOYMENT_REF + ".environment"));

            assertTrue(exception.getMessage().contains("Bare title"));
        }

        @Test
        void duplicateAxis_rejected() {
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(
                            DEPLOYMENT_REF
                                    + ".env == \"prod\" && "
                                    + DEPLOYMENT_REF
                                    + ".env == \"staging\""));
            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    ex.getRuleType());
            assertTrue(ex.getMessage().contains("duplicate axis"));
        }

        @Test
        void forbiddenFunction_rejected() {
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(DEPLOYMENT_REF + ".tags.size() > 0"));
            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    ex.getRuleType());
            assertTrue(ex.getMessage().contains("matches()") || ex.getMessage().contains("forbidden"));
        }

        @Test
        void syntaxError_rejected() {
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(DEPLOYMENT_REF + ".env ==== \"prod\""));
            assertEquals(AscriptionConsistencyRuleType.NORM_APPLICABILITY_CEL_PARSING, ex.getRuleType());
            assertTrue(ex.getMessage().contains("parse error"));
        }

        // NORM_APPLICABILITY_COMPARISON_CONSISTENCY

        @Test
        void inListSingleElement_rejected() {
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(DEPLOYMENT_REF + ".env in [\"prod\"]"));
            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
                    ex.getRuleType());
            assertTrue(ex.getMessage().contains(">= 2"));
        }

        @Test
        void inScalar_rejected() {
            RuleViolationException exception = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(DEPLOYMENT_REF + ".env in \"prod\""));

            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    exception.getRuleType());
            assertTrue(exception.getMessage().contains("list literal"));
        }

        @Test
        void inListMixedTypes_rejected() {
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(DEPLOYMENT_REF + ".tier in [\"prod\", 1]"));
            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
                    ex.getRuleType());
            assertTrue(ex.getMessage().contains("type-homogeneous"));
        }

        @Test
        void inListDuplicates_rejected() {
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(
                            DEPLOYMENT_REF + ".tier in [\"prod\", \"staging\", \"prod\"]"));
            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
                    ex.getRuleType());
            assertTrue(ex.getMessage().contains("Duplicate"));
        }

        // NORM_APPLICABILITY_ARCHETYPE_REFERENCE_RESOLUTION

        @Test
        void applicabilityArchetypeNotFound_rejected() {
            String archetypeId = "gsmarc://tenant/NonExistent/v1";
            when(archetypeService.resolveArchetypeUri(archetypeId, "applicability ref()"))
                    .thenThrow(
                            RuleViolationException.of(
                                    AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY,
                                    "Archetype reference does not resolve: " + archetypeId,
                                    "value",
                                    archetypeId));

            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicabilityReferences(
                            "ref(\"" + archetypeId + "\").environment == \"production\""));
            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_ARCHETYPE_REFERENCE_RESOLUTION,
                    ex.getRuleType());
            assertTrue(ex.getMessage().contains("NonExistent"));
        }

        // NORM_APPLICABILITY_PROPERTY_PATH_RESOLUTION

        @Test
        void applicabilityPropertyNotInSchema_rejected() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("title", "DeploymentProperties");
            schema.putObject("properties").set("region", MAPPER.createObjectNode().put("type", "string"));

            ArchetypeEntity archetype = mock(ArchetypeEntity.class);
            when(archetype.getStatement()).thenReturn(schema);
            when(archetypeService.resolveArchetypeUri(
                    "gsmarc://tenant/DeploymentProperties/v1", "applicability ref()"))
                    .thenReturn(archetype);

            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicabilityReferences(
                            DEPLOYMENT_REF + ".nonExistentProp == \"x\""));
            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_PROPERTY_PATH_RESOLUTION,
                    ex.getRuleType());
            assertTrue(ex.getMessage().contains("nonExistentProp"));
        }

        @Test
        void applicabilityPropertyInheritedFromAllOfFacet_accepted() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("title", "DeploymentProperties");
            schema.putArray("allOf").addObject().put("$ref", DEPLOYMENT_FACET_ID);

            ObjectNode facetSchema = MAPPER.createObjectNode();
            facetSchema.put("title", "DeploymentEnvironmentFacet");
            facetSchema
                    .putObject("properties")
                    .putObject("deployment")
                    .putObject("properties")
                    .set("environment", MAPPER.createObjectNode().put("type", "string"));

            ArchetypeEntity archetype = mock(ArchetypeEntity.class);
            when(archetype.getStatement()).thenReturn(schema);
            ArchetypeEntity facet = mock(ArchetypeEntity.class);
            when(facet.getStatement()).thenReturn(facetSchema);
            when(archetypeService.resolveArchetypeUri(
                    "gsmarc://tenant/DeploymentProperties/v1", "applicability ref()"))
                    .thenReturn(archetype);
            when(archetypeService.resolveArchetypeUri(
                    DEPLOYMENT_FACET_ID, "applicability schema composition"))
                    .thenReturn(facet);

            assertDoesNotThrow(
                    () -> service.validateApplicabilityReferences(
                            DEPLOYMENT_REF + ".deployment.environment == \"production\""));
        }

        @Test
        void applicabilityPropertyResolvedThroughLocalDefinition_accepted() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("title", "DeploymentProperties");
            schema
                    .putObject("properties")
                    .putObject("deployment")
                    .put("$ref", "#/$defs/DeploymentEnvironment");
            schema
                    .putObject("$defs")
                    .putObject("DeploymentEnvironment")
                    .putObject("properties")
                    .set("environment", MAPPER.createObjectNode().put("type", "string"));

            ArchetypeEntity archetype = mock(ArchetypeEntity.class);
            when(archetype.getStatement()).thenReturn(schema);
            when(archetypeService.resolveArchetypeUri(
                    "gsmarc://tenant/DeploymentProperties/v1", "applicability ref()"))
                    .thenReturn(archetype);

            assertDoesNotThrow(
                    () -> service.validateApplicabilityReferences(
                            DEPLOYMENT_REF + ".deployment.environment == \"production\""));
        }

        @Test
        void applicabilityPropertyResolvedThroughRecursiveLocalReference_accepted() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("title", "DeploymentProperties");
            schema.putObject("properties").set("name", MAPPER.createObjectNode().put("type", "string"));
            schema.withObject("/properties").putObject("child").put("$ref", "#");

            ArchetypeEntity archetype = mock(ArchetypeEntity.class);
            when(archetype.getStatement()).thenReturn(schema);
            when(archetypeService.resolveArchetypeUri(
                    "gsmarc://tenant/DeploymentProperties/v1", "applicability ref()"))
                    .thenReturn(archetype);

            assertDoesNotThrow(
                    () -> service.validateApplicabilityReferences(
                            DEPLOYMENT_REF + ".child.child.name == \"worker\""));
        }

        @Test
        void applicabilityPropertyResolvedThroughRecursiveExternalReference_accepted() {
            String nodeId = "gsmarc://tenant/DeploymentNode/v1";
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("title", "DeploymentProperties");
            schema.putObject("properties").putObject("root").put("$ref", nodeId);

            ObjectNode nodeSchema = MAPPER.createObjectNode();
            nodeSchema.put("$id", nodeId);
            nodeSchema.put("title", "DeploymentNode");
            nodeSchema
                    .putObject("properties")
                    .set("name", MAPPER.createObjectNode().put("type", "string"));
            nodeSchema.withObject("/properties").putObject("child").put("$ref", nodeId);

            ArchetypeEntity archetype = mock(ArchetypeEntity.class);
            when(archetype.getStatement()).thenReturn(schema);
            ArchetypeEntity node = mock(ArchetypeEntity.class);
            when(node.getStatement()).thenReturn(nodeSchema);
            when(archetypeService.resolveArchetypeUri(
                    "gsmarc://tenant/DeploymentProperties/v1", "applicability ref()"))
                    .thenReturn(archetype);
            when(archetypeService.resolveArchetypeUri(nodeId, "applicability schema composition"))
                    .thenReturn(node);

            assertDoesNotThrow(
                    () -> service.validateApplicabilityReferences(
                            DEPLOYMENT_REF + ".root.child.child.name == \"worker\""));
        }

        @Test
        void unsupportedLocalAnchorReference_failsClosed() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("title", "DeploymentProperties");
            schema.putObject("properties").putObject("deployment").put("$ref", "#environment");

            ArchetypeEntity archetype = mock(ArchetypeEntity.class);
            when(archetype.getStatement()).thenReturn(schema);
            when(archetypeService.resolveArchetypeUri(
                    "gsmarc://tenant/DeploymentProperties/v1", "applicability ref()"))
                    .thenReturn(archetype);

            RuleViolationException exception = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicabilityReferences(
                            DEPLOYMENT_REF + ".deployment.environment == \"production\""));

            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_PROPERTY_PATH_RESOLUTION,
                    exception.getRuleType());
        }

        @Test
        void unresolvedComposedSchemaReference_reportsNormReferenceRule() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("title", "DeploymentProperties");
            schema.putArray("allOf").addObject().put("$ref", DEPLOYMENT_FACET_ID);

            ArchetypeEntity archetype = mock(ArchetypeEntity.class);
            when(archetype.getStatement()).thenReturn(schema);
            when(archetypeService.resolveArchetypeUri(
                    "gsmarc://tenant/DeploymentProperties/v1", "applicability ref()"))
                    .thenReturn(archetype);
            when(archetypeService.resolveArchetypeUri(
                    DEPLOYMENT_FACET_ID, "applicability schema composition"))
                    .thenThrow(
                            RuleViolationException.of(
                                    AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY,
                                    "Archetype reference does not resolve: " + DEPLOYMENT_FACET_ID,
                                    "value",
                                    DEPLOYMENT_FACET_ID));

            RuleViolationException exception = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicabilityReferences(
                            DEPLOYMENT_REF + ".deployment.environment == \"production\""));

            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_ARCHETYPE_REFERENCE_RESOLUTION,
                    exception.getRuleType());
            assertTrue(exception.getMessage().contains(DEPLOYMENT_FACET_ID));
        }

        @Test
        void matchesFunctionCall_exercisesCollectAxesCallTarget() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("title", "ServiceProperties");
            schema.putObject("properties").set("name", MAPPER.createObjectNode().put("type", "string"));

            ArchetypeEntity archetype = mock(ArchetypeEntity.class);
            when(archetype.getStatement()).thenReturn(schema);
            when(archetypeService.resolveArchetypeUri(
                    "gsmarc://tenant/ServiceProperties/v1", "applicability ref()"))
                    .thenReturn(archetype);

            assertDoesNotThrow(
                    () -> service.validateApplicabilityReferences(
                            SERVICE_REF + ".name.matches(\"^payment-.*\")"));
        }

        @Test
        void applicabilityReferences_propertyResolved_accepted() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("title", "DeploymentProperties");
            schema.putObject("properties").set("region", MAPPER.createObjectNode().put("type", "string"));

            ArchetypeEntity archetype = mock(ArchetypeEntity.class);
            when(archetype.getStatement()).thenReturn(schema);
            when(archetypeService.resolveArchetypeUri(
                    "gsmarc://tenant/DeploymentProperties/v1", "applicability ref()"))
                    .thenReturn(archetype);

            assertDoesNotThrow(
                    () -> service.validateApplicabilityReferences(DEPLOYMENT_REF + ".region == \"us-east-1\""));
        }
    }

    // ========================================================================
    // ApplicabilityExprEdgeCases
    // ========================================================================

    @Nested
    class ApplicabilityExprEdgeCases {

        @Test
        void bareFunctionCall_rejected() {
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability("timestamp(\"2024-01-01T00:00:00Z\")"));
            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    ex.getRuleType());
        }

        @Test
        void topLevelArithmetic_rejected() {
            RuleViolationException ex = assertThrows(RuleViolationException.class,
                    () -> service.validateApplicability("1 + 2"));
            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    ex.getRuleType());
            assertTrue(ex.getMessage().contains("arithmetic"));
        }

        @Test
        void bareIdentAsApplicability_noError() {
            assertDoesNotThrow(() -> service.validateApplicability("true"));
        }

        @Test
        void bareMatchOnIdent_rejected() {
            assertThrows(
                    RuleViolationException.class, () -> service.validateApplicability("x.matches(\"^a\")"));
        }

        @Test
        void inOperatorWithIdentRhs_rejected() {
            assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(DEPLOYMENT_REF + ".env in otherList"));
        }

        @Test
        void guardOperandWithNonArithmeticFunction_rejected() {
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(DEPLOYMENT_REF + ".x == size(\"abc\")"));
            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    ex.getRuleType());
        }

        @Test
        void inListWithBooleans_accepted() {
            assertDoesNotThrow(
                    () -> service.validateApplicability(DEPLOYMENT_REF + ".active in [true, false]"));
        }

        @Test
        void duplicateMatchesAxis_rejected() {
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(
                            DEPLOYMENT_REF
                                    + ".name.matches(\"^a\") && "
                                    + DEPLOYMENT_REF
                                    + ".name.matches(\"^b\")"));
            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                    ex.getRuleType());
            assertTrue(ex.getMessage().contains("duplicate axis"));
        }

        @Test
        void funcCallTargetInMatches_rejected() {
            assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability("func().b.matches(\"^x\")"));
        }

        @Test
        void inListWithDoubles_accepted() {
            assertDoesNotThrow(
                    () -> service.validateApplicability(DEPLOYMENT_REF + ".score in [1.5, 2.5, 3.5]"));
        }
    }

    // ========================================================================
    // ConstantToStringEdgeCases
    // ========================================================================

    @Nested
    class ConstantToStringEdgeCases {

        @Test
        void integerValues_accepted() {
            assertDoesNotThrow(
                    () -> service.validateApplicability(DEPLOYMENT_REF + ".level in [1, 2, 3]"));
        }

        @Test
        void uintValues_duplicateDetected() {
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(DEPLOYMENT_REF + ".level in [1, 2, 1]"));
            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
                    ex.getRuleType());
            assertTrue(ex.getMessage().contains("Duplicate"));
        }

        @Test
        void uintLiterals_accepted() {
            assertDoesNotThrow(
                    () -> service.validateApplicability(DEPLOYMENT_REF + ".level in [2u, 3u, 4u]"));
        }
    }

    // ========================================================================
    // ExtractAxisDeepNesting
    // ========================================================================

    @Nested
    class ExtractAxisDeepNesting {

        @Test
        void twoLevelSelect_extractsRootAndFirstField() {
            assertDoesNotThrow(
                    () -> service.validateApplicability(DEPLOYMENT_REF + ".config.env == \"production\""));
        }

        @Test
        void threeLevelSelect_extractsRootAndFirstField() {
            assertDoesNotThrow(
                    () -> service.validateApplicability(
                            DEPLOYMENT_REF + ".config.nested.env == \"production\""));
        }

        @Test
        void distinctDeepPropertyPaths_accepted() {
            assertDoesNotThrow(
                    () -> service.validateApplicability(
                            DEPLOYMENT_REF
                                    + ".config.env == \"prod\" && "
                                    + DEPLOYMENT_REF
                                    + ".config.region == \"us\""));
        }
    }

    // ========================================================================
    // ApplicabilityExprAdditionalBranches
    // ========================================================================

    @Nested
    class ApplicabilityExprAdditionalBranches {

        @Test
        void existsMacroInApplicability_rejectedAsFunctionCall() {
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(
                            DEPLOYMENT_REF + ".items.exists(x, x == \"prod\")"));
            assertTrue(ex.getMessage().contains("matches"));
        }

        @Test
        void nestedNegation_accepted() {
            assertDoesNotThrow(
                    () -> service.validateApplicability(
                            "!(" + DEPLOYMENT_REF + ".tier in [\"internal\", \"dev\"])"));
        }

        @Test
        void bareIdentOnlyInComparison_accepted() {
            assertDoesNotThrow(() -> service.validateApplicability(DEPLOYMENT_REF + ".flag == true"));
        }

        @Test
        void listOperandInComparison_accepted() {
            assertDoesNotThrow(
                    () -> service.validateApplicability(DEPLOYMENT_REF + ".env in [\"prod\", \"staging\"]"));
        }

        @Test
        void negatedComparison_accepted() {
            assertDoesNotThrow(
                    () -> service.validateApplicability("!(" + DEPLOYMENT_REF + ".level == 1)"));
        }

        @Test
        void inListWithNullElement_rejectsAsMixedType() {
            RuleViolationException ex = assertThrows(
                    RuleViolationException.class,
                    () -> service.validateApplicability(DEPLOYMENT_REF + ".x in [null, 1]"));
            assertEquals(
                    AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
                    ex.getRuleType());
        }
    }
}
