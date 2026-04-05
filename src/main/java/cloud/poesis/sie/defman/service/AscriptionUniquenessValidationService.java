package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Validates uniqueness constraints for ascription statement properties.
 *
 * <p>Provides two complementary checks:
 *
 * <ul>
 *   <li><b>Annotation-driven</b> ({@link #validate}): walks the archetype schema, discovers {@code
 *       $gsm:unique}-annotated properties, and validates each against in-effect ascriptions from
 *       other definitions. Called at creation time (step 6 of the create template).
 *   <li><b>Property-level</b> ({@link #validatePropertyAcrossDefinitions}): validates a single
 *       handler-declared property value against a caller-supplied collection of in-effect siblings.
 *       Called by handlers during activation.
 * </ul>
 *
 * <p>Both checks enforce the same rule: {@link
 * AscriptionConsistencyRuleType#ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS}.
 *
 * <p>Consumed by {@link AscriptionService} for the annotation-driven path.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class AscriptionUniquenessValidationService {

  private final AscriptionRepository ascriptionRepository;

  AscriptionUniquenessValidationService(AscriptionRepository ascriptionRepository) {
    this.ascriptionRepository = ascriptionRepository;
  }

  // ======================================================================
  // Annotation-driven uniqueness ($gsm:unique) — creation time
  // ======================================================================

  /**
   * Validates {@code $gsm:unique}-annotated statement properties against in-effect ascriptions of
   * the same archetype from other definitions.
   *
   * @param statement the JSON statement payload being created
   * @param archetype the typing archetype (schema source for annotation discovery)
   * @param definitionId the definition the new ascription belongs to (excluded from duplicate
   *     search)
   * @throws RuleViolationException with {@link
   *     AscriptionConsistencyRuleType#ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS} if a
   *     duplicate is found
   */
  void validate(JsonNode statement, ArchetypeEntity archetype, UUID definitionId) {
    JsonNode archetypeStmt = archetype.getStatement();
    if (archetypeStmt == null) {
      return;
    }
    JsonNode properties = archetypeStmt.get("properties");
    if (properties == null || !properties.isObject()) {
      return;
    }

    List<AscriptionEntity> inEffect = null; // lazy-loaded

    for (Map.Entry<String, JsonNode> entry : properties.properties()) {
      String propName = entry.getKey();
      JsonNode propSchema = entry.getValue();

      if (!ArchetypeParsingService.hasAnnotation(propSchema, "$gsm:unique")) {
        continue;
      }
      if (!statement.has(propName)) {
        continue;
      }

      if (inEffect == null) {
        inEffect =
            ascriptionRepository.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
                archetype.getId(),
                EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED),
                definitionId);
      }

      JsonNode value = statement.get(propName);
      String valueStr = value.isTextual() ? value.asText() : value.toString();

      for (AscriptionEntity existing : inEffect) {
        JsonNode existingStmt = existing.getStatement();
        if (existingStmt.has(propName)) {
          JsonNode existingVal = existingStmt.get(propName);
          String existingStr =
              existingVal.isTextual() ? existingVal.asText() : existingVal.toString();
          if (valueStr.equals(existingStr)) {
            throw RuleViolationException.of(
                AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
                propName
                    + " '"
                    + valueStr
                    + "' duplicates ascription "
                    + existing.getId()
                    + " (definition "
                    + existing.getDefinition().getId()
                    + ")",
                "field",
                propName,
                "value",
                valueStr,
                "conflictingAscriptionId",
                existing.getId(),
                "conflictingDefinitionId",
                existing.getDefinition().getId());
          }
        }
      }
    }
  }

  // ======================================================================
  // Property-level uniqueness — activation time (used by handlers)
  // ======================================================================

  /**
   * Validates that a statement property value is unique across all in-effect ascriptions of the
   * same subject type from <em>different</em> definitions.
   *
   * <p>Called by {@link AscriptionSubtypeService#validateActivationUniqueness} implementations for
   * subject types whose identity is defined by a single statement property (Structure/{@code
   * purpose}, Mechanism/{@code function}, Archetype/{@code title}). Ascriptions belonging to the
   * same definition as {@code thisDefId} are excluded from the check.
   *
   * @param subjectType the GSM subject type being validated
   * @param propertyName the statement property whose uniqueness is enforced
   * @param propertyValue the value to check for duplicates
   * @param thisDefId the definition being activated (excluded from duplicate search)
   * @param inEffectSiblings in-effect ascriptions of the same type to check against
   * @throws RuleViolationException with {@link
   *     AscriptionConsistencyRuleType#ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS} if a
   *     duplicate is found
   */
  public static void validatePropertyAcrossDefinitions(
      DefinitionSubjectType subjectType,
      String propertyName,
      String propertyValue,
      UUID thisDefId,
      Collection<? extends AscriptionEntity> inEffectSiblings) {
    for (AscriptionEntity sibling : inEffectSiblings) {
      if (sibling.getDefinition().getId().equals(thisDefId)) continue;
      JsonNode sibStmt = sibling.getStatement();
      String sibValue =
          (sibStmt != null && sibStmt.has(propertyName))
              ? sibStmt.get(propertyName).asText()
              : null;
      if (propertyValue.equals(sibValue)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
            subjectType.getPrimitiveType().getLabel()
                + " "
                + propertyName
                + " '"
                + propertyValue
                + "' already in effect",
            "field",
            propertyName,
            "value",
            propertyValue);
      }
    }
  }
}
