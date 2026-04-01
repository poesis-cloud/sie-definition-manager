package cloud.poesis.sie.defman.type;

/**
 * Sealed interface for all GSM rule-type enums.
 *
 * <p>Every enum constant carries a stable machine-readable URI, a short human-readable title, and a
 * natural-language description of the rule. The interface is implemented by:
 *
 * <ul>
 *   <li>{@link AscriptionConsistencyRuleType} — creation-time consistency rules (always-true
 *       preconditions)
 *   <li>{@link AscriptionStatusTransitionRuleType} — lifecycle status transition rules
 *   <li>{@link AppraisalRuleType} — governance evaluation rules (Appraisal domain)
 * </ul>
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public sealed interface RuleType
    permits AscriptionConsistencyRuleType, AscriptionStatusTransitionRuleType, AppraisalRuleType {

  /**
   * Returns the stable machine-readable URI ({@code gsm:rules/…}).
   *
   * @return the rule type URI; never {@code null}
   */
  String getType();

  /**
   * Returns a short human-readable label for RFC 9457 ProblemDetail.
   *
   * @return the rule title; never {@code null}
   */
  String getTitle();

  /**
   * Returns the natural-language statement of the rule.
   *
   * @return the rule description; never {@code null}
   */
  String getDescription();

  /**
   * Returns the enum constant name.
   *
   * @return the constant name; never {@code null}
   */
  String name();
}
