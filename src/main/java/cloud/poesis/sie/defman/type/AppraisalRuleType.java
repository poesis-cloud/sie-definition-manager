package cloud.poesis.sie.defman.type;

/**
 * Appraisal rules — the 35 governance evaluation rules.
 *
 * <p>The naming convention is {@code SUBJECT_PROPERTY_CONSTRAINT} — e.g. {@code
 * DIRECTIVE_NORM_OPERATIONALIZATION}. Each constant is named after the <b>property it enforces</b>
 * (positive orientation), not the problem it detects.
 *
 * <p>Rules are grouped by category:
 *
 * <ul>
 *   <li><b>Governance Coverage</b> — 8 rules: completeness of the D→N→A governance chain
 *   <li><b>Governance Integrity</b> — 7 rules: non-contradiction and proportionality of governance
 *       artifacts
 *   <li><b>Source Fidelity</b> — 4 rules: synchronization with external authority sources
 *   <li><b>Normative Compliance — Definition</b> — 3 rules: assertion compliance at definition-time
 *       (CEL on JSONB statements)
 *   <li><b>Normative Compliance — Execution</b> — 4 rules: assertion compliance from execution
 *       observations
 *   <li><b>Lifecycle Health</b> — 5 rules: lifecycle progression and staleness
 *   <li><b>Fabric Topology</b> — 4 rules: structural graph connectivity and governance distribution
 * </ul>
 *
 * <p>Unlike {@link AscriptionConsistencyRuleType} rules (which prevent structurally malformed
 * mutations), appraisal rules detect governance design issues and are evaluated at activation time
 * or on-demand via the Appraisal endpoint.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public enum AppraisalRuleType implements RuleType {

  // ====================================================================
  // Governance Coverage (8 rules)
  // Enforces the D→N→A chain is wired end-to-end.
  // ====================================================================

  /** Enforces that a Directive is operationalized by at least one Norm. */
  DIRECTIVE_NORM_OPERATIONALIZATION(
      "gsm:rules/appraisal/directive/norm/operationalization",
      "Directive Norm operationalization",
      "A Directive must be operationalized by at least one Norm "
          + "implementing its assertions — governance intent must be "
          + "translated into operational rules."),

  /** Enforces that all properties of a Directive's qualifier are covered by Norms. */
  DIRECTIVE_QUALIFIER_COVERAGE(
      "gsm:rules/appraisal/directive/qualifier/coverage",
      "Directive qualifier coverage",
      "Every property in a Directive's qualifier Archetype must be "
          + "covered by at least one Norm assertion — governance intent "
          + "must be fully operationalized."),

  /** Enforces that a Directive's purpose Structure has governed Ascriptions. */
  DIRECTIVE_PURPOSE_TARGETING(
      "gsm:rules/appraisal/directive/purpose/targeting",
      "Directive purpose targeting",
      "A Directive's purpose Structure must have at least one "
          + "governed Ascription — governance intent must have a "
          + "target to govern."),

  /**
   * Enforces that a Directive's governed Structure is reachable from an Interaction or Mechanism.
   */
  DIRECTIVE_PURPOSE_REACHABILITY(
      "gsm:rules/appraisal/directive/purpose/reachability",
      "Directive purpose reachability",
      "A Directive's purpose Structure must be reachable from at "
          + "least one active Interaction or Mechanism — the governed "
          + "entity must not be structurally isolated."),

  /** Enforces that a Norm has at least one bound Ascription. */
  NORM_ASCRIPTION_BINDING(
      "gsm:rules/appraisal/norm/ascription/binding",
      "Norm Ascription binding",
      "A Norm must have at least one current Ascription bound "
          + "to it — an operational rule must have governed "
          + "instances."),

  /**
   * Enforces that a Norm has a backing Directive (governance chain).
   *
   * <p><b>Implementation note:</b> this rule is also enforced synchronously at activation time (see
   * {@link cloud.poesis.sie.defman.service.AppraisalService#validateGovernanceChain}). The
   * appraisal variant detects the same condition asynchronously for Norms that were activated
   * before a Directive was deprecated.
   */
  NORM_DIRECTIVE_BACKING(
      "gsm:rules/appraisal/norm/directive/backing",
      "Norm Directive backing",
      "A Norm must be legitimated by an in-effect Directive whose "
          + "purpose matches its structure and whose qualifier is an "
          + "ancestor-or-equal of its qualifier in the archetype "
          + "hierarchy — governance authority requires Directive backing."),

  /** Enforces that a Structure is governed by at least one Directive or Norm. */
  STRUCTURE_GOVERNANCE_COVERAGE(
      "gsm:rules/appraisal/structure/governance/coverage",
      "Structure governance coverage",
      "A Structure must be targeted by at least one Directive or "
          + "Norm — the entity must operate within the governance "
          + "fabric."),

  /** Enforces that an Archetype is used as a qualifier by at least one Directive or Norm. */
  ARCHETYPE_QUALIFIER_UTILIZATION(
      "gsm:rules/appraisal/archetype/qualifier/utilization",
      "Archetype qualifier utilization",
      "An Archetype must be referenced as a qualifier by at least "
          + "one Directive or Norm — a schema must play a governance "
          + "role."),

  // ====================================================================
  // Governance Integrity (7 rules)
  // Enforces governance artifacts are non-contradictory and well-proportioned.
  // ====================================================================

  /**
   * Enforces verb compatibility between a Directive and others on the same qualifier + purpose.
   *
   * <p><b>Implementation note:</b> also enforced synchronously at activation time (see {@link
   * cloud.poesis.sie.defman.service.AppraisalService#validateDirectiveCompatibility}).
   */
  DIRECTIVE_VERB_COMPATIBILITY(
      "gsm:rules/appraisal/directive/verb/compatibility",
      "Directive verb compatibility",
      "A Directive must not carry a verb contradicting another "
          + "Directive on the same qualifier and purpose scope "
          + "(e.g. ENSURE vs PREVENT on the same viability "
          + "dimension)."),

  /**
   * Enforces modal compatibility between a Directive and others on the same qualifier + purpose +
   * verb.
   *
   * <p><b>Implementation note:</b> also enforced synchronously at activation time (see {@link
   * cloud.poesis.sie.defman.service.AppraisalService#validateDirectiveCompatibility}).
   */
  DIRECTIVE_MODAL_COMPATIBILITY(
      "gsm:rules/appraisal/directive/modal/compatibility",
      "Directive modal compatibility",
      "A Directive must not carry a modal contradicting another "
          + "Directive on the same qualifier, purpose, and verb "
          + "(e.g. MUST + MUST_NOT) — this is a governance "
          + "contradiction."),

  /**
   * Enforces assertion compatibility between a Norm and others on overlapping scope.
   *
   * <p><b>Implementation note:</b> also enforced synchronously at activation time (see {@link
   * cloud.poesis.sie.defman.service.AppraisalService#validateNormCompatibility}).
   */
  NORM_ASSERTION_COMPATIBILITY(
      "gsm:rules/appraisal/norm/assertion/compatibility",
      "Norm assertion compatibility",
      "A Norm must not carry assertions contradicting another Norm "
          + "on the same structure and overlapping qualifier lineage "
          + "— governance constraints must be mutually compatible."),

  /** Enforces authority exclusivity — one governance authority per scope. */
  DIRECTIVE_AUTHORITY_EXCLUSIVITY(
      "gsm:rules/appraisal/directive/authority/exclusivity",
      "Directive authority exclusivity",
      "A Directive's qualifier + purpose scope must have a single "
          + "authoritative governance source — conflicting authorities "
          + "on the same scope require explicit precedence "
          + "resolution."),

  /** Enforces proportional governance density for a Structure. */
  STRUCTURE_GOVERNANCE_PROPORTIONALITY(
      "gsm:rules/appraisal/structure/governance/proportionality",
      "Structure governance proportionality",
      "A Structure's governance density (bound Norm count) must "
          + "remain proportional relative to the tenant baseline — "
          + "disproportionate concentration may indicate "
          + "over-specification or conflated concerns."),

  /** Enforces that a Norm uses a specific, targeted qualifier. */
  NORM_QUALIFIER_SPECIFICITY(
      "gsm:rules/appraisal/norm/qualifier/specificity",
      "Norm qualifier specificity",
      "A Norm must use a specific, targeted qualifier rather than "
          + "a root-level Archetype — a governance rule should be "
          + "structurally precise."),

  /** Enforces alignment between a Directive's governance scope and operational reality. */
  DIRECTIVE_PURPOSE_OPERATIONAL_ALIGNMENT(
      "gsm:rules/appraisal/directive/purpose/operational-alignment",
      "Directive purpose operational alignment",
      "A Directive's purpose Structure must have at least one "
          + "active Mechanism or Interaction operating on it — "
          + "governance scope must align with operational reality."),

  // ====================================================================
  // Source Fidelity (4 rules)
  // Enforces synchronization with external authority sources.
  // ====================================================================

  /** Enforces content alignment between a sourced artifact and its authority. */
  SOURCED_CONTENT_ALIGNMENT(
      "gsm:rules/appraisal/sourced/content/alignment",
      "Sourced content alignment",
      "A sourced artifact must remain aligned with its external "
          + "authority's latest known version — the local copy must "
          + "reflect upstream state."),

  /** Enforces timely synchronization of a sourced artifact. */
  SOURCED_SYNC_FRESHNESS(
      "gsm:rules/appraisal/sourced/sync/freshness",
      "Sourced sync freshness",
      "The time since a sourced artifact's last successful "
          + "synchronization must not exceed the configured staleness "
          + "threshold — sync pipelines must run on schedule."),

  /** Enforces that a sourced artifact's mapping references an existing external artifact. */
  SOURCED_MAPPING_INTEGRITY(
      "gsm:rules/appraisal/sourced/mapping/integrity",
      "Sourced mapping integrity",
      "A sourced artifact's mapping must reference an existing "
          + "external authority artifact — the local artifact must "
          + "have a valid upstream counterpart."),

  /** Enforces unilateral synchronization (no bilateral divergence) for a sourced artifact. */
  SOURCED_SYNC_DIVERGENCE(
      "gsm:rules/appraisal/sourced/sync/divergence",
      "Sourced sync divergence",
      "A sourced artifact must not have diverged bilaterally (both "
          + "local and remote changed since last sync) — divergence "
          + "must be reconciled before proceeding."),

  // ====================================================================
  // Normative Compliance, Definition (3 rules)
  // Enforces that Ascription statements comply with Norm assertions.
  // ====================================================================

  /** Enforces that an Ascription's statement satisfies Norm CEL assertions at definition-time. */
  ASCRIPTION_STATEMENT_NORM_COMPLIANCE(
      "gsm:rules/appraisal/ascription/statement/norm-compliance",
      "Ascription statement Norm compliance",
      "An Ascription's statement must satisfy the CEL assertions "
          + "of all applicable Norms — normative constraints must "
          + "hold at definition-time."),

  /** Enforces that a Norm's applicability filter matches at least one Ascription. */
  NORM_APPLICABILITY_TARGET_MATCH(
      "gsm:rules/appraisal/norm/applicability/target-match",
      "Norm applicability target match",
      "A Norm's applicability filter (CEL expression) must match "
          + "at least one in-effect Ascription — an operational rule "
          + "must have applicable targets."),

  /** Enforces that Norm assertions can be fully evaluated against an Ascription's statement. */
  ASCRIPTION_STATEMENT_COMPLIANCE_EVALUABILITY(
      "gsm:rules/appraisal/ascription/statement/compliance-evaluability",
      "Ascription statement compliance evaluability",
      "Norm assertions applicable to an Ascription must reference "
          + "statement properties that are present and non-null in "
          + "its statement — compliance state must be deterministic."),

  // ====================================================================
  // Normative Compliance, Execution (4 rules)
  // Enforces that Mechanism execution complies with Norm assertions.
  // ====================================================================

  /** Enforces that execution observations for a governed entity satisfy Norm assertions. */
  ASCRIPTION_EXECUTION_NORM_COMPLIANCE(
      "gsm:rules/appraisal/ascription/execution/norm-compliance",
      "Ascription execution Norm compliance",
      "Execution observation data for a governed entity must "
          + "satisfy all applicable Norm assertions — normative "
          + "constraints must hold in production."),

  /** Enforces that a Norm has configured observation sources for execution evaluation. */
  NORM_OBSERVATION_COVERAGE(
      "gsm:rules/appraisal/norm/observation/coverage",
      "Norm observation coverage",
      "A Norm must have a configured observation source (Receptor, "
          + "telemetry pipeline) for its governed Structure — execution "
          + "compliance must be assessable."),

  /** Enforces that execution metrics for a governed entity stay within Norm-defined tolerances. */
  ASCRIPTION_EXECUTION_TOLERANCE_COMPLIANCE(
      "gsm:rules/appraisal/ascription/execution/tolerance-compliance",
      "Ascription execution tolerance compliance",
      "Execution metrics for a governed entity must remain within "
          + "the tolerance thresholds defined by applicable Norm "
          + "assertions — the entity must operate within acceptable "
          + "parameters."),

  /** Enforces stable compliance trends for a governed entity over the observation window. */
  ASCRIPTION_EXECUTION_TREND_STABILITY(
      "gsm:rules/appraisal/ascription/execution/trend-stability",
      "Ascription execution trend stability",
      "Execution compliance metrics for a governed entity must "
          + "remain stable or improving over the configured observation "
          + "window — sustained degradation requires attention even if "
          + "the current value is within tolerance."),

  // ====================================================================
  // Lifecycle Health (5 rules)
  // Enforces that Ascriptions progress through their lifecycle.
  // ====================================================================

  /** Enforces timely progression of an Ascription out of DRAFT status. */
  ASCRIPTION_DRAFT_PROGRESSION(
      "gsm:rules/appraisal/ascription/draft/progression",
      "Ascription draft progression",
      "An Ascription in DRAFT status must progress within the "
          + "configured staleness threshold — a stale draft may "
          + "indicate abandoned work or a blocked workflow."),

  /** Enforces timely review of an Ascription awaiting approval. */
  ASCRIPTION_REVIEW_TIMELINESS(
      "gsm:rules/appraisal/ascription/review/timeliness",
      "Ascription review timeliness",
      "An Ascription in PROPOSED status must be reviewed within "
          + "the configured SLA — a stalled review blocks governance "
          + "progression."),

  /** Enforces that a deprecated Ascription has a successor. */
  ASCRIPTION_DEPRECATION_SUCCESSION(
      "gsm:rules/appraisal/ascription/deprecation/succession",
      "Ascription deprecation succession",
      "A deprecated Ascription must have a successor Ascription "
          + "for the same Definition in a non-terminal state — "
          + "governance intent must be preserved through replacement."),

  /** Enforces timely resolution of a suspended Ascription. */
  ASCRIPTION_SUSPENSION_RESOLUTION(
      "gsm:rules/appraisal/ascription/suspension/resolution",
      "Ascription suspension resolution",
      "A suspended Ascription must be resolved within the "
          + "configured maximum suspension duration — extended "
          + "suspension requires escalation."),

  /** Enforces manageable activation backlogs for Ascriptions. */
  ASCRIPTION_ACTIVATION_THROUGHPUT(
      "gsm:rules/appraisal/ascription/activation/throughput",
      "Ascription activation throughput",
      "An approved Ascription is part of an activation backlog that "
          + "must stay within the configured threshold — deployment "
          + "throughput must keep pace with governance demand."),

  // ====================================================================
  // Fabric Topology (4 rules)
  // Enforces structural graph connectivity and governance distribution.
  // ====================================================================

  /** Enforces that a Mechanism has at least one Effector. */
  MECHANISM_EFFECTOR_BINDING(
      "gsm:rules/appraisal/mechanism/effector/binding",
      "Mechanism Effector binding",
      "A Mechanism must have at least one Effector — a Mechanism "
          + "must be capable of actuating change within the governance "
          + "fabric."),

  /** Enforces that a Structure's cluster is connected to the governance fabric. */
  STRUCTURE_FABRIC_CONNECTIVITY(
      "gsm:rules/appraisal/structure/fabric/connectivity",
      "Structure fabric connectivity",
      "A Structure must be connected to the broader governance "
          + "fabric via at least one Interaction path — governance "
          + "decisions must be able to propagate to and from the "
          + "entity."),

  /** Enforces bidirectional governance for a governed entity where expected. */
  STRUCTURE_GOVERNANCE_RECIPROCITY(
      "gsm:rules/appraisal/structure/governance/reciprocity",
      "Structure governance reciprocity",
      "Governance relationships involving a governed entity should be "
          + "reciprocal where bidirectionality is expected — one-way "
          + "governance creates feedback blind spots."),

  /** Enforces non-overlapping framework scopes for a Directive. */
  DIRECTIVE_FRAMEWORK_SCOPE_EXCLUSIVITY(
      "gsm:rules/appraisal/directive/framework-scope/exclusivity",
      "Directive framework scope exclusivity",
      "A Directive must not overlap in qualifier + purpose scope "
          + "with Directives from other sourced frameworks — framework "
          + "scopes must be reconciled or explicitly prioritized.");

  // ====================================================================
  // Fields and accessors
  // ====================================================================

  private final String type;
  private final String title;
  private final String description;

  AppraisalRuleType(String type, String title, String description) {
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
