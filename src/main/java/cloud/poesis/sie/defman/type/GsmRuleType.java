package cloud.poesis.sie.defman.type;

/**
 * Single source of truth for all GSM domain rules enforced by the
 * definition-manager.
 *
 * <p>
 * Each value describes a <em>rule</em> (the constraint that must hold), not an
 * error or violation. The naming convention is
 * {@code TYPE.PROPERTY.CONSTRAINT} — e.g.
 * {@code ASCRIPTION_PROPERTY_REQUIREMENT}.
 *
 * <p>
 * Every value carries:
 * <ul>
 * <li>{@code type} — a stable machine-readable URI ({@code gsm:rules/…})</li>
 * <li>{@code title} — short human-readable label for RFC 9457
 * ProblemDetail</li>
 * <li>{@code description} — natural-language statement of the rule</li>
 * </ul>
 */
public enum GsmRuleType {

    // ====================================================================
    // ASCRIPTION — cross-cutting Ascription constraints
    // ====================================================================

    ASCRIPTION_PROPERTY_REQUIREMENT(
            "gsm:rules/ascription/property/requirement",
            "Ascription property requirement",
            "Every property declared as required by the typing Archetype's schema "
                    + "must be present in the Ascription statement."),

    ASCRIPTION_PROPERTY_FORMATTING(
            "gsm:rules/ascription/property/formatting",
            "Ascription property formatting",
            "Every property value must conform to the format, pattern, and range "
                    + "constraints declared by the typing Archetype's schema."),

    ASCRIPTION_PROPERTY_TYPING(
            "gsm:rules/ascription/property/typing",
            "Ascription property typing",
            "Every property value must match the type declared by the typing "
                    + "Archetype's schema."),

    ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS(
            "gsm:rules/ascription/property/uniqueness-across-definitions",
            "Ascription property uniqueness across definitions",
            "Identity properties (purpose, function, title) must be unique among "
                    + "in-effect Ascriptions across all Definitions of the same Archetype."),

    ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION(
            "gsm:rules/ascription/property/integrity-within-definition",
            "Ascription property integrity within definition",
            "Identity-bound properties must not change across Ascriptions of the "
                    + "same Definition."),

    ASCRIPTION_STATUS_TRANSITION_PATH(
            "gsm:rules/ascription/status-transition/path",
            "Ascription status transition path",
            "A lifecycle transition is permitted only between statuses connected "
                    + "in the Ascription state machine."),

    ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS(
            "gsm:rules/ascription/status-transition/compatibility-with-reference-status",
            "Ascription status transition compatibility with reference status",
            "A lifecycle transition requires all referenced entities (referees) to "
                    + "be in a status that satisfies the transition precondition."),

    ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_CONSTITUANTS(
            "gsm:rules/ascription/status-transition/cascade-to-constituants",
            "Ascription status transition cascade to constituants",
            "A constitutive cascade must complete successfully for all "
                    + "lifecycle-coupled targets."),

    ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_SUBJECTS(
            "gsm:rules/ascription/status-transition/cascade-to-subjects",
            "Ascription status transition cascade to subjects",
            "A governing cascade propagates the transition to all governed "
                    + "elements (non-blocking — no-op on failure)."),

    ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_DEPENDENTS(
            "gsm:rules/ascription/status-transition/cascade-to-dependents",
            "Ascription status transition cascade to dependents",
            "A dependent cascade propagates degradation and terminal transitions "
                    + "to downstream consumers (non-blocking — no-op on failure)."),

    ASCRIPTION_STATUS_TRANSITION_APPROVAL_CONVERGENCE(
            "gsm:rules/ascription/status-transition/approval-convergence",
            "Ascription status transition approval convergence",
            "Approving an Ascription auto-terminates all non-terminal sibling "
                    + "Ascriptions for the same Definition (DRAFT → ABANDONED, "
                    + "PROPOSED → REJECTED)."),

    ASCRIPTION_STATUS_TRANSITION_ACTIVATION_HANDOFF(
            "gsm:rules/ascription/status-transition/activation-handoff",
            "Ascription status transition activation handoff",
            "Activating an Ascription supersedes the previous in-effect Ascription "
                    + "for the same Definition (ACTIVE → DEPRECATED)."),

    ASCRIPTION_STATUS_TRANSITION_TERMINAL_IMMUTABILITY(
            "gsm:rules/ascription/status-transition/terminal-immutability",
            "Ascription status transition terminal immutability",
            "Once an Ascription reaches a terminal status (ABANDONED, REJECTED, "
                    + "RETIRED), no additional transitions may be appended."),

    // ====================================================================
    // MECHANISM — Mechanism rule syntax sub-concerns
    // ====================================================================

