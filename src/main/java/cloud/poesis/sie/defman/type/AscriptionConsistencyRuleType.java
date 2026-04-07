package cloud.poesis.sie.defman.type;

/**
 * Centralized source of truth for references of all GSM domain rules enforced
 * by the
 * definition-manager.
 *
 * <p>
 * Each value describes a <em>rule</em> (the constraint that must hold), not an
 * error or
 * violation. The naming convention is {@code SUBTYPE_PROPERTY_CONSTRAINT} —
 * e.g. {@code
 * ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE}.
 *
 * <p>
 * Every value carries:
 *
 * <ul>
 * <li>{@code type} — a stable machine-readable URI ({@code gsm:rules/…})
 * <li>{@code title} — short human-readable label for RFC 9457 ProblemDetail
 * <li>{@code description} — natural-language statement of the rule
 * </ul>
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public enum AscriptionConsistencyRuleType implements RuleType {

    // ====================================================================
    // MECHANISM — Starlark rule validation
    // ====================================================================

    MECHANISM_RULE_STARLARK_PARSING(
            "gsm:rules/mechanism/rule/starlark-parsing",
            "Mechanism rule Starlark parsing",
            "A Mechanism rule must be syntactically valid Starlark — the source "
                    + "must parse without errors by the Starlark parser."),

    MECHANISM_RULE_STARLARK_BUDGET(
            "gsm:rules/mechanism/rule/starlark-budget",
            "Mechanism rule Starlark budget",
            "A Mechanism rule must not exceed the configurable maximum statement "
                    + "count (default: 100,000 steps)."),

    MECHANISM_RULE_STARLARK_CONSTRUCT_BLACKLIST(
            "gsm:rules/mechanism/rule/starlark-construct-blacklist",
            "Mechanism rule Starlark construct blacklist",
            "Forbidden Starlark constructs (load() statements) are rejected — "
                    + "all logic must be self-contained within the rule body."),

    MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST(
            "gsm:rules/mechanism/rule/starlark-global-whitelist",
            "Mechanism rule Starlark global whitelist",
            "Only the sys namespace (methods and properties), injected host "
                    + "functions (on, now, uuid7, fullmatch, search), and Starlark "
                    + "built-ins are permitted as globals — any other global "
                    + "identifier, unknown sys method, or unknown sys property is "
                    + "rejected."),

    // ====================================================================
    // MECHANISM — trigger constraints
    // ====================================================================

    MECHANISM_RULE_TRIGGER_AS_FIRST_STATEMENT(
            "gsm:rules/mechanism/rule/trigger-as-first-statement",
            "Mechanism rule trigger as first statement",
            "A Mechanism rule must begin with exactly one on(\"ArchetypeName\") "
                    + "call as its first executable statement — declaring the "
                    + "triggering Archetype and auto-deriving the trigger Receptor."),

    MECHANISM_RULE_TRIGGER_AS_UNIQUE_STATEMENT(
            "gsm:rules/mechanism/rule/trigger-as-unique-statement",
            "Mechanism rule trigger as unique statement",
            "A Mechanism rule must contain exactly one on() trigger declaration "
                    + "— multiple on() calls are forbidden."),

    MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE(
            "gsm:rules/mechanism/rule/trigger-argument-as-archetype-title",
            "Mechanism rule trigger argument as Archetype title",
            "on() must receive exactly one positional argument: a non-empty "
                    + "string literal that resolves to a declared Archetype title "
                    + "in the governed scope."),

    // ====================================================================
    // MECHANISM — sys.* fluent API arity and chain constraints
    // ====================================================================

    MECHANISM_RULE_SYS_FLUENT_API_ARITY(
            "gsm:rules/mechanism/rule/sys-fluent-api-arity",
            "Mechanism rule sys.* fluent API method arity",
            "sys.effect() requires 1-2 positional arguments (archetype "
                    + "title, optional data payload). sys.receive() requires "
                    + "exactly 1 positional argument (archetype title). "
                    + "Chain methods .by(), .receive(), .on() each require "
                    + "exactly 1 positional argument (archetype title as "
                    + "string literal)."),

    MECHANISM_RULE_SYS_FLUENT_API(
            "gsm:rules/mechanism/rule/sys-fluent-api",
            "Mechanism rule sys.* fluent API chain invalid",
            "sys.effect() chain must follow: effect → [by] → [receive → [on]]. "
                    + "sys.receive() chain must follow: receive → [on]. "
                    + "Each method may appear at most once. No other methods are "
                    + "allowed on either chain."),

    // ====================================================================
    // MECHANISM — reference integrity
    // ====================================================================

    MECHANISM_STRUCTURE_REFERENCE_INTEGRITY(
            "gsm:rules/mechanism/structure/reference-integrity",
            "Mechanism structure reference integrity",
            "A Mechanism's structure reference must resolve to an existing "
                    + "Structure Definition — a non-existent or mistyped reference "
                    + "is rejected."),

    // ====================================================================
    // EFFECTOR — reference integrity
    // ====================================================================

    EFFECTOR_MECHANISM_REFERENCE_INTEGRITY(
            "gsm:rules/effector/mechanism/reference-integrity",
            "Effector mechanism reference integrity",
            "An Effector's mechanism reference must resolve to an existing "
                    + "Mechanism Definition — a non-existent or mistyped reference "
                    + "is rejected."),

    EFFECTOR_ARCHETYPE_REFERENCE_INTEGRITY(
            "gsm:rules/effector/archetype/reference-integrity",
            "Effector data archetype reference integrity",
            "An Effector's data archetype reference must resolve to an existing "
                    + "Archetype Definition — a non-existent or mistyped reference "
                    + "is rejected."),

    // ====================================================================
    // RECEPTOR — reference integrity
    // ====================================================================

    RECEPTOR_MECHANISM_REFERENCE_INTEGRITY(
            "gsm:rules/receptor/mechanism/reference-integrity",
            "Receptor mechanism reference integrity",
            "A Receptor's mechanism reference must resolve to an existing "
                    + "Mechanism Definition — a non-existent or mistyped reference "
                    + "is rejected."),

    RECEPTOR_ARCHETYPE_REFERENCE_INTEGRITY(
            "gsm:rules/receptor/archetype/reference-integrity",
            "Receptor data archetype reference integrity",
            "A Receptor's data archetype reference must resolve to an existing "
                    + "Archetype Definition — a non-existent or mistyped reference "
                    + "is rejected."),

    // ====================================================================
    // INTERACTION — references and compatibility
    // ====================================================================

    INTERACTION_EFFECTOR_REFERENCE_INTEGRITY(
            "gsm:rules/interaction/effector/reference-integrity",
            "Interaction effector reference integrity",
            "An Interaction's effector reference must resolve to an existing "
                    + "Effector Definition — a non-existent or mistyped reference "
                    + "is rejected."),

    INTERACTION_RECEPTOR_REFERENCE_INTEGRITY(
            "gsm:rules/interaction/receptor/reference-integrity",
            "Interaction receptor reference integrity",
            "An Interaction's receptor reference must resolve to an existing "
                    + "Receptor Definition — a non-existent or mistyped reference "
                    + "is rejected."),

    INTERACTION_EFFECTOR_RECEPTOR_COMPATIBILITY(
            "gsm:rules/interaction/effector-receptor/compatibility",
            "Interaction Effector–Receptor compatibility",
            "An Interaction's Effector output Archetype and Receptor input "
                    + "Archetype must be schema-compatible — the Effector's data "
                    + "Archetype must be assignable to the Receptor's data "
                    + "Archetype."),

    // ====================================================================
    // DIRECTIVE — references and governance consistency
    // ====================================================================

    DIRECTIVE_STRUCTURE_REFERENCE_INTEGRITY(
            "gsm:rules/directive/structure/reference-integrity",
            "Directive structure reference integrity",
            "A Directive's authoring structure reference must resolve to an "
                    + "existing Structure Definition — a non-existent or mistyped "
                    + "reference is rejected."),

    DIRECTIVE_QUALIFIER_REFERENCE_INTEGRITY(
            "gsm:rules/directive/qualifier/reference-integrity",
            "Directive qualifier reference integrity",
            "A Directive's qualifier reference must resolve to an existing "
                    + "Archetype Definition — a non-existent or mistyped reference "
                    + "is rejected. This is the viability-dimension Archetype "
                    + "(qualifier FK), distinct from the Ascription-level typing "
                    + "archetype."),

    // ====================================================================
    // NORM — reference integrity
    // ====================================================================

    NORM_STRUCTURE_REFERENCE_INTEGRITY(
            "gsm:rules/norm/structure/reference-integrity",
            "Norm structure reference integrity",
            "A Norm's authoring structure reference must resolve to an "
                    + "existing Structure Definition — a non-existent or mistyped "
                    + "reference is rejected."),

    NORM_QUALIFIER_REFERENCE_INTEGRITY(
            "gsm:rules/norm/qualifier/reference-integrity",
            "Norm qualifier reference integrity",
            "A Norm's qualifier reference must resolve to an existing "
                    + "Archetype Definition — a non-existent or mistyped reference "
                    + "is rejected. This is the viability-dimension Archetype "
                    + "(qualifier FK), distinct from the Ascription-level typing "
                    + "archetype."),

    // ====================================================================
    // NORM — Applicability CEL profile
    // ====================================================================

    NORM_APPLICABILITY_CEL_PARSING(
            "gsm:rules/norm/applicability/cel-parsing",
            "Norm applicability CEL parsing",
            "A Norm applicability expression must be a syntactically valid CEL expression — the "
                    + "source must parse without errors by the CEL parser."),

    NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM(
            "gsm:rules/norm/applicability/axis-predicate-normal-form",
            "Norm applicability axis-predicate normal form",
            "A Norm applicability expression must be expressible as a conjunction (&&) of "
                    + "single-axis predicates, where each predicate compares "
                    + "exactly one Archetype property to a static literal using "
                    + "comparison operators, set membership (in), or "
                    + "string.matches(). Forbidden: disjunction (||), ternary "
                    + "(?:), arithmetic (+,-,*,/,%), cross-property comparisons, "
                    + "function calls other than matches(), comprehensions, macros "
                    + "(except in), and duplicate axis predicates (at most one "
                    + "predicate per (Archetype, propertyPath) pair)."),

    NORM_APPLICABILITY_COMPARISON_CONSISTENCY(
            "gsm:rules/norm/applicability/comparison-consistency",
            "Norm applicability comparison consistency",
            "A Norm applicability expression's set-membership lists (in [...]) must have at least "
                    + "2 unique, type-homogeneous elements (single value → use ==). "
                    + "Comparison operands must be type-compatible with the "
                    + "Archetype property's declared schema type."),

    NORM_APPLICABILITY_ARCHETYPE_REFERENCE_RESOLUTION(
            "gsm:rules/norm/applicability/archetype-reference-resolution",
            "Norm applicability Archetype reference resolution",
            "Archetype names used as root identifiers in Norm applicability property "
                    + "paths (e.g. SecurityProperties.environment) must resolve to "
                    + "declared Archetype titles in the governed scope."),

    NORM_APPLICABILITY_PROPERTY_PATH_RESOLUTION(
            "gsm:rules/norm/applicability/property-path-resolution",
            "Norm applicability property path resolution",
            "Property paths in Norm applicability predicates must resolve to properties "
                    + "declared in the referenced Archetype's JSON Schema — "
                    + "undefined or misspelled paths are rejected."),

    // ====================================================================
    // NORM — Assertion CEL profile
    // ====================================================================

    NORM_ASSERTION_CEL_PARSING(
            "gsm:rules/norm/assertion/cel-parsing",
            "Norm assertion CEL parsing",
            "A Norm assertion must be a syntactically valid CEL expression — "
                    + "the source must parse without errors by the CEL parser."),

    NORM_ASSERTION_AS_DETERMINISTIC_EXPRESSION(
            "gsm:rules/norm/assertion/deterministic-expression",
            "Norm assertion as deterministic expression",
            "A Norm assertion must be deterministic and side-effect-free — "
                    + "non-deterministic functions (now(), uuid()) and imperative "
                    + "constructs (variable declarations, assignments, loops) are "
                    + "forbidden."),

    NORM_ASSERTION_AS_ARCHETYPE_BOUND_EXPRESSION(
            "gsm:rules/norm/assertion/archetype-bound-expression",
            "Norm assertion as Archetype-bound expression",
            "A Norm assertion must use bare qualifier property names — "
                    + "resolved against the Norm's qualifier Archetype FK at evaluation time. "
                    + "The 'self.' prefix and explicit Archetype names in property paths are forbidden."),

    NORM_ASSERTION_AS_BOOLEAN_RESULT(
            "gsm:rules/norm/assertion/boolean-result",
            "Norm assertion as boolean result",
            "A Norm assertion must evaluate to a boolean — the CEL "
                    + "expression's result type must be bool."),

    NORM_ASSERTION_PROPERTY_PATH_RESOLUTION(
            "gsm:rules/norm/assertion/property-path-resolution",
            "Norm assertion property path resolution",
            "Property paths in Norm assertion expressions must "
                    + "resolve to properties declared in the qualifier Archetype's "
                    + "JSON Schema — undefined or misspelled paths are rejected."),

    NORM_ASSERTION_TOLERANCE_MODE_CONSISTENCY(
            "gsm:rules/norm/assertion/tolerance-mode-consistency",
            "Norm assertion tolerance mode consistency",
            "Norm tolerance-mode field combination must be consistent: "
                    + "INSTANTANEOUS forbids temporalWindow/temporalAggregation/"
                    + "sustainedThreshold; AGGREGATED requires temporalWindow and "
                    + "temporalAggregation; SUSTAINED requires all three including "
                    + "sustainedThreshold in [0,1]."),

    // ====================================================================
    // ARCHETYPE — allOf chain constraints
    // ====================================================================

    ARCHETYPE_ALLOF_EXCLUSIVE_BASE_CONVERGENCE(
            "gsm:rules/archetype/allof/exclusive-base-convergence",
            "Archetype allOf exclusive base convergence",
            "A structural Archetype's allOf chain must converge to exactly one "
                    + "GSM base Archetype (no divergence). Every $ref must use the "
                    + "gsmarc://{authority}/{segments}/{title}/v{version} URI convention and "
                    + "resolve to a declared Archetype; at activation time, all "
                    + "intermediary Archetypes must be in-effect."),

    ARCHETYPE_ALLOF_ACYCLICITY(
            "gsm:rules/archetype/allof/acyclicity",
            "Archetype allOf acyclicity",
            "The Archetype's allOf chain must be acyclic — no Archetype may "
                    + "transitively reference itself through $ref entries."),

    ARCHETYPE_ALLOF_NON_SEALED(
            "gsm:rules/archetype/allof/non-sealed",
            "Archetype allOf non-sealed",
            "A tenant-defined Archetype must not extend a sealed ($gsm:sealed) "
                    + "Archetype via allOf — sealed Archetypes are non-extensible."),

    ARCHETYPE_REF_NORM(
            "gsm:rules/archetype/ref/norm",
            "Archetype $ref norm",
            "Every $ref URI in an Archetype schema must be either a local "
                    + "JSON Pointer (starting with '#') or a gsmarc:// URI following "
                    + "the gsmarc://{authority}/{segments}/{title}/v{version} convention. "
                    + "External URIs (http://, https://, file://, etc.) are "
                    + "rejected to prevent SSRF and ensure all schema resolution "
                    + "is local."),

    // ====================================================================
    // ARCHETYPE — $gsm:* annotation well-formedness
    // ====================================================================

    ARCHETYPE_IDENTITY_BOUND_PROPERTY_IMMUTABILITY(
            "gsm:rules/archetype/identity-bound/property-immutability",
            "Archetype identity-bound property immutability",
            "The set of properties annotated $gsm:identityBound within an "
                    + "Archetype schema must not differ across Ascriptions of "
                    + "the same Archetype Definition — changing the identity-"
                    + "bound set requires a new Definition."),

    // ====================================================================
    // ASCRIPTION — cross-cutting statement validation
    // ====================================================================

    ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE(
            "gsm:rules/ascription/statement/compliance-to-gsm-archetype",
            "Ascription statement compliance to GSM archetype",
            "An Ascription statement must conform to the GSM base Archetype "
                    + "schema for its subject type — all required structural "
                    + "properties present, all values matching declared types, "
                    + "formats, and constraints."),

    ASCRIPTION_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE(
            "gsm:rules/ascription/statement/compliance-to-non-gsm-archetype",
            "Ascription statement compliance to non-GSM archetype",
            "An Ascription statement must conform to the tenant-extended "
                    + "Archetype schema — all tenant-defined properties matching "
                    + "declared types, formats, patterns, ranges, enums, and "
                    + "$gsm:* vocabulary keyword constraints."),

    // ====================================================================
    // ASCRIPTION — cross-cutting lifecycle constraints
    // ====================================================================

    ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE(
            "gsm:rules/ascription/archetype/based-on-gsm-archetype",
            "Ascription archetype based on GSM archetype",
            "An Ascription's typing archetype (archetype_id) must reference "
                    + "an Archetype whose allOf chain converges to a GSM base "
                    + "Archetype — rootless Archetypes cannot serve as typing "
                    + "references because the GSM base determines the "
                    + "DefinitionSubjectType."),

    ASCRIPTION_ARCHETYPE_REFERENCE_INTEGRITY(
            "gsm:rules/ascription/archetype/reference-integrity",
            "Ascription archetype reference integrity",
            "Any Ascription FK referencing an Archetype Definition "
                    + "(Effector/Receptor data archetype, Directive/Norm qualifier "
                    + "archetype) must resolve to an existing Archetype Definition "
                    + "— a non-existent or mistyped reference is rejected."),

    ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS(
            "gsm:rules/ascription/property/uniqueness-across-definitions",
            "Ascription property uniqueness across definitions",
            "Identity properties (Structure.purpose, Mechanism.function, "
                    + "Archetype.title) and $gsm:unique-annotated properties must be "
                    + "unique among in-effect (ACTIVE or DEPRECATED) Ascriptions across "
                    + "all Definitions of the same Ascription type (or GSM archetype)."),

    ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION(
            "gsm:rules/ascription/property/integrity-within-definition",
            "Ascription property integrity within definition",
            "Properties annotated $gsm:identityBound must not change across "
                    + "Ascriptions of the same Definition — the value must equal the "
                    + "value from the Definition's first Ascription.");

    // ====================================================================
    // Fields and accessors
    // ====================================================================

    private final String type;
    private final String title;
    private final String description;

    AscriptionConsistencyRuleType(String type, String title, String description) {
        this.type = type;
        this.title = title;
        this.description = description;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getDescription() {
        return description;
    }
}
