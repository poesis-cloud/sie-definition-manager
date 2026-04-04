package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.exception.InternalException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import java.util.Map;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Translates {@link DataIntegrityViolationException} into domain-specific exceptions by mapping
 * known PostgreSQL constraint names to {@link AscriptionConsistencyRuleType}.
 *
 * <p>Extracted from {@link AbstractAscriptionService} to eliminate duplication between the create
 * template and the lifecycle orchestrator.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
final class PersistenceExceptionTranslationService {

  private static final Logger LOG =
      LoggerFactory.getLogger(PersistenceExceptionTranslationService.class);

  private PersistenceExceptionTranslationService() {}

  // Known PostgreSQL constraint → AscriptionConsistencyRuleType mapping
  // (auto-generated FK names and partial unique indexes)
  static final Map<String, AscriptionConsistencyRuleType> CONSTRAINT_TO_RULE =
      Map.ofEntries(
          // Directive reference FKs
          Map.entry(
              "directive_structure_id_fkey",
              AscriptionConsistencyRuleType.DIRECTIVE_STRUCTURE_REFERENCE_INTEGRITY),
          Map.entry(
              "directive_qualifier_id_fkey",
              AscriptionConsistencyRuleType.DIRECTIVE_QUALIFIER_REFERENCE_INTEGRITY),
          // Norm reference FKs
          Map.entry(
              "norm_structure_id_fkey",
              AscriptionConsistencyRuleType.NORM_STRUCTURE_REFERENCE_INTEGRITY),
          Map.entry(
              "norm_qualifier_id_fkey",
              AscriptionConsistencyRuleType.NORM_QUALIFIER_REFERENCE_INTEGRITY),
          // Effector / Receptor reference FKs
          Map.entry(
              "effector_mechanism_id_fkey",
              AscriptionConsistencyRuleType.EFFECTOR_MECHANISM_REFERENCE_INTEGRITY),
          Map.entry(
              "effector_output_archetype_id_fkey",
              AscriptionConsistencyRuleType.EFFECTOR_ARCHETYPE_REFERENCE_INTEGRITY),
          Map.entry(
              "receptor_mechanism_id_fkey",
              AscriptionConsistencyRuleType.RECEPTOR_MECHANISM_REFERENCE_INTEGRITY),
          Map.entry(
              "receptor_input_archetype_id_fkey",
              AscriptionConsistencyRuleType.RECEPTOR_ARCHETYPE_REFERENCE_INTEGRITY),
          // Mechanism structure FK
          Map.entry(
              "mechanism_structure_id_fkey",
              AscriptionConsistencyRuleType.MECHANISM_STRUCTURE_REFERENCE_INTEGRITY),
          // Interaction reference FKs
          Map.entry(
              "interaction_effector_id_fkey",
              AscriptionConsistencyRuleType.INTERACTION_EFFECTOR_REFERENCE_INTEGRITY),
          Map.entry(
              "interaction_receptor_id_fkey",
              AscriptionConsistencyRuleType.INTERACTION_RECEPTOR_REFERENCE_INTEGRITY),
          // Archetype self-typing FK
          Map.entry(
              "archetype_typed_by_fk",
              AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_REFERENCE_INTEGRITY),
          // Identity uniqueness indexes
          Map.entry(
              "uq_structure_purpose",
              AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS),
          Map.entry(
              "uq_mechanism_function",
              AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS),
          Map.entry(
              "uq_archetype_title",
              AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS));

  /**
   * Translates a {@link DataIntegrityViolationException} to a domain exception.
   *
   * @param ex the persistence exception to translate
   * @return a {@link RuleViolationException} for known constraints, or an {@link InternalException}
   *     for unmapped constraints
   */
  static RuntimeException translate(DataIntegrityViolationException ex) {
    String constraintName = extractConstraintName(ex);
    if (constraintName != null) {
      AscriptionConsistencyRuleType ruleType = CONSTRAINT_TO_RULE.get(constraintName);
      if (ruleType == null && constraintName.endsWith("_archetype_id_fkey")) {
        ruleType = AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_REFERENCE_INTEGRITY;
      }
      if (ruleType != null) {
        return RuleViolationException.of(
            ruleType,
            "Database constraint violation: " + constraintName,
            ex,
            "constraint",
            constraintName);
      }
    }
    LOG.error("Unmapped DB constraint violation (constraint={})", constraintName, ex);
    return new InternalException(
        "Database constraint violation" + (constraintName != null ? ": " + constraintName : ""),
        ex);
  }

  /**
   * Extracts the underlying constraint name from a persistence exception.
   *
   * @param ex the persistence exception
   * @return the constraint name, or {@code null} if unavailable
   */
  static String extractConstraintName(DataIntegrityViolationException ex) {
    Throwable cause = ex.getCause();
    if (cause instanceof ConstraintViolationException cve) {
      return cve.getConstraintName();
    }
    return null;
  }
}