    MECHANISM_RULE_PARSE_ERROR(
            "gsm:rules/mechanism/rule/parse-error",
            "Mechanism rule parse error",
            "A Mechanism rule must be syntactically valid Starlark."),

    MECHANISM_RULE_BUDGET_EXCEEDED(
            "gsm:rules/mechanism/rule/budget-exceeded",
            "Mechanism rule budget exceeded",
            "A Mechanism rule must not exceed the maximum statement count."),

    MECHANISM_RULE_LOAD_FORBIDDEN(
            "gsm:rules/mechanism/rule/load-forbidden",
            "Mechanism rule load forbidden",
            "load() statements are not allowed in Mechanism rules."),

    MECHANISM_RULE_MISSING_TRIGGER(
            "gsm:rules/mechanism/rule/missing-trigger",
            "Mechanism rule missing trigger",
            "A Mechanism rule must begin with on(\"ArchetypeName\") as its "
                    + "first executable statement."),

    MECHANISM_RULE_MULTIPLE_TRIGGERS(
            "gsm:rules/mechanism/rule/multiple-triggers",
            "Mechanism rule multiple triggers",
            "A Mechanism rule must have exactly one on() trigger declaration."),

    MECHANISM_RULE_TRIGGER_ARGUMENT(
            "gsm:rules/mechanism/rule/trigger-argument",
            "Mechanism rule trigger argument",
            "on() must have exactly one positional non-empty string literal argument."),

    MECHANISM_RULE_UNKNOWN_GLOBAL(
            "gsm:rules/mechanism/rule/unknown-global",
            "Mechanism rule unknown global",
            "Only sys, injected host functions (on, now, uuid7, fullmatch, search), "
                    + "and Starlark built-ins are allowed globals."),

    MECHANISM_RULE_UNKNOWN_SYS_METHOD(
            "gsm:rules/mechanism/rule/unknown-sys-method",
            "Mechanism rule unknown sys method",
            "sys.* method calls must reference a declared sys method."),

    MECHANISM_RULE_UNKNOWN_SYS_PROPERTY(
            "gsm:rules/mechanism/rule/unknown-sys-property",
            "Mechanism rule unknown sys property",
            "sys.* property accesses must reference a declared sys property."),

    MECHANISM_RULE_SYS_ARGUMENT(
            "gsm:rules/mechanism/rule/sys-argument",
            "Mechanism rule sys argument",
            "sys.*() calls must have at least one argument."),

    MECHANISM_RULE_NON_LITERAL_ARCHETYPE(
            "gsm:rules/mechanism/rule/non-literal-archetype",
            "Mechanism rule non-literal archetype",
            "sys.*() first argument must be a string literal (archetype name)."),

    // ====================================================================
    // MECHANISM — Mechanism mode constraints
    // ====================================================================

    MECHANISM_RULE_DECLARATION_MODE_EXCLUSIVITY(
            "gsm:rules/mechanism/rule-declaration/mode-exclusivity",
            "Mechanism rule declaration mode exclusivity",
            "A Mechanism operates in exactly one mode: generative (rule present, "
                    + "no explicit ports) or declarative (no rule, explicit ports)."),

    MECHANISM_RULE_DECLARATION_MIN_PORT(
            "gsm:rules/mechanism/rule-declaration/min-port",
            "Mechanism rule declaration min port",
            "A declarative Mechanism must have at least one Effector and one "
                    + "Receptor explicitly authored."),

    // ====================================================================
    // DIRECTIVE — Directive governance consistency
    // ====================================================================

    DIRECTIVE_VERB_COMPATIBILITY(
            "gsm:rules/directive/verb/compatibility",
            "Directive verb compatibility",
            "Directives targeting the same qualifier and purpose must not carry "
                    + "contradictory verb directions (e.g. ENSURE vs PREVENT)."),

    DIRECTIVE_MODAL_COMPATIBILITY(
            "gsm:rules/directive/modal/compatibility",
            "Directive modal compatibility",
            "Directives targeting the same qualifier, purpose, and verb must not "
                    + "carry a positive modal and its negation (e.g. MUST vs MUST_NOT)."),

    // ====================================================================
    // NORM — Guard profile sub-concerns
    // ====================================================================

    NORM_GUARD_PARSE_ERROR(
            "gsm:rules/norm/guard/parse-error",
            "Norm guard parse error",
            "A Norm guard must be a valid CEL expression."),

    NORM_GUARD_DISJUNCTION(
            "gsm:rules/norm/guard/disjunction",
            "Norm guard disjunction forbidden",
            "The guard must be a pure conjunction — OR (||) is forbidden."),

