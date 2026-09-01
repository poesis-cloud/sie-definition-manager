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
import java.util.Set;
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

  // ======================================================================
  // CEL profile constants
  // ======================================================================

  private static final Set<String> APPLICABILITY_COMPARISON_OPS =
      Set.of("_==_", "_!=_", "_<_", "_<=_", "_>_", "_>=_", "@in");
  private static final Set<String> APPLICABILITY_ALLOWED_FUNCTIONS = Set.of("matches");
  private static final Set<String> APPLICABILITY_ARITHMETIC_OPS =
      Set.of("_+_", "_-_", "_*_", "_%_", "_/_");

  private final ArchetypeService archetypeService;
  private final ArchetypeCompositionValidationService compositionValidation;
  private final CelCompiler celParser;

  public NormApplicabilityValidationService(
      ArchetypeService archetypeService,
      ArchetypeCompositionValidationService compositionValidation,
      CelCompiler celParser) {
    this.archetypeService = archetypeService;
    this.compositionValidation = compositionValidation;
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
    Set<ApplicabilityAxis> axes = new HashSet<>();
    validateApplicabilityExpr(ast, axes, true);
  }

  /**
   * Validates applicability expression archetype references and property paths.
   *
   * @param applicability the CEL expression string
   */
  public void validateApplicabilityReferences(String applicability) {
    CelExpr ast = parseApplicabilityCel(applicability);
    Set<ApplicabilityAxis> axes = new LinkedHashSet<>();
    collectAxes(ast, axes);
    for (ApplicabilityAxis axis : axes) {
      // NORM_APPLICABILITY_ARCHETYPE_REFERENCE_RESOLUTION
      ArchetypeEntity archetype;
      try {
        archetype = archetypeService.resolveArchetypeUri(axis.archetypeId(), "applicability ref()");
      } catch (RuleViolationException exception) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_ARCHETYPE_REFERENCE_RESOLUTION,
            "Applicability ref() does not resolve to a governed Archetype URI: "
                + axis.archetypeId(),
            exception,
            "archetypeId",
            axis.archetypeId());
      }
      // NORM_APPLICABILITY_PROPERTY_PATH_RESOLUTION
      JsonNode schema = archetype.getStatement();
      if (!compositionValidation.resolvesPropertyPath(
          schema,
          axis.propertyPath(),
          referencedId -> {
            try {
              return archetypeService
                  .resolveArchetypeUri(referencedId, "applicability schema composition")
                  .getStatement();
            } catch (RuleViolationException exception) {
              throw RuleViolationException.of(
                  AscriptionConsistencyRuleType.NORM_APPLICABILITY_ARCHETYPE_REFERENCE_RESOLUTION,
                  "Applicability schema composition reference does not resolve to a governed Archetype URI: "
                      + referencedId,
                  exception,
                  "archetypeId",
                  referencedId);
            }
          })) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_PROPERTY_PATH_RESOLUTION,
            "Applicability references property '"
                + axis.propertyPath()
                + "' which does not exist in Archetype '"
                + axis.archetypeId()
                + "' schema",
            "archetypeId",
            axis.archetypeId(),
            "propertyPath",
            axis.propertyPath(),
            "axis",
            axis.toString());
      }
    }
  }

  /** Returns distinct Archetype URIs referenced by an applicability expression. */
  public List<String> extractApplicabilityReferences(String applicability) {
    if (applicability == null || applicability.isBlank() || "true".equals(applicability.trim())) {
      return List.of();
    }
    CelExpr ast = parseApplicabilityCel(applicability);
    Set<String> references = new LinkedHashSet<>();
    collectApplicabilityReferences(ast, references);
    return List.copyOf(references);
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

  private void validateApplicabilityExpr(
      CelExpr expr, Set<ApplicabilityAxis> axes, boolean topLevel) {
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
          if (call.args().size() != 1 || isConjunction(call.args().get(0))) {
            throw invalidApplicabilityExpression(
                "compound-expression negation is forbidden; negate only one axis predicate.");
          }
          for (CelExpr arg : call.args()) {
            validateApplicabilityExpr(arg, axes, topLevel);
          }
          return;
        }
        if (APPLICABILITY_COMPARISON_OPS.contains(fn)) {
          if ("@in".equals(fn) && call.args().size() == 2) {
            if (call.args().get(1).exprKind().getKind() != CelExpr.ExprKind.Kind.LIST) {
              throw invalidApplicabilityExpression(
                  "'in' requires a list literal with at least two elements.");
            }
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
            ApplicabilityAxis axis = extractAxis(call.target().get());
            if (axis == null) {
              throw RuleViolationException.of(
                  AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                  "Applicability profile violation: .matches() must target a ref(\"$id\").property axis.",
                  "field",
                  "applicability",
                  "construct",
                  fn);
            }
            if (call.args().size() != 1 || !isStringLiteral(call.args().get(0))) {
              throw invalidApplicabilityExpression(
                  ".matches() requires exactly one static string literal argument.");
            }
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
      case SELECT, IDENT, CONSTANT, LIST ->
          throw invalidApplicabilityExpression(
              "Applicability must be an axis predicate rooted at ref(\"$id\"), not a bare "
                  + kind.getKind().name().toLowerCase()
                  + " expression.");
      default ->
          throw invalidApplicabilityExpression(
              "Applicability contains a forbidden CEL expression kind: " + kind.getKind());
    }
  }

  private void validateSingleAxisPredicate(CelExpr.CelCall call, Set<ApplicabilityAxis> axes) {
    for (CelExpr arg : call.args()) {
      rejectForbiddenInApplicabilityOperand(arg);
    }
    if (call.args().stream().anyMatch(NormApplicabilityValidationService::hasBareRoot)) {
      throw invalidApplicabilityExpression(
          "Bare title or identifier roots are forbidden; use ref(\"<Archetype URI>\").property.");
    }
    Set<ApplicabilityAxis> predAxes = new HashSet<>();
    for (CelExpr arg : call.args()) {
      collectAxes(arg, predAxes);
    }
    if (predAxes.size() != 1) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          "Applicability profile violation: each predicate must compare exactly one "
              + "ref(\"$id\").property axis to a literal. "
              + "Found axes: "
              + predAxes,
          "field",
          "applicability",
          "axes",
          predAxes.toString());
    }
    int axisOperandCount = 0;
    int literalOperandCount = 0;
    for (CelExpr arg : call.args()) {
      Set<ApplicabilityAxis> operandAxes = new HashSet<>();
      collectAxes(arg, operandAxes);
      if (operandAxes.size() == 1) {
        axisOperandCount++;
      } else if (operandAxes.isEmpty() && isStaticLiteral(arg)) {
        literalOperandCount++;
      } else {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
            "Applicability profile violation: a ref(\"$id\").property axis must be compared to a static literal.",
            "field",
            "applicability");
      }
    }
    if (axisOperandCount != 1 || literalOperandCount != 1) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          "Applicability profile violation: a ref(\"$id\").property axis must be compared to exactly one static literal.",
          "field",
          "applicability");
    }
    for (ApplicabilityAxis axis : predAxes) {
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
        if (isRefCall(call)) {
          return;
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

  static void collectAxes(CelExpr expr, Set<ApplicabilityAxis> axes) {
    CelExpr.ExprKind kind = expr.exprKind();
    switch (kind.getKind()) {
      case SELECT -> {
        ApplicabilityAxis axis = extractAxis(expr);
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

  static ApplicabilityAxis extractAxis(CelExpr expr) {
    if (expr.exprKind().getKind() != CelExpr.ExprKind.Kind.SELECT) return null;
    StringBuilder propertyPath = new StringBuilder();
    CelExpr root = expr;
    while (root.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT) {
      CelExpr.CelSelect select = root.exprKind().select();
      if (!propertyPath.isEmpty()) {
        propertyPath.insert(0, '.');
      }
      propertyPath.insert(0, select.field());
      root = select.operand();
    }
    if (root.exprKind().getKind() != CelExpr.ExprKind.Kind.CALL) {
      return null;
    }
    CelExpr.CelCall refCall = root.exprKind().call();
    if (!"ref".equals(refCall.function())) {
      return null;
    }
    validateRefCall(refCall);
    String archetypeId = refCall.args().getFirst().exprKind().constant().stringValue();
    validateRefIdentity(archetypeId);
    return new ApplicabilityAxis(archetypeId, propertyPath.toString());
  }

  private static boolean isRefCall(CelExpr.CelCall call) {
    return "ref".equals(call.function())
        && call.target().isEmpty()
        && call.args().size() == 1
        && call.args().getFirst().exprKind().getKind() == CelExpr.ExprKind.Kind.CONSTANT
        && call.args().getFirst().exprKind().constant().getKind() == CelConstant.Kind.STRING_VALUE;
  }

  private static void validateRefCall(CelExpr.CelCall call) {
    if (!isRefCall(call)) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          "Applicability profile violation: ref() requires exactly one string-literal $id argument.",
          "field",
          "applicability",
          "construct",
          "ref");
    }
  }

  private static void validateRefIdentity(String archetypeId) {
    try {
      ArchetypeParsingService.parseIdentity(archetypeId);
    } catch (IllegalArgumentException exception) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          "Applicability profile violation: ref() argument is not a grammar-conformant Archetype $id: "
              + archetypeId,
          "field",
          "applicability",
          "archetypeId",
          archetypeId);
    }
  }

  private static boolean isStaticLiteral(CelExpr expr) {
    return switch (expr.exprKind().getKind()) {
      case CONSTANT -> true;
      case LIST ->
          expr.exprKind().list().elements().stream()
              .allMatch(element -> element.exprKind().getKind() == CelExpr.ExprKind.Kind.CONSTANT);
      default -> false;
    };
  }

  private static boolean isStringLiteral(CelExpr expr) {
    return expr.exprKind().getKind() == CelExpr.ExprKind.Kind.CONSTANT
        && expr.exprKind().constant().getKind() == CelConstant.Kind.STRING_VALUE;
  }

  private static boolean isConjunction(CelExpr expr) {
    return expr.exprKind().getKind() == CelExpr.ExprKind.Kind.CALL
        && "_&&_".equals(expr.exprKind().call().function());
  }

  private static boolean hasBareRoot(CelExpr expr) {
    CelExpr root = expr;
    while (root.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT) {
      root = root.exprKind().select().operand();
    }
    return root.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT;
  }

  private static RuleViolationException invalidApplicabilityExpression(String detail) {
    return RuleViolationException.of(
        AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
        "Applicability profile violation: " + detail,
        "field",
        "applicability");
  }

  private static void collectApplicabilityReferences(CelExpr expr, Set<String> references) {
    CelExpr.ExprKind kind = expr.exprKind();
    switch (kind.getKind()) {
      case SELECT -> collectApplicabilityReferences(kind.select().operand(), references);
      case CALL -> {
        CelExpr.CelCall call = kind.call();
        if ("ref".equals(call.function())) {
          validateRefCall(call);
          String archetypeId = call.args().getFirst().exprKind().constant().stringValue();
          validateRefIdentity(archetypeId);
          references.add(archetypeId);
        }
        call.target().ifPresent(target -> collectApplicabilityReferences(target, references));
        for (CelExpr arg : call.args()) {
          collectApplicabilityReferences(arg, references);
        }
      }
      default -> {
        /* no references */
      }
    }
  }

  record ApplicabilityAxis(String archetypeId, String propertyPath) {}

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
}
