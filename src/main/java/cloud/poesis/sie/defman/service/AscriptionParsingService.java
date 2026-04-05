package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Centralised helpers for extracting typed values from ascription statement JSON payloads.
 *
 * <p>Every GSM handler that needs to parse UUID references or other typed fields from the
 * statement's {@link JsonNode} should use this class instead of doing raw {@code
 * UUID.fromString(statement.get(…).asText())} inline. This ensures consistent error translation
 * into {@link RuleViolationException} with proper rule type and context.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public final class AscriptionParsingService {

  private AscriptionParsingService() {}

  /**
   * Extracts a required UUID from a named field in a statement JSON payload.
   *
   * @param statement the statement {@link JsonNode}
   * @param field the JSON field name to extract
   * @param rule the consistency rule to report on failure
   * @return the parsed UUID
   * @throws RuleViolationException if the field is missing, null, blank, or not a valid UUID
   */
  public static UUID extractRequiredUuid(
      JsonNode statement, String field, AscriptionConsistencyRuleType rule) {
    JsonNode node = statement.get(field);
    if (node == null || node.isNull() || node.asText().isBlank()) {
      throw RuleViolationException.of(
          rule, "Required field '" + field + "' missing in statement payload", "field", field);
    }
    try {
      return UUID.fromString(node.asText());
    } catch (IllegalArgumentException e) {
      throw RuleViolationException.of(
          rule,
          "Invalid UUID for field '" + field + "': " + node.asText(),
          "field",
          field,
          "value",
          node.asText());
    }
  }

  /**
   * Extracts a list of UUIDs from a JSON array field in a statement payload.
   *
   * <p>Returns an empty list if the field is missing, null, or not an array.
   *
   * @param statement the statement {@link JsonNode}
   * @param field the JSON array field name to extract
   * @param rule the consistency rule to report on failure
   * @return list of parsed UUIDs (may be empty)
   * @throws RuleViolationException if any array element is not a valid UUID
   */
  public static List<UUID> extractUuidList(
      JsonNode statement, String field, AscriptionConsistencyRuleType rule) {
    JsonNode node = statement.get(field);
    if (node == null || node.isNull() || !node.isArray()) {
      return List.of();
    }
    List<UUID> result = new ArrayList<>(node.size());
    for (JsonNode element : node) {
      try {
        result.add(UUID.fromString(element.asText()));
      } catch (IllegalArgumentException e) {
        throw RuleViolationException.of(
            rule,
            "Invalid UUID in '" + field + "': " + element.asText(),
            "field",
            field,
            "value",
            element.asText());
      }
    }
    return result;
  }
}