    NORM_GUARD_TERNARY(
            "gsm:rules/norm/guard/ternary",
            "Norm guard ternary forbidden",
            "Ternary operator (?:) is forbidden in guard expressions."),

    NORM_GUARD_ARITHMETIC(
            "gsm:rules/norm/guard/arithmetic",
            "Norm guard arithmetic forbidden",
            "Arithmetic operators (+, -, *, /, %) are forbidden in guard expressions."),

    NORM_GUARD_FORBIDDEN_FUNCTION(
            "gsm:rules/norm/guard/forbidden-function",
            "Norm guard forbidden function",
            "Only .matches() is allowed as a function call in guard expressions."),

    NORM_GUARD_CROSS_PROPERTY(
            "gsm:rules/norm/guard/cross-property",
            "Norm guard cross-property comparison",
            "Each guard predicate must compare a single property to a literal — "
                    + "cross-property comparisons are forbidden."),

    NORM_GUARD_DUPLICATE_AXIS(
            "gsm:rules/norm/guard/duplicate-axis",
            "Norm guard duplicate axis",
            "At most one predicate per (Archetype, propertyPath) axis."),

    // ====================================================================
    // NORM — Predicate profile sub-concerns
    // ====================================================================

    NORM_PREDICATE_PARSE_ERROR(
            "gsm:rules/norm/predicate/parse-error",
            "Norm predicate parse error",
            "A Norm predicate must be a valid CEL expression."),

    NORM_PREDICATE_NON_DETERMINISTIC(
            "gsm:rules/norm/predicate/non-deterministic",
            "Norm predicate non-deterministic function",
            "Non-deterministic functions (now(), uuid()) are forbidden — "
                    + "predicate must be deterministic and side-effect-free."),

    NORM_PREDICATE_EXPLICIT_ROOT(
            "gsm:rules/norm/predicate/explicit-root",
            "Norm predicate explicit root",
            "Predicate must use 'self' as implicit root, not an explicit "
                    + "Archetype name."),

    // ====================================================================
    // INTERACTION — structural coupling constraints
    // ====================================================================

    INTERACTION_ENDPOINTS_COMPATIBILITY(
            "gsm:rules/interaction/endpoints/compatibility",
            "Interaction endpoints compatibility",
            "An Interaction's Effector and Receptor must reference "
                    + "schema-compatible Archetypes."),

    // ====================================================================
    // ARCHETYPE — Archetype schema and allOf chain constraints
    // ====================================================================

    ARCHETYPE_ALLOF_CONVERGENCE(
            "gsm:rules/archetype/allof/convergence",
            "Archetype allOf convergence",
            "A structural Archetype's allOf chain must converge to exactly one "
                    + "GSM base Archetype."),

    ARCHETYPE_ALLOF_RESOLUTION(
            "gsm:rules/archetype/allof/resolution",
            "Archetype allOf resolution",
            "Every $ref in the Archetype's allOf chain must resolve to an "
                    + "existing, in-effect Archetype."),

    ARCHETYPE_ALLOF_ACYCLICITY(
            "gsm:rules/archetype/allof/acyclicity",
            "Archetype allOf acyclicity",
            "The Archetype's allOf chain must be acyclic — no Archetype may "
                    + "transitively reference itself."),

    ARCHETYPE_ALLOF_SEALED_EXTENSION_PROHIBITION(
            "gsm:rules/archetype/allof/sealed-extension-prohibition",
            "Archetype allOf sealed extension prohibition",
            "A tenant Archetype must not extend a sealed ($gsm:sealed) Archetype "
                    + "via allOf."),

    ARCHETYPE_TYPING_DERIVATION(
            "gsm:rules/archetype/typing/derivation",
            "Archetype typing derivation",
            "An Archetype used as archetype_id must be a structural Archetype "
                    + "whose allOf chain converges to a GSM base."),

    ARCHETYPE_VOCABULARY_WELLFORMEDNESS(
            "gsm:rules/archetype/vocabulary/wellformedness",
            "Archetype vocabulary wellformedness",
            "Every $gsm:* vocabulary keyword in the Archetype schema must conform "
                    + "to its declared value shape and constraint rules.");

    // ====================================================================
    // Fields and accessors
    // ====================================================================

    private final String type;
    private final String title;
    private final String description;

    GsmRuleType(String type, String title, String description) {
        this.type = type;
        this.title = title;
        this.description = description;
    }

    /** Stable machine-readable URI ({@code gsm:rules/…}). */
    public String getType() {
        return type;
    }

    /** Short human-readable label for RFC 9457 ProblemDetail. */
    public String getTitle() {
        return title;
    }

    /** Natural-language statement of the rule. */
    public String getDescription() {
        return description;
    }
}
