package cloud.poesis.sie.defman.type;

/**
 * Centralized source of truth for references of all GSM domain rules enforced by the
 * definition-manager.
 *
 * <p>Each value describes a <em>rule</em> (the constraint that must hold), not an error or
 * violation. The naming convention is {@code SUBTYPE_PROPERTY_CONSTRAINT} — e.g. {@code
 * ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE}.
 *
 * <p>Every value carries:
 *
 * <ul>
 *   <li>{@code type} — a stable machine-readable URI ({@code gsm:rules/…})
 *   <li>{@code title} — short human-readable label for RFC 9457 ProblemDetail
 *   <li>{@code description} — natural-language statement of the rule
 * </ul>
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public enum RuleType {

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
  // EFFECTOR — reference integrity
  // ====================================================================

  EFFECTOR_MECHANISM_REFERENCE_INTEGRITY(
      "gsm:rules/effector/mechanism/reference-integrity",
      "Effector mechanism reference integrity",
      "An Effector's mechanism reference must resolve to an existing "
          + "Mechanism Definition — a non-existent or mistyped reference "
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

  DIRECTIVE_PURPOSE_REFERENCE_INTEGRITY(
      "gsm:rules/directive/purpose/reference-integrity",
      "Directive purpose reference integrity",
      "A Directive's purpose reference must resolve to an existing "
          + "Structure Definition — a non-existent or mistyped reference "
          + "is rejected."),

  DIRECTIVE_QUALIFIER_REFERENCE_INTEGRITY(
      "gsm:rules/directive/qualifier/reference-integrity",
      "Directive qualifier reference integrity",
      "A Directive's qualifier reference must resolve to an existing "
          + "Archetype Definition — a non-existent or mistyped reference "
          + "is rejected. This is the viability-dimension Archetype "
          + "(qualifier FK), distinct from the Ascription-level typing "
          + "archetype."),

  DIRECTIVE_VERB_COMPATIBILITY(
      "gsm:rules/directive/verb/compatibility",
      "Directive verb compatibility",
      "Directives targeting the same qualifier Archetype and the same "
          + "purpose Structure must not carry contradictory verb "
          + "directions (e.g. ENSURE vs PREVENT on the same viability "
          + "dimension)."),

  DIRECTIVE_MODAL_COMPATIBILITY(
      "gsm:rules/directive/modal/compatibility",
      "Directive modal compatibility",
      "Directives targeting the same qualifier, purpose, and verb must "
          + "not carry a positive modal and its negation (e.g. MUST + "
          + "MUST_NOT on the same verb) — this is a contradiction."),

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
  // NORM — governance chain and conflict detection
  // ====================================================================

  NORM_GOVERNANCE_CHAIN(
      "gsm:rules/norm/governance-chain",
      "Norm governance chain",
      "A Norm must be legitimated by an in-effect Directive whose "
          + "purpose matches the Norm's structure and whose qualifier "
          + "is an ancestor-or-equal of the Norm's qualifier in the "
          + "allOf chain — no Directive backing means no governance "
          + "authority for this Norm."),

  NORM_CONFLICT(
      "gsm:rules/norm/conflict",
      "Norm conflict",
      "Norms targeting the same structure and the same or overlapping "
          + "qualifier lineage must not carry contradictory assertions "
          + "on the same property paths — conflicting governance "
          + "constraints indicate a governance design error."),

  // ====================================================================
  // ARCHETYPE — allOf chain constraints
  // ====================================================================

  ARCHETYPE_ALLOF_CHAIN_EXCLUSIVE_BASE_CONVERGENCE(
      "gsm:rules/archetype/allof/chain-exclusive-base-convergence",
      "Archetype allOf chain exclusive base convergence",
      "A structural Archetype's allOf chain must converge to exactly one "
          + "GSM base Archetype (no divergence). Every $ref must use the "
          + "gsm://archetypes/{title}/v{version} URI convention and "
          + "resolve to a declared Archetype; at activation time, all "
          + "intermediary Archetypes must be in-effect."),

  ARCHETYPE_ALLOF_CHAIN_ACYCLICITY(
      "gsm:rules/archetype/allof/chain-acyclicity",
      "Archetype allOf chain acyclicity",
      "The Archetype's allOf chain must be acyclic — no Archetype may "
          + "transitively reference itself through $ref entries."),

  ARCHETYPE_ALLOF_SEAL(
      "gsm:rules/archetype/allof/seal",
      "Archetype allOf seal",
      "A tenant-defined Archetype must not extend a sealed ($gsm:sealed) "
          + "Archetype via allOf — sealed Archetypes are non-extensible."),

  ARCHETYPE_REF_URI_POLICY(
      "gsm:rules/archetype/ref/uri-policy",
      "Archetype $ref URI policy",
      "Every $ref URI in an Archetype schema must be either a local "
          + "JSON Pointer (starting with '#') or a gsm:// URI following "
          + "the gsm://archetypes/{title}/v{version} convention. "
          + "External URIs (http://, https://, file://, etc.) are "
          + "rejected to prevent SSRF and ensure all schema resolution "
          + "is local."),

  // ====================================================================
  // ARCHETYPE — $gsm:* annotation well-formedness
  // ====================================================================

  ARCHETYPE_ANNOTATION_QUERYABLE(
      "gsm:rules/archetype/annotation/queryable",
      "Archetype $gsm:queryable annotation well-formedness",
      "A $gsm:queryable annotation requires an indexable property type "
          + "(string, number, integer, boolean, or array of scalars) and "
          + "the total count of queryable properties per Archetype must "
          + "not exceed the configurable limit (default: 8)."),

  ARCHETYPE_ANNOTATION_DATA_PROTECTION(
      "gsm:rules/archetype/annotation/data-protection",
      "Archetype $gsm:dataProtection annotation well-formedness",
      "A $gsm:dataProtection annotation must be a well-formed object "
          + "with valid phase declarations (atRest / inTransit) and "
          + "must satisfy cross-phase mutual exclusion constraints "
          + "(e.g. atRest.hash constrains inTransit to suppression or "
          + "absent; atRest.suppression requires inTransit absent). "
          + "$gsm:queryable + $gsm:dataProtection on the same property "
          + "is forbidden."),

  ARCHETYPE_ANNOTATION_IDENTITY_BOUND_SET_IMMUTABILITY(
      "gsm:rules/archetype/annotation/identity-bound-set-immutability",
      "Archetype $gsm:identityBound set immutability",
      "The set of properties annotated $gsm:identityBound within an "
          + "Archetype schema must not differ across Ascriptions of "
          + "the same Archetype Definition — changing the identity-"
          + "bound set requires a new Definition."),

  // ====================================================================
  // ARCHETYPE — $gsm:validation CEL constraints
  // ====================================================================

  ARCHETYPE_VALIDATION_CEL_PARSING(
      "gsm:rules/archetype/validation/cel-parsing",
      "Archetype $gsm:validation CEL parsing",
      "Every expression in the Archetype's $gsm:validation array must "
          + "be a syntactically valid CEL expression."),

  ARCHETYPE_VALIDATION_CEL_CONSTRUCT_BLACKLIST(
      "gsm:rules/archetype/validation/cel-construct-blacklist",
      "Archetype $gsm:validation CEL construct blacklist",
      "Every $gsm:validation CEL expression must be deterministic and "
          + "side-effect-free — non-deterministic functions (now(), "
          + "uuid()) and imperative constructs are forbidden."),

  ARCHETYPE_VALIDATION_CEL_THIS_ROOT_BINDING(
      "gsm:rules/archetype/validation/cel-this-root-binding",
      "Archetype $gsm:validation CEL 'this' root binding",
      "Every $gsm:validation CEL expression receives the statement "
          + "payload as 'this' — the expression must use 'this' as its "
          + "implicit root for property access."),

  ARCHETYPE_VALIDATION_CEL_BOOLEAN_RESULT(
      "gsm:rules/archetype/validation/cel-boolean-result",
      "Archetype $gsm:validation CEL boolean result",
      "Every $gsm:validation CEL expression must evaluate to bool — "
          + "non-boolean result types are rejected."),

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
          + "value from the Definition's first Ascription."),

  ASCRIPTION_STATUS_TRANSITION_PATH(
      "gsm:rules/ascription/status-transition/path",
      "Ascription status transition path",
      "A lifecycle transition is permitted only between statuses connected "
          + "by an edge in the Ascription state machine (gsm-ascription-"
          + "lifecycle)."),

  ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS(
      "gsm:rules/ascription/status-transition/compatibility-with-reference-status",
      "Ascription status transition compatibility with reference status",
      "A lifecycle transition requires every referenced entity (referee FK) "
          + "to be in a lifecycle status that satisfies the referential "
          + "integrity precondition for the target status."),

  ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_CONSTITUENTS(
      "gsm:rules/ascription/status-transition/cascade-to-constituents",
      "Ascription status transition cascade to constituents",
      "A constitutive cascade (Mechanism → Effectors/Receptors) must "
          + "complete successfully for all lifecycle-coupled targets — "
          + "failure blocks the source transition."),

  ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_SUBJECTS(
      "gsm:rules/ascription/status-transition/cascade-to-subjects",
      "Ascription status transition cascade to subjects",
      "A governing cascade (Structure → Mechanisms, Directives, Norms) "
          + "propagates the transition to all governed elements — non-"
          + "blocking, no-op on failure."),

  ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_DEPENDENTS(
      "gsm:rules/ascription/status-transition/cascade-to-dependents",
      "Ascription status transition cascade to dependents",
      "A dependent cascade (Effector/Receptor → Interactions) propagates "
          + "degradation and terminal transitions to downstream consumers "
          + "— non-blocking, no-op on failure."),

  ASCRIPTION_STATUS_TRANSITION_APPROVAL_CONVERGENCE(
      "gsm:rules/ascription/status-transition/approval-convergence",
      "Ascription status transition approval convergence",
      "Approving an Ascription auto-terminates all non-terminal sibling "
          + "Ascriptions for the same Definition: DRAFT → ABANDONED, "
          + "PROPOSED → REJECTED."),

  ASCRIPTION_STATUS_TRANSITION_ACTIVATION_HANDOFF(
      "gsm:rules/ascription/status-transition/activation-handoff",
      "Ascription status transition activation handoff",
      "Activating an Ascription supersedes the previous in-effect "
          + "Ascription for the same Definition: the predecessor "
          + "transitions from ACTIVE to DEPRECATED."),

  ASCRIPTION_STATUS_TRANSITION_TERMINAL_IMMUTABILITY(
      "gsm:rules/ascription/status-transition/terminal-immutability",
      "Ascription status transition terminal immutability",
      "Once an Ascription reaches a terminal status (ABANDONED, REJECTED, "
          + "RETIRED), no further status transitions may be appended — "
          + "a new creative cycle requires a new Ascription."),

  // ====================================================================
  // DEFINITION — structural invariants
  // ====================================================================

  DEFINITION_ASCRIPTIONS_ALWAYS_PRESENT(
      "gsm:rules/definition/ascriptions/always-present",
      "Definition ascriptions always present",
      "A persisted Definition must always have at least one Ascription — "
          + "Definitions are created transactionally with their first "
          + "Ascription and must never exist without one.");

  // ====================================================================
  // Fields and accessors
  // ====================================================================

  private final String type;
  private final String title;
  private final String description;

  RuleType(String type, String title, String description) {
    this.type = type;
    this.title = title;
    this.description = description;
  }

  /**
   * Returns the stable machine-readable URI ({@code gsm:rules/…}).
   *
   * @return the rule type URI; never {@code null}
   */
  public String getType() {
    return type;
  }

  /**
   * Returns a short human-readable label for RFC 9457 ProblemDetail.
   *
   * @return the rule title; never {@code null}
   */
  public String getTitle() {
    return title;
  }

  /**
   * Returns the natural-language statement of the rule.
   *
   * @return the rule description; never {@code null}
   */
  public String getDescription() {
    return description;
  }
}
