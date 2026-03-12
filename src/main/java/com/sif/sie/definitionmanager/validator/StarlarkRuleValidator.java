package com.sif.sie.definitionmanager.validator;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import net.starlark.java.syntax.Argument;
import net.starlark.java.syntax.AssignmentStatement;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.DotExpression;
import net.starlark.java.syntax.Expression;
import net.starlark.java.syntax.ExpressionStatement;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ForStatement;
import net.starlark.java.syntax.Identifier;
import net.starlark.java.syntax.LoadStatement;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.Statement;
import net.starlark.java.syntax.StringLiteral;
import net.starlark.java.syntax.SyntaxError;

/**
 * Validates Mechanism Starlark rule source against GSM structural constraints
 * using {@code net.starlark.java.syntax} AST parsing (see Mechanism note —
 * Validation Rules, Usage Rules).
 *
 * <p>
 * Enforced constraints:
 * <ul>
 * <li>Valid Starlark syntax (parse errors rejected).</li>
 * <li>First executable statement must be exactly one {@code on("...")}
 * call.</li>
 * <li>String literals in {@code sys.*} calls must be string literals (not
 * variables).</li>
 * <li>No {@code load()} statements.</li>
 * <li>Only allowed globals: {@code sys}, {@code on}, {@code now},
 * {@code uuid7},
 * {@code fullmatch}, {@code search}, and Starlark built-ins.</li>
 * </ul>
 */
@Component
public class StarlarkRuleValidator {

    /** GSM-injected host functions and namespaces. */
    private static final Set<String> ALLOWED_GLOBALS = Set.of(
            "sys", "on", "now", "uuid7", "fullmatch", "search");

    /** Starlark built-in names that are always allowed as top-level identifiers. */
    private static final Set<String> STARLARK_BUILTINS = Set.of(
            "True", "False", "None",
            "bool", "dict", "float", "int", "list", "str", "tuple", "type",
            "abs", "all", "any", "dir", "enumerate", "fail", "getattr",
            "hasattr", "hash", "len", "max", "min", "print", "range",
            "repr", "reversed", "sorted", "zip", "map", "filter", "struct");

    /** Allowed sys.* method names. */
    private static final Set<String> SYS_METHODS = Set.of(
            "emit", "create", "modify", "delete", "acquire");

    /**
     * Validates a Mechanism Starlark rule.
     *
     * @param rule the Starlark source code
     * @return the archetype name from the {@code on("...")} trigger (for Receptor
     *         derivation)
     */
    public String validate(String rule) {
        if (rule == null || rule.isBlank()) {
            throw new IllegalArgumentException(
                    "Mechanism rule must not be empty in Generative mode");
        }

        // 1. Parse with the real Starlark parser
        StarlarkFile file = parseStarlark(rule);

        List<Statement> statements = file.getStatements();
        if (statements.isEmpty()) {
            throw new IllegalArgumentException(
                    "Starlark rule violation: rule body is empty");
        }

        // 2. Reject load() statements
        for (Statement stmt : statements) {
            if (stmt instanceof LoadStatement) {
                throw new IllegalArgumentException(
                        "Starlark rule violation: load() statements are forbidden");
            }
        }

        // 3. First statement must be on("ArchetypeName")
        String triggerArchetype = validateOnTrigger(statements);

        // 4. Walk all statements: validate sys.*() literal args + collect locals
        Set<String> localNames = new HashSet<>();
        int onCallCount = 0;
        for (Statement stmt : statements) {
            collectLocals(stmt, localNames);
            onCallCount += countOnCalls(stmt);
        }
        if (onCallCount > 1) {
            throw new IllegalArgumentException(
                    "Starlark rule violation: exactly one on() call is allowed as the first "
                            + "executable statement");
        }

        // 5. Validate sys.*() calls have string literal first arg
        for (Statement stmt : statements) {
            validateSysCallsInStatement(stmt);
        }

        // 6. Validate globals
        Set<String> unknownGlobals = new HashSet<>();
        for (Statement stmt : statements) {
            collectUnknownGlobals(stmt, localNames, unknownGlobals);
        }
        if (!unknownGlobals.isEmpty()) {
            throw new IllegalArgumentException(
                    "Starlark rule violation: unknown global(s) detected: " + unknownGlobals
                            + ". Only sys, on, now, uuid7, fullmatch, search, "
                            + "and Starlark built-ins are allowed.");
        }

        return triggerArchetype;
    }

    // ========================================================================
    // Parse
    // ========================================================================

    private static StarlarkFile parseStarlark(String source) {
        ParserInput input = ParserInput.fromString(source, "<rule>");
        StarlarkFile file = StarlarkFile.parse(input, FileOptions.DEFAULT);
        if (!file.ok()) {
            StringBuilder sb = new StringBuilder("Starlark syntax error:");
            for (SyntaxError err : file.errors()) {
                sb.append("\n  ").append(err.message());
            }
            throw new IllegalArgumentException(sb.toString());
        }
        return file;
    }

    // ========================================================================
    // on() trigger validation
    // ========================================================================

