package com.sif.sie.definitionmanager.validator;

import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;

/**
 * Validates Norm CEL expressions against their respective profiles (GSM Norm
 * note)
 * using {@code dev.cel:cel-java} AST walking:
 *
 * <ul>
 * <li><b>Applicability-guard profile</b> ({@code guard}): axis-predicate normal
 * form.
 * Pure conjunction of single-axis predicates. No {@code ||}, no cross-property
 * comparisons, no arithmetic, no function calls except {@code matches()},
 * no ternary.</li>
 * <li><b>Property-assertion profile</b> ({@code predicate}): boolean expression
 * over qualifier properties. Uses {@code self} as implicit root. No
 * non-deterministic functions ({@code now()}, {@code uuid()}).</li>
 * </ul>
 */
@Component
public class CelProfileValidator {

    /** Guard profile: allowed comparison operators (CEL function names). */
    private static final Set<String> GUARD_COMPARISON_OPS = Set.of(
            "_==_", "_!=_", "_<_", "_<=_", "_>_", "_>=_", "@in");

    /** Guard profile: allowed function calls (receiver-style). */
    private static final Set<String> GUARD_ALLOWED_FUNCTIONS = Set.of("matches");

    /** Guard profile: forbidden arithmetic operators. */
    private static final Set<String> GUARD_ARITHMETIC_OPS = Set.of(
            "_+_", "_-_", "_*_", "_%_", "_/_");

    /** Predicate profile: forbidden non-deterministic functions. */
    private static final Set<String> PREDICATE_FORBIDDEN_FUNCTIONS = Set.of("now", "uuid");

    private final CelCompiler guardCompiler;
    private final CelCompiler predicateCompiler;

