package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

/**
 * Validates the Archetype URI {@code $id} grammar, {@code $id}/{@code title} coherence, and
 * root-only {@code $id} placement on Archetype JSON Schemas.
 *
 * <p>This service is stateless.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
class ArchetypeIdentityValidationService {

  private final JsonSchemaPositionWalker schemaPositionWalker;

  ArchetypeIdentityValidationService(JsonSchemaPositionWalker schemaPositionWalker) {
    this.schemaPositionWalker = schemaPositionWalker;
  }

  void validate(JsonNode schema) {
    JsonNode idNode = schema.get("$id");
    if (idNode == null || !idNode.isTextual()) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_ID_GRAMMAR,
          "Archetype statement must declare a textual root $id",
          "field",
          "$id");
    }

    ArchetypeParsingService.ArchetypeIdentity identity;
    try {
      identity = ArchetypeParsingService.parseIdentity(idNode.asText());
    } catch (IllegalArgumentException exception) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_ID_GRAMMAR,
          "Archetype statement $id does not match the normative identity grammar: "
              + idNode.asText(),
          exception,
          "field",
          "$id",
          "$id",
          idNode.asText());
    }

    JsonNode titleNode = schema.get("title");
    if (titleNode == null || !titleNode.isTextual()) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_ID_TITLE_COHERENCE,
          "Archetype statement must declare a textual title",
          "field",
          "title",
          "$id",
          idNode.asText());
    }

    String title = titleNode.asText();
    if (!identity.title().equals(title)) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_ID_TITLE_COHERENCE,
          "Archetype $id title segment '"
              + identity.title()
              + "' does not equal statement title '"
              + title
              + "'",
          "$id",
          idNode.asText(),
          "title",
          title);
    }

    schemaPositionWalker.walk(schema, this::rejectNestedIdentity);
  }

  private void rejectNestedIdentity(JsonNode schema, String pointer) {
    if (pointer.isEmpty() || !schema.isObject() || !schema.has("$id")) {
      return;
    }

    String idPointer = pointer + "/$id";
    throw RuleViolationException.of(
        AscriptionConsistencyRuleType.ARCHETYPE_ID_ROOT_EXCLUSIVITY,
        "Archetype $id is allowed only at the statement root; found nested $id at " + idPointer,
        "path",
        idPointer);
  }
}
