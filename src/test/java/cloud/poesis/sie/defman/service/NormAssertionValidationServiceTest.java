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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NormAssertionValidationServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private NormAssertionValidationService service;

  @BeforeEach
  void setUp() {
    CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();
    service = new NormAssertionValidationService(celCompiler);
  }

  // ========================================================================
  // AssertionProfile
  // ========================================================================

  @Nested
  class AssertionProfile {

    @Test
    void emptyAssertion_rejected() {
      RuleViolationException ex1 =
          assertThrows(RuleViolationException.class, () -> service.validateAssertion(null));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ex1.getRuleType());

      RuleViolationException ex2 =
          assertThrows(RuleViolationException.class, () -> service.validateAssertion(""));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ex2.getRuleType());

      RuleViolationException ex3 =
          assertThrows(RuleViolationException.class, () -> service.validateAssertion("   "));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ex3.getRuleType());
    }

    @Test
    void simpleThreshold_accepted() {
      assertDoesNotThrow(() -> service.validateAssertion("encryptionLevel >= \"AES-128\""));
    }

    @Test
    void booleanAnd_accepted() {
      assertDoesNotThrow(
          () -> service.validateAssertion("tlsEnabled == true && tlsVersion >= \"1.2\""));
    }

    @Test
    void disjunction_accepted() {
      assertDoesNotThrow(
          () -> service.validateAssertion("authMethod == \"mTLS\" || authMethod == \"OAuth2\""));
    }

    @Test
    void negation_accepted() {
      assertDoesNotThrow(() -> service.validateAssertion("!allowsAnonymousAccess"));
    }

    @Test
    void crossPropertyComparison_accepted() {
      assertDoesNotThrow(() -> service.validateAssertion("p99Latency < budget"));
    }

    @Test
    void arithmeticThreshold_accepted() {
      assertDoesNotThrow(() -> service.validateAssertion("p99Latency < budget * 1.1"));
    }

    @Test
    void setMembership_accepted() {
      assertDoesNotThrow(
          () ->
              service.validateAssertion(
                  "cipherSuite in [\"TLS_AES_128_GCM_SHA256\", \"TLS_AES_256_GCM_SHA384\"]"));
    }

    @Test
    void stringFunctions_accepted() {
      assertDoesNotThrow(
          () ->
              service.validateAssertion(
                  "schema.matches(\"^https://.*\") && !owner.endsWith(\"-deprecated\")"));
    }

    @Test
    void collectionMacroAll_accepted() {
      assertDoesNotThrow(() -> service.validateAssertion("ports.all(p, p >= 1024)"));
    }

    @Test
    void collectionMacroExists_accepted() {
      assertDoesNotThrow(
          () ->
              service.validateAssertion(
                  "certifications.exists(c, c == \"SOC2\" || c == \"ISO27001\")"));
    }

    @Test
    void hasFieldPresence_accepted() {
      assertDoesNotThrow(() -> service.validateAssertion("has(config.retentionPolicy)"));
    }

    @Test
    void sizeCheck_accepted() {
      assertDoesNotThrow(() -> service.validateAssertion("tags.size() >= 1"));
    }

    @Test
    void ternary_accepted() {
      assertDoesNotThrow(
          () ->
              service.validateAssertion(
                  "(tier == \"critical\" ? p99Latency < 100 : p99Latency < 500)"));
    }

    @Test
    void typeConversion_accepted() {
      assertDoesNotThrow(() -> service.validateAssertion("double(monthlyBudget) > 0.0"));
    }

    @Test
    void nonDeterministicNow_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateAssertion("lastReview > now()"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_ASSERTION_AS_DETERMINISTIC_EXPRESSION,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("non-deterministic"));
    }

    @Test
    void nonDeterministicUuid_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateAssertion("id == uuid()"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_ASSERTION_AS_DETERMINISTIC_EXPRESSION,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("non-deterministic"));
    }

    @Test
    void explicitArchetypeName_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateAssertion("SecurityProperties.tlsEnabled == true"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_ASSERTION_AS_ARCHETYPE_BOUND_EXPRESSION,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("bare qualifier property"));
    }

    @Test
    void selfPrefix_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateAssertion("self.tlsEnabled == true"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_ASSERTION_AS_ARCHETYPE_BOUND_EXPRESSION,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("bare qualifier property"));
    }

    @Test
    void syntaxError_rejected() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateAssertion("value >>>= 5"));
      assertEquals(AscriptionConsistencyRuleType.NORM_ASSERTION_CEL_PARSING, ex.getRuleType());
    }

    // NORM_ASSERTION_AS_BOOLEAN_RESULT

    @Test
    void arithmeticTopLevel_rejected() {
      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.validateAssertion("a + b"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_ASSERTION_AS_BOOLEAN_RESULT, ex.getRuleType());
      assertTrue(ex.getMessage().contains("arithmetic"));
    }

    @Test
    void nonBooleanConstant_rejected() {
      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.validateAssertion("42"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_ASSERTION_AS_BOOLEAN_RESULT, ex.getRuleType());
      assertTrue(ex.getMessage().contains("non-boolean constant"));
    }

    // NORM_ASSERTION_PROPERTY_PATH_RESOLUTION

    @Test
    void predicatePropertyNotInQualifier_rejected() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "SecurityProperties");
      schema
          .putObject("properties")
          .set("tlsEnabled", MAPPER.createObjectNode().put("type", "boolean"));

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(qualifier.getStatement()).thenReturn(schema);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateAssertionPropertyPaths("nonExistentField > 0", qualifier));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_ASSERTION_PROPERTY_PATH_RESOLUTION, ex.getRuleType());
      assertTrue(ex.getMessage().contains("nonExistentField"));
    }
  }

  // ========================================================================
  // AssertionExprEdgeCases
  // ========================================================================

  @Nested
  class AssertionExprEdgeCases {

    @Test
    void listExprInAssertion_accepted() {
      assertDoesNotThrow(() -> service.validateAssertion("tags.exists(t, t in [\"a\", \"b\"])"));
    }

    @Test
    void unknownFunctionAtTopLevel_accepted() {
      assertDoesNotThrow(() -> service.validateAssertion("items.someCustomFunc()"));
    }

    @Test
    void nonBooleanConstantAtTopLevel_rejected() {
      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.validateAssertion("42"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_ASSERTION_AS_BOOLEAN_RESULT, ex.getRuleType());
      assertTrue(ex.getMessage().contains("non-boolean constant"));
    }

    @Test
    void arithmeticAtTopLevel_rejected() {
      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.validateAssertion("1 + 2"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_ASSERTION_AS_BOOLEAN_RESULT, ex.getRuleType());
      assertTrue(ex.getMessage().contains("arithmetic"));
    }

    @Test
    void bareIdentAtTopLevel_accepted() {
      assertDoesNotThrow(() -> service.validateAssertion("x"));
    }

    @Test
    void listAtTopLevel_accepted() {
      assertDoesNotThrow(() -> service.validateAssertion("[1, 2, 3]"));
    }
  }

  // ========================================================================
  // ParseCelEdgeCases
  // ========================================================================

  @Nested
  class ParseCelEdgeCases {

    @Test
    void ternaryInAssertion_accepted() {
      assertDoesNotThrow(() -> service.validateAssertion("x > 0 ? true : false"));
    }

    @Test
    void deepSelectInAssertion_recursesThroughSelectBranch() {
      assertDoesNotThrow(() -> service.validateAssertion("a.b.c > 0"));
    }

    @Test
    void callTargetInAssertion_recursesIntoTarget() {
      assertDoesNotThrow(() -> service.validateAssertion("items.size() > 0"));
    }
  }

  // ========================================================================
  // CollectPropertyIdentsTests
  // ========================================================================

  @Nested
  class CollectPropertyIdentsTests {

    @Test
    void simpleIdent_collected() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestQual");
      schema.putObject("properties").set("x", MAPPER.createObjectNode().put("type", "integer"));

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(qualifier.getStatement()).thenReturn(schema);

      assertDoesNotThrow(() -> service.validateAssertionPropertyPaths("x > 1", qualifier));
    }

    @Test
    void selectExpr_collectsRoot() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestQual");
      ObjectNode configProp = MAPPER.createObjectNode().put("type", "object");
      configProp
          .putObject("properties")
          .set("value", MAPPER.createObjectNode().put("type", "integer"));
      schema.putObject("properties").set("config", configProp);

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(qualifier.getStatement()).thenReturn(schema);

      assertDoesNotThrow(
          () -> service.validateAssertionPropertyPaths("config.value > 1", qualifier));
    }

    @Test
    void callExpr_recursesIntoTargetAndArgs() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestQual");
      schema.putObject("properties").set("items", MAPPER.createObjectNode().put("type", "array"));

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(qualifier.getStatement()).thenReturn(schema);

      assertDoesNotThrow(
          () -> service.validateAssertionPropertyPaths("items.size() > 0", qualifier));
    }

    @Test
    void listExpr_recursesIntoElements() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestQual");
      ObjectNode props = schema.putObject("properties");
      props.set("val", MAPPER.createObjectNode().put("type", "integer"));
      props.set("min", MAPPER.createObjectNode().put("type", "integer"));
      props.set("max", MAPPER.createObjectNode().put("type", "integer"));

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(qualifier.getStatement()).thenReturn(schema);

      assertDoesNotThrow(
          () -> service.validateAssertionPropertyPaths("val in [min, max]", qualifier));
    }

    @Test
    void macroCall_iterVarExcludedFromCollection() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestQual");
      ObjectNode props = schema.putObject("properties");
      props.set("ports", MAPPER.createObjectNode().put("type", "array"));
      props.set("p", MAPPER.createObjectNode().put("type", "integer"));

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(qualifier.getStatement()).thenReturn(schema);

      assertDoesNotThrow(
          () -> service.validateAssertionPropertyPaths("ports.all(p, p >= 1024)", qualifier));
    }

    @Test
    void unresolvedProperty_rejected() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestQual");
      schema.putObject("properties").set("x", MAPPER.createObjectNode().put("type", "integer"));

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(qualifier.getStatement()).thenReturn(schema);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateAssertionPropertyPaths("unknownProp > 0", qualifier));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_ASSERTION_PROPERTY_PATH_RESOLUTION, ex.getRuleType());
      assertTrue(ex.getMessage().contains("unknownProp"));
    }

    @Test
    void unresolvedProperty_noTitleInSchema_usesUnknownFallback() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.putObject("properties").set("x", MAPPER.createObjectNode().put("type", "integer"));

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(qualifier.getStatement()).thenReturn(schema);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateAssertionPropertyPaths("missing > 0", qualifier));
      assertTrue(ex.getMessage().contains("(unknown)"));
    }

    @Test
    void boundVarInSelect_excluded() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestQual");
      ObjectNode props = schema.putObject("properties");
      props.set("items", MAPPER.createObjectNode().put("type", "array"));
      props.set("x", MAPPER.createObjectNode().put("type", "object"));

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(qualifier.getStatement()).thenReturn(schema);

      assertDoesNotThrow(
          () -> service.validateAssertionPropertyPaths("items.exists(x, x.val > 0)", qualifier));
    }
  }
}
