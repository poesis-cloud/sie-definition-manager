package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Validates identity-bound invariants for ascription entities.
 *
 * <p>Enforces that identity-bound fields (both handler-declared entity-level fields and {@code
 * $gsm:identityBound}-annotated statement properties) remain constant across all ascriptions within
 * the same definition.
 *
 * <p>Consumed by {@link AscriptionService}.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
class AscriptionIdentityBoundValidationService {

  /**
   * Validates identity-bound invariant for a new ascription entity.
   *
   * <p>Checks both handler-declared (entity-level) and annotation-declared ({@code
   * $gsm:identityBound}) fields against existing ascriptions in the same definition.
   *
   * @param handler the subtype handler providing identity-bound field declarations
   * @param entity the new ascription entity being created
   * @param archetype the typing archetype (schema source for annotation discovery)
   * @param <T> the concrete ascription entity type
   * @throws RuleViolationException with {@link
   *     AscriptionConsistencyRuleType#ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION} if any
   *     identity-bound field changed
   */
  <T extends AscriptionEntity> void validate(
      AscriptionSubtypeService<T> handler, T entity, ArchetypeEntity archetype) {
    UUID definitionId = entity.getDefinition().getId();
    validateHandlerDeclared(handler, entity, definitionId);
    validateAnnotationDeclared(
        entity.getStatement(), archetype, definitionId, handler::findAllByDefinitionId);
  }

  // ======================================================================
  // Handler-declared identity-bound fields (entity-level)
  // ======================================================================

  private <T extends AscriptionEntity> void validateHandlerDeclared(
      AscriptionSubtypeService<T> handler, T entity, UUID definitionId) {
    Map<String, Object> newValues = handler.getIdentityBoundValues(entity);
    if (newValues.isEmpty()) {
      return;
    }
    List<T> existing = handler.findAllByDefinitionId(definitionId);
    if (existing.isEmpty()) {
      return;
    }
    AscriptionEntity first = existing.getLast();
    Map<String, Object> firstValues = handler.getIdentityBoundValues(first);

    for (var entry : newValues.entrySet()) {
      String field = entry.getKey();
      Object newVal = entry.getValue();
      Object firstVal = firstValues.get(field);
      if (!Objects.equals(newVal, firstVal)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION,
            "Identity-bound field '"
                + field
                + "' changed: expected '"
                + firstVal
                + "' but got '"
                + newVal
                + "'",
            "field",
            field,
            "definitionId",
            definitionId,
            "expectedValue",
            String.valueOf(firstVal),
            "actualValue",
            String.valueOf(newVal));
      }
    }
  }

  // ======================================================================
  // Annotation-declared identity-bound fields ($gsm:identityBound)
  // ======================================================================

  private void validateAnnotationDeclared(
      JsonNode statement,
      ArchetypeEntity archetype,
      UUID definitionId,
      java.util.function.Function<UUID, List<? extends AscriptionEntity>> existingFinder) {
    JsonNode archetypeStmt = archetype.getStatement();
    if (archetypeStmt == null) {
      return;
    }
    JsonNode properties = archetypeStmt.get("properties");
    if (properties == null || !properties.isObject()) {
      return;
    }

    List<? extends AscriptionEntity> existing = null; // lazy-loaded

    for (Map.Entry<String, JsonNode> entry : properties.properties()) {
      String propName = entry.getKey();
      JsonNode propSchema = entry.getValue();

      if (!ArchetypeParsingService.hasAnnotation(propSchema, "$gsm:identityBound")) {
        continue;
      }
      if (!statement.has(propName)) {
        continue;
      }

      if (existing == null) {
        existing = existingFinder.apply(definitionId);
        if (existing.isEmpty()) {
          return;
        }
      }

      AscriptionEntity first = existing.getLast();
      JsonNode firstStmt = first.getStatement();
      if (!firstStmt.has(propName)) {
        continue;
      }

      JsonNode value = statement.get(propName);
      String newStr = value.isTextual() ? value.asText() : value.toString();
      JsonNode firstVal = firstStmt.get(propName);
      String firstStr = firstVal.isTextual() ? firstVal.asText() : firstVal.toString();

      if (!newStr.equals(firstStr)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION,
            "Identity-bound field '"
                + propName
                + "' changed: expected '"
                + firstStr
                + "' but got '"
                + newStr
                + "'",
            "field",
            propName,
            "definitionId",
            definitionId,
            "expectedValue",
            firstStr,
            "actualValue",
            newStr);
      }
    }
  }
}
