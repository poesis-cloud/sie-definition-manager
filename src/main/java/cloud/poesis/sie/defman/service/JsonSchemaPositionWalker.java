package cloud.poesis.sie.defman.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Component;

/**
 * Recursively visits every Draft 2020-12 schema-valued position within a JSON Schema, reporting
 * each location as a JSON Pointer to the given visitor.
 *
 * <p>Package-private traversal utility shared by {@link ArchetypeService}, {@link
 * ArchetypeIdentityValidationService}, {@link ArchetypeAnnotationValidationService}, and {@link
 * AscriptionParsingValidationService} — a structural algorithm, not a domain service, hence the
 * {@code Walker} name.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Component
class JsonSchemaPositionWalker {

  private static final List<String> DIRECT_SCHEMA_KEYWORDS =
      List.of(
          "additionalProperties",
          "additionalItems",
          "contains",
          "contentSchema",
          "else",
          "if",
          "items",
          "not",
          "propertyNames",
          "then",
          "unevaluatedItems",
          "unevaluatedProperties");

  private static final List<String> ARRAY_OF_SCHEMA_KEYWORDS =
      List.of("allOf", "anyOf", "oneOf", "prefixItems");

  private static final List<String> MAP_OF_SCHEMA_KEYWORDS =
      List.of("$defs", "definitions", "dependentSchemas", "patternProperties", "properties");

  void walk(JsonNode schema, BiConsumer<JsonNode, String> visitor) {
    walk(schema, "", visitor);
  }

  private void walk(JsonNode schema, String pointer, BiConsumer<JsonNode, String> visitor) {
    if (schema == null || (!schema.isObject() && !schema.isBoolean())) {
      return;
    }

    visitor.accept(schema, pointer);
    if (schema.isBoolean()) {
      return;
    }

    for (String keyword : DIRECT_SCHEMA_KEYWORDS) {
      walk(schema.get(keyword), pointer + "/" + keyword, visitor);
    }

    for (String keyword : ARRAY_OF_SCHEMA_KEYWORDS) {
      JsonNode schemas = schema.get(keyword);
      if (schemas == null || !schemas.isArray()) {
        continue;
      }
      for (int index = 0; index < schemas.size(); index++) {
        walk(schemas.get(index), pointer + "/" + keyword + "/" + index, visitor);
      }
    }

    for (String keyword : MAP_OF_SCHEMA_KEYWORDS) {
      JsonNode schemas = schema.get(keyword);
      if (schemas == null || !schemas.isObject()) {
        continue;
      }
      for (Map.Entry<String, JsonNode> entry : schemas.properties()) {
        walk(
            entry.getValue(),
            pointer + "/" + keyword + "/" + escapePointerToken(entry.getKey()),
            visitor);
      }
    }
  }

  private static String escapePointerToken(String token) {
    return token.replace("~", "~0").replace("/", "~1");
  }
}
