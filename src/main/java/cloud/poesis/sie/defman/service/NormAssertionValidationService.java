package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import com.fasterxml.jackson.databind.JsonNode;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.compiler.CelCompiler;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * GSM Norm CEL assertion profile validation service.
 *
 * <p>Validates CEL assertion expressions against the GSM assertion profile: expressions must be
 * deterministic, boolean-producing, and archetype-bound (no {@code self.} or explicit Archetype
 * name prefixes). Also validates assertion property paths against qualifier archetype schemas.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class NormAssertionValidationService {

  private static final Logger LOG = LoggerFactory.getLogger(NormAssertionValidationService.class);

  // ======================================================================
  // CEL profile constants
  // ======================================================================

  private static final Set<String> ASSERTION_FORBIDDEN_FUNCTIONS = Set.of("now", "uuid");

  private static final Set<String> BOOLEAN_PRODUCING_OPS =
      Set.of(
          "_==_",
          "_!=_",
          "_<_",
          "_<=_",
          "_>_",
          "_>=_",
          "_&&_",
          "_||_",
          "!_",
          "_!_",
          "@in",
          "matches",
          "startsWith",
          "endsWith",
          "contains",
          "has",
          "exists",
          "all",
          "exists_one");

  private static final Set<String> APPLICABILITY_ARITHMETIC_OPS =
      Set.of("_+_", "_-_", "_*_", "_%_", "_/_");

  private final CelCompiler celParser;

  public NormAssertionValidationService(CelCompiler celParser) {
    this.celParser = celParser;
  }

  // ======================================================================
  // Public API
  // ======================================================================

  /**
   * Validates a CEL assertion expression against the assertion profile.
   *
   * @param assertion the CEL expression string
   */
  public void validateAssertion(String assertion) {
    if (assertion == null || assertion.isBlank()) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          "Assertion must not be empty",
          "field",
          "assertion");
    }
    CelExpr ast = parseAssertionCel(assertion);
    validateAssertionExpr(ast);
    // NORM_ASSERTION_AS_BOOLEAN_RESULT: top-level must be boolean-producing
    validateAssertionBooleanResult(ast);
  }

  /**
   * Validates assertion property paths against a qualifier archetype schema.
   *
   * @param assertion the CEL expression string
   * @param qualifier the qualifier archetype entity
   */
  public void validateAssertionPropertyPaths(String assertion, ArchetypeEntity qualifier) {
    CelExpr ast = parseAssertionCel(assertion);
    Set<String> paths = new LinkedHashSet<>();
    collectPropertyIdents(ast, paths, new HashSet<>());
    JsonNode schema = qualifier.getStatement();
    for (String path : paths) {
      if (!resolveSchemaProperty(schema, path)) {
        String title = schema.has("title") ? schema.get("title").asText() : "(unknown)";
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.NORM_ASSERTION_PROPERTY_PATH_RESOLUTION,
            "Assertion references '"
                + path
                + "' which does not exist in qualifier Archetype '"
                + title
                + "' schema",
            "qualifierTitle",
            title,
            "propertyPath",
            path);
      }
    }
  }

  // ======================================================================
  // CEL parsing
  // ======================================================================

  private CelExpr parseAssertionCel(String expression) {
    CelValidationResult result = celParser.parse(expression);
    if (result.hasError()) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.NORM_ASSERTION_CEL_PARSING,
          "Assertion CEL parse error: " + result.getErrorString(),
          "field",
          "assertion");
    }
    try {
      return result.getAst().getExpr();
    } catch (CelValidationException e) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.NORM_ASSERTION_CEL_PARSING,
          "Assertion CEL validation error: " + e.getMessage(),
          e,
          "field",
          "assertion");
    }
  }

  // ======================================================================
  // Assertion profile validation
  // ======================================================================

  private void validateAssertionExpr(CelExpr expr) {
    CelExpr.ExprKind kind = expr.exprKind();
    switch (kind.getKind()) {
      case CALL -> {
        CelExpr.CelCall call = kind.call();
        String fn = call.function();
        if (ASSERTION_FORBIDDEN_FUNCTIONS.contains(fn)) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.NORM_ASSERTION_AS_DETERMINISTIC_EXPRESSION,
              "Assertion profile violation: non-deterministic functions (now(), uuid()) are forbidden. "
                  + "Assertion must be deterministic and side-effect-free.",
              "field",
              "assertion",
              "construct",
              fn);
        }
        call.target().ifPresent(this::validateAssertionExpr);
        for (CelExpr arg : call.args()) {
          validateAssertionExpr(arg);
        }
      }
      case SELECT -> {
        CelExpr.CelSelect sel = kind.select();
        CelExpr operand = sel.operand();
        CelExpr root = operand;
        while (root.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT) {
          root = root.exprKind().select().operand();
        }
        if (root.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT) {
          String rootName = root.exprKind().ident().name();
          if ("self".equals(rootName)) {
            throw RuleViolationException.of(
                AscriptionConsistencyRuleType.NORM_ASSERTION_AS_ARCHETYPE_BOUND_EXPRESSION,
                "Assertion profile violation: use bare qualifier property names, "
                    + "not 'self.' prefix. "
                    + "Example: 'encryptionLevel' instead of 'self."
                    + sel.field()
                    + "'.",
                "field",
                "assertion",
                "rootName",
                rootName);
          }
          if (Character.isUpperCase(rootName.charAt(0))) {
            throw RuleViolationException.of(
                AscriptionConsistencyRuleType.NORM_ASSERTION_AS_ARCHETYPE_BOUND_EXPRESSION,
                "Assertion profile violation: use bare qualifier property names, "
                    + "not an explicit Archetype name. "
                    + "Example: 'encryptionLevel' instead of '"
                    + rootName
                    + "."
                    + sel.field()
                    + "'.",
                "field",
                "assertion",
                "rootName",
                rootName);
          }
        }
        validateAssertionExpr(operand);
      }
      case IDENT, CONSTANT -> {
        /* OK — bare identifiers are qualifier properties */
      }
      case LIST -> {
        CelExpr.CelList list = kind.list();
        for (CelExpr el : list.elements()) {
          validateAssertionExpr(el);
        }
      }
      default -> {
        /* comprehension, map, struct — allowed */
      }
    }
  }

  // ======================================================================
  // Boolean result validation
  // ======================================================================

  static void validateAssertionBooleanResult(CelExpr ast) {
    CelExpr.ExprKind kind = ast.exprKind();
    switch (kind.getKind()) {
      case CALL -> {
        String fn = kind.call().function();
        if ("_?_:_".equals(fn)) {
          return; // ternary: result type depends on branches — accept
        }
        if (!BOOLEAN_PRODUCING_OPS.contains(fn) && !APPLICABILITY_ARITHMETIC_OPS.contains(fn)) {
          return; // unknown function — accept (DYN)
        }
        if (APPLICABILITY_ARITHMETIC_OPS.contains(fn)) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.NORM_ASSERTION_AS_BOOLEAN_RESULT,
              "Assertion top-level expression is arithmetic ('" + fn + "') — must evaluate to bool",
              "function",
              fn);
        }
        // known boolean-producing op: OK
      }
      case CONSTANT -> {
        CelConstant c = kind.constant();
        if (c.getKind() != CelConstant.Kind.BOOLEAN_VALUE) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.NORM_ASSERTION_AS_BOOLEAN_RESULT,
              "Assertion top-level expression is a non-boolean constant ("
                  + c.getKind()
                  + ") — must evaluate to bool",
              "constantKind",
              c.getKind().name());
        }
      }
      case IDENT, SELECT -> {
        // DYN — could be bool at runtime; accept
      }
      default -> {
        // LIST, comprehension, etc. — accept (rare/unusual but not provably wrong)
      }
    }
  }

  // ======================================================================
  // Property ident collection
  // ======================================================================

  static void collectPropertyIdents(CelExpr expr, Set<String> paths, Set<String> boundVars) {
    CelExpr.ExprKind kind = expr.exprKind();
    switch (kind.getKind()) {
      case IDENT -> {
        String name = kind.ident().name();
        if (!boundVars.contains(name)) {
          paths.add(name);
        }
      }
      case SELECT -> {
        CelExpr root = expr;
        while (root.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT) {
          root = root.exprKind().select().operand();
        }
        if (root.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT) {
          String rootName = root.exprKind().ident().name();
          if (!boundVars.contains(rootName)) {
            paths.add(rootName);
          }
        }
      }
      case CALL -> {
        CelExpr.CelCall call = kind.call();
        call.target().ifPresent(t -> collectPropertyIdents(t, paths, boundVars));
        for (CelExpr arg : call.args()) {
          collectPropertyIdents(arg, paths, boundVars);
        }
      }
      case LIST -> {
        for (CelExpr el : kind.list().elements()) {
          collectPropertyIdents(el, paths, boundVars);
        }
      }
      case COMPREHENSION -> {
        CelExpr.CelComprehension comp = kind.comprehension();
        collectPropertyIdents(comp.iterRange(), paths, boundVars);
        Set<String> innerBound = new HashSet<>(boundVars);
        innerBound.add(comp.iterVar());
        collectPropertyIdents(comp.accuInit(), paths, innerBound);
        collectPropertyIdents(comp.loopCondition(), paths, innerBound);
        collectPropertyIdents(comp.loopStep(), paths, innerBound);
        collectPropertyIdents(comp.result(), paths, innerBound);
      }
      default -> {
        /* CONSTANT, MAP, STRUCT */
      }
    }
  }

  // ======================================================================
  // Schema property resolution helper
  // ======================================================================

  static boolean resolveSchemaProperty(JsonNode schema, String propertyPath) {
    String[] parts = propertyPath.split("\\.");
    JsonNode current = schema;
    for (String part : parts) {
      JsonNode props = current.get("properties");
      if (props == null || !props.has(part)) return false;
      current = props.get(part);
    }
    return true;
  }
}
