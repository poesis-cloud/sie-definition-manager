package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Enforces {@code $gsm:*} vocabulary annotations on ascription statements at authoring time.
 *
 * <p>Extracted from {@link AscriptionStatementValidationService} to separate annotation enforcement
 * concerns (identity-bound, uniqueness, data protection) from JSON Schema validation.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class AscriptionArchetypeSchemaAnnotationEnforcementService {

  private static final Logger LOG =
      LoggerFactory.getLogger(AscriptionArchetypeSchemaAnnotationEnforcementService.class);

  private final AscriptionService ascriptionService;
  private final AscriptionProtectionService statementProtection;

  public AscriptionArchetypeSchemaAnnotationEnforcementService(
      @Lazy AscriptionService ascriptionService, AscriptionProtectionService statementProtection) {
    this.ascriptionService = ascriptionService;
    this.statementProtection = statementProtection;
  }

  /**
   * Enforces {@code $gsm:*} vocabulary keywords on a statement at authoring time.
   *
   * @param statement the JSON statement payload
   * @param archetype the archetype carrying vocabulary annotations
   * @param definitionId the definition UUID (for uniqueness scoping)
   * @param existingFinder function to find existing ascriptions for a definition (provided by the
   *     calling subtype service)
   * @throws RuleViolationException if an annotation constraint is violated
   */
  void enforceAnnotations(
      JsonNode statement,
      ArchetypeEntity archetype,
      UUID definitionId,
      Function<UUID, List<? extends AscriptionEntity>> existingFinder) {
    JsonNode archetypeStmt = archetype.getStatement();
    if (archetypeStmt == null) {
      return;
    }

    JsonNode properties = archetypeStmt.get("properties");
    if (properties == null || !properties.isObject()) {
      return;
    }

    for (Map.Entry<String, JsonNode> entry : properties.properties()) {
      String propName = entry.getKey();
      JsonNode propSchema = entry.getValue();

      if (!statement.has(propName)) {
        continue;
      }

      JsonNode value = statement.get(propName);

      if (ArchetypeParsingService.hasAnnotation(propSchema, "$gsm:identityBound")) {
        enforceStatementIdentityBound(propName, value, definitionId, existingFinder);
      }

      if (ArchetypeParsingService.hasAnnotation(propSchema, "$gsm:unique")) {
        enforceUnique(propName, value, archetype, definitionId);
      }

      if (propSchema.has("$gsm:dataProtection")) {
        statementProtection.applyAtRestProtection(
            propSchema.get("$gsm:dataProtection"), propName, (ObjectNode) statement);
      }
    }
  }

  // ======================================================================
  // Annotation enforcement helpers
  // ======================================================================

  private void enforceUnique(
      String propName, JsonNode value, ArchetypeEntity archetype, UUID definitionId) {
    List<AscriptionEntity> inEffect =
        ascriptionService.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
            archetype.getId(),
            EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED),
            definitionId);

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

  private void enforceStatementIdentityBound(
      String propName,
      JsonNode value,
      UUID definitionId,
      Function<UUID, List<? extends AscriptionEntity>> existingFinder) {
    List<? extends AscriptionEntity> existing = existingFinder.apply(definitionId);
    if (existing.isEmpty()) {
      return;
    }

    AscriptionEntity first = existing.getLast();
    JsonNode firstStmt = first.getStatement();
    if (!firstStmt.has(propName)) {
      return;
    }

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