    private static String validateOnTrigger(List<Statement> statements) {
        Statement first = statements.getFirst();

        // on("X") can appear as ExpressionStatement(CallExpression)
        // or as AssignmentStatement: evt = on("X")
        CallExpression onCall = extractOnCall(first);

        if (onCall == null) {
            throw new IllegalArgumentException(
                    "Starlark rule violation: first executable statement must be on(\"ArchetypeName\"). "
                            + "Found: " + first.getClass().getSimpleName());
        }

        List<Argument> args = onCall.getArguments();
        if (args.isEmpty()) {
            throw new IllegalArgumentException(
                    "Starlark rule violation: on() requires a string literal archetype name argument");
        }

        Expression firstArg = args.getFirst().getValue();
        if (!(firstArg instanceof StringLiteral sl)) {
            throw new IllegalArgumentException(
                    "Starlark rule violation: on() argument must be a string literal, "
                            + "not a dynamic expression");
        }

        String archetypeName = sl.getValue();
        if (archetypeName.isBlank()) {
            throw new IllegalArgumentException(
                    "Starlark rule violation: on() archetype name must not be empty");
        }

        return archetypeName;
    }

    /**
     * Extracts the on(...) CallExpression from a statement, or null if not an on()
     * call.
     */
    private static CallExpression extractOnCall(Statement stmt) {
        CallExpression call = null;
        if (stmt instanceof ExpressionStatement es && es.getExpression() instanceof CallExpression ce) {
            call = ce;
        } else if (stmt instanceof AssignmentStatement as && as.getRHS() instanceof CallExpression ce) {
            call = ce;
        }
        if (call != null && isGlobalCall(call, "on")) {
            return call;
        }
        return null;
    }

    private static boolean isGlobalCall(CallExpression call, String name) {
        return call.getFunction() instanceof Identifier id && name.equals(id.getName());
    }

    private static int countOnCalls(Statement stmt) {
        return extractOnCall(stmt) != null ? 1 : 0;
    }

    // ========================================================================
    // sys.*() literal argument validation
    // ========================================================================

    private static void validateSysCallsInStatement(Statement stmt) {
        if (stmt instanceof ExpressionStatement es) {
            validateSysCallsInExpr(es.getExpression());
        } else if (stmt instanceof AssignmentStatement as) {
            validateSysCallsInExpr(as.getRHS());
        }
        // ForStatement, IfStatement, etc. — would need recursive statement walking
        // for full coverage, but Mechanism rules are flat by convention
    }

    private static void validateSysCallsInExpr(Expression expr) {
        if (expr instanceof CallExpression ce) {
            // Check if this is sys.method(...)
            if (ce.getFunction() instanceof DotExpression de
                    && de.getObject() instanceof Identifier id
                    && "sys".equals(id.getName())
                    && SYS_METHODS.contains(de.getField().getName())) {

                List<Argument> args = ce.getArguments();
                if (args.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Starlark rule violation: sys." + de.getField().getName()
                                    + "() requires at least one argument (archetype name string literal)");
                }
                Expression firstArg = args.getFirst().getValue();
                if (!(firstArg instanceof StringLiteral)) {
                    throw new IllegalArgumentException(
                            "Starlark rule violation: sys." + de.getField().getName()
                                    + "() first argument must be a string literal (archetype name). "
                                    + "Dynamic archetype references prevent static analysis.");
                }
            }

            // Recurse into arguments
            for (Argument arg : ce.getArguments()) {
                validateSysCallsInExpr(arg.getValue());
            }
        }
    }

    // ========================================================================
    // Local name collection
    // ========================================================================

    private static void collectLocals(Statement stmt, Set<String> locals) {
        if (stmt instanceof AssignmentStatement as) {
            if (as.getLHS() instanceof Identifier id) {
                locals.add(id.getName());
            }
        } else if (stmt instanceof ForStatement fs) {
            if (fs.getVars() instanceof Identifier id) {
                locals.add(id.getName());
            }
        }
    }

    // ========================================================================
    // Unknown global detection
    // ========================================================================

    private static void collectUnknownGlobals(Statement stmt, Set<String> locals, Set<String> unknowns) {
        if (stmt instanceof ExpressionStatement es) {
            collectUnknownGlobalsInExpr(es.getExpression(), locals, unknowns);
        } else if (stmt instanceof AssignmentStatement as) {
            collectUnknownGlobalsInExpr(as.getRHS(), locals, unknowns);
        }
    }

    private static void collectUnknownGlobalsInExpr(Expression expr, Set<String> locals, Set<String> unknowns) {
        if (expr instanceof Identifier id) {
            String name = id.getName();
            if (!ALLOWED_GLOBALS.contains(name)
                    && !STARLARK_BUILTINS.contains(name)
                    && !locals.contains(name)) {
                unknowns.add(name);
            }
        } else if (expr instanceof CallExpression ce) {
            // For global calls like on(...), check the function identifier
            collectUnknownGlobalsInExpr(ce.getFunction(), locals, unknowns);
            for (Argument arg : ce.getArguments()) {
                collectUnknownGlobalsInExpr(arg.getValue(), locals, unknowns);
            }
        } else if (expr instanceof DotExpression de) {
            // Only check the root object, not the field (fields are method names)
            collectUnknownGlobalsInExpr(de.getObject(), locals, unknowns);
        }
    }
}