    public CelProfileValidator() {
        // Guard compiler: variables are ArchetypeName identifiers (dynamic)
        this.guardCompiler = CelCompilerFactory.standardCelCompilerBuilder()
                .addVar("self", SimpleType.DYN)
                .build();

        // Predicate compiler: self is the implicit root
        this.predicateCompiler = CelCompilerFactory.standardCelCompilerBuilder()
                .addVar("self", SimpleType.DYN)
                .build();
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Validates a guard expression against the applicability-guard CEL profile.
     *
     * @param guard the guard CEL expression (may be {@code "true"} for
     *              unconditional)
     */
    public void validateGuard(String guard) {
        if (guard == null || guard.isBlank() || "true".equals(guard.trim())) {
            return; // Unconditional guard — valid
        }

        CelExpr ast = parse(guardCompiler, guard, "Guard");

        // Walk AST enforcing guard profile constraints
        Set<String> axes = new HashSet<>();
        validateGuardExpr(ast, axes, true);
    }

    /**
     * Validates a predicate expression against the property-assertion CEL profile.
     *
     * @param predicate the predicate CEL expression
     */
    public void validatePredicate(String predicate) {
        if (predicate == null || predicate.isBlank()) {
            throw new IllegalArgumentException("Predicate must not be empty");
        }

        CelExpr ast = parse(predicateCompiler, predicate, "Predicate");
        validatePredicateExpr(ast);
    }

    // ========================================================================
    // CEL parse helper
    // ========================================================================

    private static CelExpr parse(CelCompiler compiler, String expression, String profileName) {
        CelValidationResult result = compiler.parse(expression);
        if (result.hasError()) {
            throw new IllegalArgumentException(
                    profileName + " CEL parse error: " + result.getErrorString());
        }
        try {
            CelAbstractSyntaxTree ast = result.getAst();
            return ast.getExpr();
        } catch (CelValidationException e) {
            throw new IllegalArgumentException(
                    profileName + " CEL validation error: " + e.getMessage(), e);
        }
    }

    // ========================================================================
    // Guard profile: AST walking
    // ========================================================================

    /**
     * Recursively validates a guard expression node.
     *
     * @param expr     the current AST node
     * @param axes     accumulates (Archetype.property) axes for duplicate detection
     * @param topLevel true if this node is at the top-level conjunction
     */
    private void validateGuardExpr(CelExpr expr, Set<String> axes, boolean topLevel) {
        CelExpr.ExprKind kind = expr.exprKind();

        switch (kind.getKind()) {
            case CALL -> {
                CelExpr.CelCall call = kind.call();
                String fn = call.function();

                // _&&_ is the only allowed logical connective (conjunction)
                if ("_&&_".equals(fn)) {
                    for (CelExpr arg : call.args()) {
                        validateGuardExpr(arg, axes, true);
                    }
                    return;
                }

                // Forbidden: || (disjunction)
                if ("_||_".equals(fn)) {
                    throw new IllegalArgumentException(
                            "Guard profile violation: '||' (OR) is forbidden. "
                                    + "The guard must be a pure conjunction. "
                                    + "Use 'in [...]' for set membership instead of OR.");
                }

                // Forbidden: ternary
                if ("_?_:_".equals(fn)) {
                    throw new IllegalArgumentException(
                            "Guard profile violation: ternary operator (?:) is forbidden in guard expressions.");
                }

                // Forbidden: arithmetic
                if (GUARD_ARITHMETIC_OPS.contains(fn)) {
                    throw new IllegalArgumentException(
                            "Guard profile violation: arithmetic operators are forbidden in guard expressions.");
                }

                // Forbidden: negation at predicate level (unary !)
                if ("!_".equals(fn) || "_!_".equals(fn)) {
                    // Allowed on leaf predicates — validate the inner expression
                    for (CelExpr arg : call.args()) {
                        validateGuardExpr(arg, axes, topLevel);
                    }
                    return;
                }

                // Comparison operators — this is a single-axis predicate
                if (GUARD_COMPARISON_OPS.contains(fn)) {
                    if (topLevel) {
                        validateSingleAxisPredicate(call, axes);
                    }
                    return;
                }

                // Receiver-style function call (e.g., .matches())
                if (call.target().isPresent()) {
                    if (!GUARD_ALLOWED_FUNCTIONS.contains(fn)) {
                        throw new IllegalArgumentException(
                                "Guard profile violation: only .matches() is allowed as a function call. "
                                        + "Found: ." + fn + "()");
                    }
                    // .matches() is an axis predicate on the target
                    if (topLevel) {
                        String axis = extractAxis(call.target().get());
                        if (axis != null && !axes.add(axis)) {
                            throw new IllegalArgumentException(
                                    "Guard profile violation: duplicate axis '" + axis
                                            + "'. At most one predicate per (Archetype, propertyPath).");
                        }
                    }
                    return;
                }

                // Global function call — forbidden in guard
                throw new IllegalArgumentException(
                        "Guard profile violation: function call '" + fn
                                + "' is forbidden. Only comparison operators and .matches() are allowed.");
            }
            case SELECT, IDENT, CONSTANT, LIST -> {
                // Leaf nodes: valid in guard context
            }
            default -> {
                // Comprehension, map, struct — not expected in guard
            }
        }
    }

    /**
     * Validates that a comparison call is single-axis (compares one property to a
     * literal/list).
     */
    private void validateSingleAxisPredicate(CelExpr.CelCall call, Set<String> axes) {
        // Collect all property references (SELECT chains rooted at IDENT) from args
        Set<String> predAxes = new HashSet<>();
        for (CelExpr arg : call.args()) {
            collectAxes(arg, predAxes);
        }

        if (predAxes.size() > 1) {
            throw new IllegalArgumentException(
                    "Guard profile violation: cross-property comparison detected. "
                            + "Each predicate must compare a single property to a literal. "
                            + "Found axes: " + predAxes);
        }

        for (String axis : predAxes) {
            if (!axes.add(axis)) {
                throw new IllegalArgumentException(
                        "Guard profile violation: duplicate axis '" + axis
                                + "'. At most one predicate per (Archetype, propertyPath).");
            }
        }
    }

    /**
     * Collects Archetype.property axis references from an expression subtree.
     */
    private static void collectAxes(CelExpr expr, Set<String> axes) {
        CelExpr.ExprKind kind = expr.exprKind();
        switch (kind.getKind()) {
            case SELECT -> {
                String axis = extractAxis(expr);
                if (axis != null) {
                    axes.add(axis);
                }
            }
            case CALL -> {
                CelExpr.CelCall call = kind.call();
                call.target().ifPresent(t -> collectAxes(t, axes));
                for (CelExpr arg : call.args()) {
                    collectAxes(arg, axes);
                }
            }
            default -> {
                // IDENT, CONSTANT, LIST — no axis
            }
        }
    }

    /**
     * Extracts the axis string "Root.field" from a SELECT chain.
     * Returns null if the root is not an IDENT (e.g., nested call).
     */
    private static String extractAxis(CelExpr expr) {
        if (expr.exprKind().getKind() != CelExpr.ExprKind.Kind.SELECT) {
            return null;
        }
        CelExpr.CelSelect sel = expr.exprKind().select();
        String field = sel.field();

        // Walk down to the root ident
        CelExpr operand = sel.operand();
        if (operand.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT) {
            return operand.exprKind().ident().name() + "." + field;
        }
        // Deeper select: take root ident + first field only (normalized axis)
        CelExpr root = operand;
        while (root.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT) {
            root = root.exprKind().select().operand();
        }
        if (root.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT) {
            // First field after root
            CelExpr firstSelect = operand;
            CelExpr prev = expr;
            while (firstSelect.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT
                    && firstSelect.exprKind().select().operand().exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
                prev = firstSelect;
                firstSelect = firstSelect.exprKind().select().operand();
            }
            if (firstSelect.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT) {
                return root.exprKind().ident().name() + "." + firstSelect.exprKind().select().field();
            }
        }
        return null;
    }

    // ========================================================================
    // Predicate profile: AST walking
    // ========================================================================

    private void validatePredicateExpr(CelExpr expr) {
        CelExpr.ExprKind kind = expr.exprKind();

        switch (kind.getKind()) {
            case CALL -> {
                CelExpr.CelCall call = kind.call();
                String fn = call.function();

                // Forbidden: non-deterministic functions
                if (PREDICATE_FORBIDDEN_FUNCTIONS.contains(fn)) {
                    throw new IllegalArgumentException(
                            "Predicate profile violation: non-deterministic functions (now(), uuid()) are forbidden. "
                                    + "Predicate must be deterministic and side-effect-free.");
                }

                // Recurse into target and args
                call.target().ifPresent(this::validatePredicateExpr);
                for (CelExpr arg : call.args()) {
                    validatePredicateExpr(arg);
                }
            }
            case SELECT -> {
                CelExpr.CelSelect sel = kind.select();
                CelExpr operand = sel.operand();
                // Check root: must be 'self', not an explicit Archetype ident
                CelExpr root = operand;
                while (root.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT) {
                    root = root.exprKind().select().operand();
                }
                if (root.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT) {
                    String rootName = root.exprKind().ident().name();
                    if (!rootName.equals("self") && Character.isUpperCase(rootName.charAt(0))) {
                        throw new IllegalArgumentException(
                                "Predicate profile violation: use 'self' as implicit root, "
                                        + "not an explicit Archetype name. "
                                        + "Example: 'self.encryptionLevel' instead of '"
                                        + rootName + "." + sel.field() + "'.");
                    }
                }
                validatePredicateExpr(operand);
            }
            case IDENT -> {
                // Bare identifiers are OK (self, true, false, etc.)
            }
            case CONSTANT -> {
                // Literals are always OK
            }
            case LIST -> {
                CelExpr.CelList list = kind.list();
                for (CelExpr el : list.elements()) {
                    validatePredicateExpr(el);
                }
            }
            default -> {
                // COMPREHENSION, MAP, STRUCT — allowed in predicate profile
            }
        }
    }
}
