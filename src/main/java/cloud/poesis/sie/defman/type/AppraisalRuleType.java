package cloud.poesis.sie.defman.type;

/**
 * Governance evaluation rules checked during Appraisal.
 *
 * <p>These rules assess governance coherence (Directive/Norm compatibility, governance chain,
 * conflict detection) and are evaluated at activation time or on-demand via the Appraisal endpoint.
 * Unlike {@link AscriptionConsistencyRuleType} rules, violations here indicate governance design
 * issues rather than structural malformation.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public enum AppraisalRuleType implements GsmRuleType {

  // ====================================================================
  // DIRECTIVE — governance compatibility
  // ====================================================================

  DIRECTIVE_COMPATIBILITY_ON_VERB(
      "gsm:rules/directive/compatibility/on-verb",
      "Directive compatibility on verb",
      "Directives targeting the same qualifier Archetype and the same "
          + "purpose Structure must not carry contradictory verb "
          + "directions (e.g. ENSURE vs PREVENT on the same viability "
          + "dimension)."),

  DIRECTIVE_COMPATIBILITY_ON_MODAL(
      "gsm:rules/directive/compatibility/on-modal",
      "Directive compatibility on modal",
      "Directives targeting the same qualifier, purpose, and verb must "
          + "not carry a positive modal and its negation (e.g. MUST + "
          + "MUST_NOT on the same verb) — this is a contradiction."),

  // ====================================================================
  // NORM — governance chain and conflict detection
  // ====================================================================

  NORM_DIRECTED(
      "gsm:rules/norm/directed",
      "Norm directed",
      "A Norm must be legitimated by an in-effect Directive whose "
          + "purpose matches the Norm's structure and whose qualifier "
          + "is an ancestor-or-equal of the Norm's qualifier in the "
          + "allOf chain — no Directive backing means no governance "
          + "authority for this Norm."),

  NORM_COMPATIBILITY(
      "gsm:rules/norm/compatibility",
      "Norm compatibility",
      "Norms targeting the same structure and the same or overlapping "
          + "qualifier lineage must not carry contradictory assertions "
          + "on the same property paths — conflicting governance "
          + "constraints indicate a governance design error.");

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
