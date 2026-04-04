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
import java.util.Optional;
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

  @Mock private ArchetypeService archetypeService;

  private NormApplicabilityValidationService service;

  @BeforeEach
  void setUp() {
    CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();
    service = new NormApplicabilityValidationService(archetypeService, celCompiler);
  }

  // ========================================================================
  // ApplicabilityProfile
  // ========================================================================

  @Nested
  class ApplicabilityProfile {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"true", " true ", "  "})
    void unconditionalApplicability_accepted(String applicability) {
      assertDoesNotThrow(() -> service.validateApplicability(applicability));
    }

    @Test
    void singleAxisEquality_accepted() {
      assertDoesNotThrow(
          () ->
              service.validateApplicability("DeploymentProperties.environment == \"production\""));
    }

    @Test
    void singleAxisInequality_accepted() {
      assertDoesNotThrow(
          () -> service.validateApplicability("PerformanceProperties.criticality >= 3"));
    }

    @Test
    void singleAxisSetMembership_accepted() {
      assertDoesNotThrow(
          () ->
              service.validateApplicability(
                  "DeploymentProperties.tier in [\"production\", \"staging\"]"));
    }

    @Test
    void singleAxisNegatedSetMembership_accepted() {
      assertDoesNotThrow(
          () ->
              service.validateApplicability(
                  "!(DeploymentProperties.region in [\"cn-north-1\", \"cn-northwest-1\"])"));
    }

    @Test
    void singleAxisRegexMatch_accepted() {
      assertDoesNotThrow(
          () -> service.validateApplicability("ServiceProperties.name.matches(\"^payment-.*\")"));
    }

    @Test
    void multiAxisConjunction_accepted() {
      assertDoesNotThrow(
          () ->
              service.validateApplicability(
                  "DeploymentProperties.environment == \"production\" "
                      + "&& ServiceProperties.classification == \"PII\""));
    }

    @Test
    void threeAxisConjunction_accepted() {
      assertDoesNotThrow(
          () ->
              service.validateApplicability(
                  "DeploymentProperties.environment == \"production\" "
                      + "&& DataProperties.classification == \"confidential\" "
                      + "&& ServiceProperties.owner != \"deprecated-team\""));
    }

    @Test
    void disjunction_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateApplicability(
                      "DeploymentProperties.env == \"prod\" "
                          + "|| DeploymentProperties.env == \"staging\""));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("||"));
      assertTrue(ex.getMessage().contains("forbidden"));
    }

    @Test
    void ternary_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateApplicability(
                      "DeploymentProperties.env == \"prod\" ? true : false"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("ternary"));
    }

    @Test
    void arithmetic_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateApplicability("PerformanceProperties.score + 1 > 5"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("arithmetic"));
    }

    @Test
    void crossPropertyComparison_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateApplicability(
                      "PerformanceProperties.actual > PerformanceProperties.budget"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          ex.getRuleType());
      assertTrue(
          ex.getMessage().contains("cross-property") || ex.getMessage().contains("duplicate axis"));
    }

    @Test
    void duplicateAxis_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateApplicability(
                      "DeploymentProperties.env == \"prod\" "
                          + "&& DeploymentProperties.env == \"staging\""));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("duplicate axis"));
    }

    @Test
    void forbiddenFunction_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateApplicability("DeploymentProperties.tags.size() > 0"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("matches()") || ex.getMessage().contains("forbidden"));
    }

    @Test
    void syntaxError_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateApplicability("DeploymentProperties.env ==== \"prod\""));
      assertEquals(AscriptionConsistencyRuleType.NORM_APPLICABILITY_CEL_PARSING, ex.getRuleType());
      assertTrue(ex.getMessage().contains("parse error"));
    }

    // NORM_APPLICABILITY_COMPARISON_CONSISTENCY

    @Test
    void inListSingleElement_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateApplicability("DeploymentProperties.env in [\"prod\"]"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains(">= 2"));
    }

    @Test
    void inListMixedTypes_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateApplicability("DeploymentProperties.tier in [\"prod\", 1]"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("type-homogeneous"));
    }

    @Test
    void inListDuplicates_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateApplicability(
                      "DeploymentProperties.tier in [\"prod\", \"staging\", \"prod\"]"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Duplicate"));
    }

    // NORM_APPLICABILITY_ARCHETYPE_REFERENCE_RESOLUTION

    @Test
    void applicabilityArchetypeNotFound_rejected() {
      when(archetypeService.findInEffectByTitle("NonExistent")).thenReturn(Optional.empty());

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateApplicabilityReferences(
                      "NonExistent.environment == \"production\""));
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
      when(archetypeService.findInEffectByTitle("DeploymentProperties"))
          .thenReturn(Optional.of(archetype));

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateApplicabilityReferences(
                      "DeploymentProperties.nonExistentProp == \"x\""));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_PROPERTY_PATH_RESOLUTION,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("nonExistentProp"));
    }

    @Test
    void matchesFunctionCall_exercisesCollectAxesCallTarget() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "ServiceProperties");
      schema.putObject("properties").set("name", MAPPER.createObjectNode().put("type", "string"));

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(schema);
      when(archetypeService.findInEffectByTitle("ServiceProperties"))
          .thenReturn(Optional.of(archetype));

      assertDoesNotThrow(
          () ->
              service.validateApplicabilityReferences(
                  "ServiceProperties.name.matches(\"^payment-.*\")"));
    }

    @Test
    void applicabilityReferences_propertyResolved_accepted() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "DeploymentProperties");
      schema.putObject("properties").set("region", MAPPER.createObjectNode().put("type", "string"));

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(schema);
      when(archetypeService.findInEffectByTitle("DeploymentProperties"))
          .thenReturn(Optional.of(archetype));

      assertDoesNotThrow(
          () ->
              service.validateApplicabilityReferences(
                  "DeploymentProperties.region == \"us-east-1\""));
    }
  }

  // ========================================================================
  // ApplicabilityExprEdgeCases
  // ========================================================================

  @Nested
  class ApplicabilityExprEdgeCases {

    @Test
    void bareFunctionCall_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateApplicability("timestamp(\"2024-01-01T00:00:00Z\")"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          ex.getRuleType());
    }

    @Test
    void topLevelArithmetic_rejected() {
      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.validateApplicability("1 + 2"));
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
    void bareMatchOnIdent_axisNull_accepted() {
      assertDoesNotThrow(() -> service.validateApplicability("x.matches(\"^a\")"));
    }

    @Test
    void inOperatorWithIdentRhs_accepted() {
      assertDoesNotThrow(() -> service.validateApplicability("DeploymentProps.env in otherList"));
    }

    @Test
    void guardOperandWithNonArithmeticFunction_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateApplicability("DeploymentProps.x == size(\"abc\")"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          ex.getRuleType());
    }

    @Test
    void inListWithBooleans_accepted() {
      assertDoesNotThrow(
          () -> service.validateApplicability("DeploymentProps.active in [true, false]"));
    }

    @Test
    void duplicateMatchesAxis_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateApplicability(
                      "DeploymentProps.name.matches(\"^a\") "
                          + "&& DeploymentProps.name.matches(\"^b\")"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("duplicate axis"));
    }

    @Test
    void funcCallTargetInMatches_axisResolvesToNull_accepted() {
      assertDoesNotThrow(() -> service.validateApplicability("func().b.matches(\"^x\")"));
    }

    @Test
    void inListWithDoubles_accepted() {
      assertDoesNotThrow(
          () -> service.validateApplicability("DeploymentProps.score in [1.5, 2.5, 3.5]"));
    }
  }

  // ========================================================================
  // ConstantToStringEdgeCases
  // ========================================================================

  @Nested
  class ConstantToStringEdgeCases {

    @Test
    void integerValues_accepted() {
      assertDoesNotThrow(() -> service.validateApplicability("DeploymentProps.level in [1, 2, 3]"));
    }

    @Test
    void uintValues_duplicateDetected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateApplicability("DeploymentProps.level in [1, 2, 1]"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Duplicate"));
    }

    @Test
    void uintLiterals_accepted() {
      assertDoesNotThrow(
          () -> service.validateApplicability("DeploymentProps.level in [2u, 3u, 4u]"));
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
          () -> service.validateApplicability("DeploymentProperties.config.env == \"production\""));
    }

    @Test
    void threeLevelSelect_extractsRootAndFirstField() {
      assertDoesNotThrow(
          () ->
              service.validateApplicability(
                  "DeploymentProperties.config.nested.env == \"production\""));
    }

    @Test
    void deepAndShallowOnSameAxis_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateApplicability(
                      "DeploymentProperties.config.env == \"prod\" "
                          + "&& DeploymentProperties.config.region == \"us\""));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("duplicate axis"));
    }
  }

  // ========================================================================
  // ApplicabilityExprAdditionalBranches
  // ========================================================================

  @Nested
  class ApplicabilityExprAdditionalBranches {

    @Test
    void existsMacroInApplicability_rejectedAsFunctionCall() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateApplicability(
                      "DeploymentProperties.items.exists(x, x == \"prod\")"));
      assertTrue(ex.getMessage().contains("matches"));
    }

    @Test
    void nestedNegation_accepted() {
      assertDoesNotThrow(
          () ->
              service.validateApplicability(
                  "!(DeploymentProperties.tier in [\"internal\", \"dev\"])"));
    }

    @Test
    void bareIdentOnlyInComparison_accepted() {
      assertDoesNotThrow(() -> service.validateApplicability("DeploymentProperties.flag == true"));
    }

    @Test
    void listOperandInComparison_accepted() {
      assertDoesNotThrow(
          () ->
              service.validateApplicability("DeploymentProperties.env in [\"prod\", \"staging\"]"));
    }

    @Test
    void negatedComparison_accepted() {
      assertDoesNotThrow(() -> service.validateApplicability("!(DeploymentProperties.level == 1)"));
    }

    @Test
    void inListWithNullElement_rejectsAsMixedType() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateApplicability("DeploymentProperties.x in [null, 1]"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
          ex.getRuleType());
    }
  }
}
