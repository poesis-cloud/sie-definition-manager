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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * GSM Norm CEL applicability profile validation service.
 *
 * <p>Validates CEL applicability expressions against the GSM applicability profile: expressions
 * must be a pure conjunction of single-axis predicates with no OR, no ternary, no arithmetic, and
 * no cross-property comparisons. Also validates archetype references and property paths.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class NormApplicabilityValidationService {

  private static final Logger LOG =
      LoggerFactory.getLogger(NormApplicabilityValidationService.class);

  // ======================================================================
  // CEL profile constants
  // ======================================================================

  private static final Set<String> APPLICABILITY_COMPARISON_OPS =
      Set.of("_==_", "_!=_", "_<_", "_<=_", "_>_", "_>=_", "@in");
  private static final Set<String> APPLICABILITY_ALLOWED_FUNCTIONS = Set.of("matches");
  private static final Set<String> APPLICABILITY_ARITHMETIC_OPS =
      Set.of("_+_", "_-_", "_*_", "_%_", "_/_");

  private final ArchetypeService archetypeService;
  private final CelCompiler celParser;

  public NormApplicabilityValidationService(
      ArchetypeService archetypeService, CelCompiler celParser) {
    this.archetypeService = archetypeService;
    this.celParser = celParser;
  }

  // ======================================================================
  // Public API
  // ======================================================================

  /**
   * Validates a CEL applicability expression against the applicability profile.
   *
   * @param applicability the CEL expression string
   */
  public void validateApplicability(String applicability) {
    if (applicability == null || applicability.isBlank() || "true".equals(applicability.trim())) {
      return;
    }
    CelExpr ast = parseApplicabilityCel(applicability);
    Set<String> axes = new HashSet<>();
    validateApplicabilityExpr(ast, axes, true);
  }

  /**
   * Validates applicability expression archetype references and property paths.
   *
   * @param applicability the CEL expression string
   */
  public void validateApplicabilityReferences(String applicability) {
    CelExpr ast = parseApplicabilityCel(applicability);
    Set<String> axes = new LinkedHashSet<>();
    collectAxes(ast, axes);
    for (String axis : axes) {
      int dot = axis.indexOf('.');
      if (dot <= 0) continue;
      String archetypeName = axis.substring(0, dot);
      String propertyPath = axis.substring(dot + 1);
      // NORM_APPLICABILITY_ARCHETYPE_REFERENCE_RESOLUTION
      Optional<ArchetypeEntity> archetype = archetypeService.findInEffectByTitle(archetypeName);
      if (archetype.isEmpty()) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_ARCHETYPE_REFERENCE_RESOLUTION,
            "Applicability references Archetype '"
                + archetypeName
                + "' which does not exist as an active Archetype",
            "archetypeName",
            archetypeName,
            "axis",
            axis);
      }
      // NORM_APPLICABILITY_PROPERTY_PATH_RESOLUTION
      JsonNode schema = archetype.get().getStatement();
      if (!resolveSchemaProperty(schema, propertyPath)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_PROPERTY_PATH_RESOLUTION,
            "Applicability references property '"
                + propertyPath
                + "' which does not exist in Archetype '"
                + archetypeName
                + "' schema",
            "archetypeName",
            archetypeName,
            "propertyPath",
            propertyPath,
            "axis",
            axis);
      }
    }
  }

  // ======================================================================
  // CEL parsing
  // ======================================================================

  private CelExpr parseApplicabilityCel(String expression) {
    CelValidationResult result = celParser.parse(expression);
    if (result.hasError()) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_CEL_PARSING,
          "Applicability CEL parse error: " + result.getErrorString(),
          "field",
          "applicability");
    }
    try {
      return result.getAst().getExpr();
    } catch (CelValidationException e) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_CEL_PARSING,
          "Applicability CEL validation error: " + e.getMessage(),
          e,
          "field",
          "applicability");
    }
  }

  // ======================================================================
  // Applicability profile validation
  // ======================================================================

  private void validateApplicabilityExpr(CelExpr expr, Set<String> axes, boolean topLevel) {
    CelExpr.ExprKind kind = expr.exprKind();
    switch (kind.getKind()) {
      case CALL -> {
        CelExpr.CelCall call = kind.call();
        String fn = call.function();
        if ("_&&_".equals(fn)) {
          for (CelExpr arg : call.args()) {
            validateApplicabilityExpr(arg, axes, true);
          }
          return;
        }
        if ("_||_".equals(fn)) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
              "Applicability profile violation: '||' (OR) is forbidden. "
                  + "The applicability expression must be a pure conjunction. "
                  + "Use 'in [...]' for set membership instead of OR.",
              "field",
              "applicability",
              "construct",
              fn);
        }
        if ("_?_:_".equals(fn)) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
              "Applicability profile violation: ternary operator (?:) is forbidden in applicability expressions.",
              "field",
              "applicability",
              "construct",
              fn);
        }
        if (APPLICABILITY_ARITHMETIC_OPS.contains(fn)) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
              "Applicability profile violation: arithmetic operators are forbidden in guard expressions.",
              "field",
              "applicability",
              "construct",
              fn);
        }
        if ("!_".equals(fn) || "_!_".equals(fn)) {
          for (CelExpr arg : call.args()) {
            validateApplicabilityExpr(arg, axes, topLevel);
          }
          return;
        }
        if (APPLICABILITY_COMPARISON_OPS.contains(fn)) {
          if ("@in".equals(fn) && call.args().size() == 2) {
            validateInListConsistency(call.args().get(1));
          }
          if (topLevel) {
            validateSingleAxisPredicate(call, axes);
          }
          return;
        }
        if (call.target().isPresent()) {
          if (!APPLICABILITY_ALLOWED_FUNCTIONS.contains(fn)) {
            throw RuleViolationException.of(
                AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                "Applicability profile violation: only .matches() is allowed as a function call. "
                    + "Found: ."
                    + fn
                    + "()",
                "field",
                "applicability",
                "construct",
                fn);
          }
          if (topLevel) {
            String axis = extractAxis(call.target().get());
            if (axis != null && !axes.add(axis)) {
              throw RuleViolationException.of(
                  AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                  "Applicability profile violation: duplicate axis '"
                      + axis
                      + "'. At most one applicability predicate per (Archetype, propertyPath).",
                  "field",
                  "applicability",
                  "axis",
                  axis);
            }
          }
          return;
        }
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
            "Applicability profile violation: function call '"
                + fn
                + "' is forbidden. Only comparison operators and .matches() are allowed.",
            "field",
            "applicability",
            "construct",
            fn);
      }
      case SELECT, IDENT, CONSTANT, LIST -> {
        /* leaf nodes */
      }
      default -> {
        /* comprehension, map, struct */
      }
    }
  }

  private void validateSingleAxisPredicate(CelExpr.CelCall call, Set<String> axes) {
    for (CelExpr arg : call.args()) {
      rejectForbiddenInApplicabilityOperand(arg);
    }
    Set<String> predAxes = new HashSet<>();
    for (CelExpr arg : call.args()) {
      collectAxes(arg, predAxes);
    }
    if (predAxes.size() > 1) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          "Applicability profile violation: cross-property comparison detected. "
              + "Each applicability predicate must compare a single property to a literal. "
              + "Found axes: "
              + predAxes,
          "field",
          "applicability",
          "axes",
          predAxes.toString());
    }
    for (String axis : predAxes) {
      if (!axes.add(axis)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
            "Applicability profile violation: duplicate axis '"
                + axis
                + "'. At most one applicability predicate per (Archetype, propertyPath).",
            "field",
            "applicability",
            "axis",
            axis);
      }
    }
  }

  private static void rejectForbiddenInApplicabilityOperand(CelExpr expr) {
    CelExpr.ExprKind kind = expr.exprKind();
    switch (kind.getKind()) {
      case CALL -> {
        CelExpr.CelCall call = kind.call();
        String fn = call.function();
        if (APPLICABILITY_ARITHMETIC_OPS.contains(fn)) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
              "Applicability profile violation: arithmetic operators are forbidden in guard expressions.",
              "field",
              "applicability",
              "construct",
              fn);
        }
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
            "Applicability profile violation: function call '"
                + fn
                + "' is forbidden in applicability comparison operands. "
                + "Only property references and literals are allowed. "
                + "(Only .matches() is allowed as a standalone predicate.)",
            "field",
            "applicability",
            "construct",
            fn);
      }
      case SELECT -> rejectForbiddenInApplicabilityOperand(kind.select().operand());
      case IDENT, CONSTANT, LIST -> {
        /* valid */
      }
      default -> {
        /* not expected */
      }
    }
  }

  static void collectAxes(CelExpr expr, Set<String> axes) {
    CelExpr.ExprKind kind = expr.exprKind();
    switch (kind.getKind()) {
      case SELECT -> {
        String axis = extractAxis(expr);
        if (axis != null) axes.add(axis);
      }
      case CALL -> {
        CelExpr.CelCall call = kind.call();
        call.target().ifPresent(t -> collectAxes(t, axes));
        for (CelExpr arg : call.args()) {
          collectAxes(arg, axes);
        }
      }
      default -> {
        /* IDENT, CONSTANT, LIST — no axis */
      }
    }
  }

  static String extractAxis(CelExpr expr) {
    if (expr.exprKind().getKind() != CelExpr.ExprKind.Kind.SELECT) return null;
    CelExpr.CelSelect sel = expr.exprKind().select();
    String field = sel.field();
    CelExpr operand = sel.operand();
    if (operand.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT) {
      return operand.exprKind().ident().name() + "." + field;
    }
    CelExpr root = operand;
    while (root.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT) {
      root = root.exprKind().select().operand();
    }
    if (root.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT) {
      CelExpr firstSelect = operand;
      while (firstSelect.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT
          && firstSelect.exprKind().select().operand().exprKind().getKind()
              != CelExpr.ExprKind.Kind.IDENT) {
        firstSelect = firstSelect.exprKind().select().operand();
      }
      if (firstSelect.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT) {
        return root.exprKind().ident().name() + "." + firstSelect.exprKind().select().field();
      }
    }
    return null;
  }

  // ======================================================================
  // In-list consistency validation
  // ======================================================================

  private static void validateInListConsistency(CelExpr listExpr) {
    if (listExpr.exprKind().getKind() != CelExpr.ExprKind.Kind.LIST) {
      return;
    }
    CelExpr.CelList list = listExpr.exprKind().list();
    List<CelExpr> elements = list.elements();
    if (elements.size() < 2) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
          "Applicability 'in' list must have >= 2 elements (single value → use '=='). Found: "
              + elements.size(),
          "elementCount",
          elements.size());
    }
    Set<String> seen = new LinkedHashSet<>();
    CelConstant.Kind firstKind = null;
    for (CelExpr el : elements) {
      if (el.exprKind().getKind() != CelExpr.ExprKind.Kind.CONSTANT) {
        continue; // non-literal elements rejected by applicability profile elsewhere
      }
      CelConstant c = el.exprKind().constant();
      CelConstant.Kind kind = c.getKind();
      if (firstKind == null) {
        firstKind = kind;
      } else if (kind != firstKind) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
            "Applicability 'in' list elements must be type-homogeneous. "
                + "Mixed types: "
                + firstKind
                + " and "
                + kind,
            "firstKind",
            firstKind.name(),
            "conflictingKind",
            kind.name());
      }
      String repr = constantToString(c);
      if (!seen.add(repr)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
            "Applicability 'in' list elements must be unique. Duplicate: " + repr,
            "duplicate",
            repr);
      }
    }
  }

  static String constantToString(CelConstant c) {
    return switch (c.getKind()) {
      case STRING_VALUE -> c.stringValue();
      case INT64_VALUE -> String.valueOf(c.int64Value());
      case UINT64_VALUE -> String.valueOf(c.uint64Value());
      case DOUBLE_VALUE -> String.valueOf(c.doubleValue());
      case BOOLEAN_VALUE -> String.valueOf(c.booleanValue());
      default -> c.toString();
    };
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
