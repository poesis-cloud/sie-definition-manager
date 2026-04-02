package cloud.poesis.sie.defman.dto;

import cloud.poesis.sie.defman.type.AppraisalRuleType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.hateoas.server.core.Relation;

/**
 * Appraisal resource — a <b>stateless, on-demand governance evaluation</b> computed by defman from
 * the current state of persisted ascriptions.
 *
 * <p>Appraisals are <b>derived, not persisted</b>. Each {@code GET /ascriptions/{id}/appraisals}
 * call recomputes the full set of applicable rule evaluations against the ascription's current
 * relational context (Directive → Norm → Archetype, etc.) and returns them as a collection of
 * {@code AppraisalDto} items. There is no appraisal table, no stable identity, no lifecycle — each
 * response is a fresh projection of governance health <i>at this instant</i>.
 *
 * <p>This is the observational counterpart of {@link
 * cloud.poesis.sie.defman.exception.RuleViolationException}. Where a {@code RuleViolationException}
 * is <b>synchronous and preventive</b> (thrown at mutation time, blocks the operation), an {@code
 * AppraisalDto} is <b>on-demand and observational</b> (computed when requested, returned for
 * review, then forgotten by defman).
 *
 * <h3>Design principles</h3>
 *
 * <ul>
 *   <li><b>Generic envelope</b>: every appraisal, regardless of category or rule, shares the same
 *       API shape. The BFF/UI derives category, finding type, and surface from {@link #ruleId} via
 *       {@link AppraisalRuleType} metadata — those fields are rule-intrinsic, not instance data.
 *   <li><b>Polymorphic evidence</b>: rule-specific data lives in the {@link #evidence} JSONB
 *       payload. The schema of {@code evidence} is implicit from {@link #ruleId} — the UI selects
 *       the detail-panel template accordingly. This mirrors how {@link
 *       cloud.poesis.sie.defman.dto.AscriptionDto#getStatement()} is typed by the Archetype.
 *   <li><b>Severity inputs (not severity itself)</b>: defman exposes the raw governance signals
 *       needed for severity derivation — {@link #modalSourceDirectiveId} (for MODAL rules: the
 *       Directive whose modal verb provides governance weight). The actual mapping to a
 *       presentation-level severity scale (e.g. CRITICAL/WARNING/INFO) is a <b>client-domain
 *       responsibility</b> (ITIP, BFF, or other consumers).
 *   <li><b>Stateless / no persistence</b>: defman does not store, track, or resolve appraisals.
 *       Consumers (BFF, audit services) may persist snapshots if they need historical comparison or
 *       resolution tracking — that is their responsibility. Evolution to event publication (Option
 *       C) can be added later without changing this API shape.
 * </ul>
 *
 * <h3>Structural analogy to existing GSM patterns</h3>
 *
 * <table>
 * <tr>
 * <th>GSM concept</th>
 * <th>Appraisal analog</th>
 * </tr>
 * <tr>
 * <td>{@link cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType}</td>
 * <td>{@link #ruleId} — the rule that was checked</td>
 * </tr>
 * <tr>
 * <td>{@link cloud.poesis.sie.defman.exception.RuleViolationException}</td>
 * <td>{@code AppraisalDto} — the result of checking</td>
 * </tr>
 * <tr>
 * <td>{@code statement} (JSONB)</td>
 * <td>{@link #evidence} (JSONB) — polymorphic payload</td>
 * </tr>
 * <tr>
 * <td>{@code archetype_id} → schema</td>
 * <td>{@link #ruleId} → evidence schema + rule-intrinsic metadata</td>
 * </tr>
 * </table>
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Relation(collectionRelation = "appraisals")
@Schema(
    name = "Appraisal",
    description = "Stateless governance appraisal computed on demand from current ascription state")
public class AppraisalDto {

  // ======================================================================
  // Rule identification
  // ======================================================================

  @Schema(
      description =
          "The appraisal rule that produced this result. "
              + "Determines the evidence schema and the detail-panel template in the UI. "
              + "Rule-intrinsic metadata (category, findingType, surface) "
              + "is accessible via AppraisalRuleType getters.")
  private final AppraisalRuleType ruleId;

  // ======================================================================
  // Governance provenance — raw signals for client-side severity derivation
  // ======================================================================

  @Schema(
      description =
          "For rules with modal-derived governance weight: the Directive whose modal verb "
              + "(MUST/SHOULD/MAY) provides the governance signal. "
              + "Clients derive their own severity scale from this. "
              + "Null when the rule does not depend on a Directive's modal.",
      nullable = true)
  private final UUID modalSourceDirectiveId;

  // ======================================================================
  // Scope — which entities are involved
  // ======================================================================

  @Schema(
      description =
          "The entities involved in this appraisal — always at least one. "
              + "Each subject carries a role label (e.g. 'governing', 'governed', 'source') "
              + "to disambiguate multi-entity appraisals like conflicts.")
  private final List<Subject> subjects;

  // ======================================================================
  // Evidence — polymorphic payload, schema keyed by ruleId
  // ======================================================================

  @Schema(
      description =
          "Rule-specific evidence payload. Schema is implicit from ruleId — the UI selects "
              + "the detail-panel template accordingly. "
              + "Examples: for GC-1 (Directive operationalization) this contains directiveRef, "
              + "qualifierRef, normCount; for NC-D1 (Assertion compliance) this contains "
              + "normRef, assertion, evaluatedProperties, result.",
      implementation = Map.class)
  private final JsonNode evidence;

  // ======================================================================
  // Provenance — governance context for this result
  // ======================================================================

  @Schema(
      description =
          "For NC-D and NC-E appraisals: the Norm whose assertion was evaluated. "
              + "Null for non-normative-compliance categories.",
      nullable = true)
  private final UUID normRef;

  @Schema(
      description =
          "For appraisals governed by a Directive: the Directive providing governance "
              + "authority and modal verb. Null when the appraisal has no governing Directive.",
      nullable = true)
  private final UUID directiveRef;

  @Schema(
      description =
          "Human-readable summary of the appraisal result. "
              + "Analogous to RuleViolationException.getMessage().")
  private final String detail;

  // ======================================================================
  // Constructor
  // ======================================================================

  public AppraisalDto(
      AppraisalRuleType ruleId,
      UUID modalSourceDirectiveId,
      List<Subject> subjects,
      JsonNode evidence,
      UUID normRef,
      UUID directiveRef,
      String detail) {
    this.ruleId = ruleId;
    this.modalSourceDirectiveId = modalSourceDirectiveId;
    this.subjects = subjects != null ? List.copyOf(subjects) : List.of();
    this.evidence = evidence;
    this.normRef = normRef;
    this.directiveRef = directiveRef;
    this.detail = detail;
  }

  // ======================================================================
  // Accessors
  // ======================================================================

  public AppraisalRuleType getRuleId() {
    return ruleId;
  }

  public UUID getModalSourceDirectiveId() {
    return modalSourceDirectiveId;
  }

  public List<Subject> getSubjects() {
    return subjects;
  }

  public JsonNode getEvidence() {
    return evidence;
  }

  public UUID getNormRef() {
    return normRef;
  }

  public UUID getDirectiveRef() {
    return directiveRef;
  }

  public String getDetail() {
    return detail;
  }

  // ======================================================================
  // Subject — an entity involved in an appraisal
  // ======================================================================

  /**
   * An entity involved in an appraisal.
   *
   * <p>Appraisals always involve at least one subject. Multi-entity appraisals (e.g. conflicts)
   * carry multiple subjects with distinguishing roles. The {@link #role} field disambiguates them
   * in the UI (e.g. "governing Structure", "conflicting Directive", "governed Norm").
   *
   * @param definitionId the stable Definition identity of the involved entity
   * @param ascriptionId the specific Ascription (version) involved, if applicable; may be null when
   *     the appraisal concerns the Definition as a whole
   * @param subjectType the GSM structural type
   * @param role a human-readable role label for UI disambiguation (e.g. "governing", "governed",
   *     "source", "conflicting")
   */
  @Schema(name = "Appraisal.Subject", description = "Entity involved in an appraisal")
  public record Subject(
      @Schema(description = "Definition ID of the involved entity") UUID definitionId,
      @Schema(
              description =
                  "Ascription ID of the specific version involved. "
                      + "Null when the appraisal concerns the Definition as a whole.",
              nullable = true)
          UUID ascriptionId,
      @Schema(description = "GSM structural type of the involved entity")
          DefinitionSubjectType subjectType,
      @Schema(
              description =
                  "Role of this entity in the appraisal "
                      + "(e.g. 'governing', 'governed', 'source', 'conflicting')")
          String role) {}
}
