package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonSchemaPositionWalkerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final JsonSchemaPositionWalker walker = new JsonSchemaPositionWalker();

  @ParameterizedTest
  @ValueSource(
      strings = {
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
        "unevaluatedProperties"
      })
  void directSchemaKeyword_isVisited(String keyword) {
    ObjectNode root = MAPPER.createObjectNode();
    root.putObject(keyword).put("type", "string");

    assertEquals(List.of("", "/" + keyword), visitedPointers(root));
  }

  @ParameterizedTest
  @ValueSource(strings = {"allOf", "anyOf", "oneOf", "prefixItems"})
  void arrayOfSchemaKeyword_isVisited(String keyword) {
    ObjectNode root = MAPPER.createObjectNode();
    root.putArray(keyword).addObject().put("type", "string");

    assertEquals(List.of("", "/" + keyword + "/0"), visitedPointers(root));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"$defs", "definitions", "dependentSchemas", "patternProperties", "properties"})
  void mapOfSchemaKeyword_isVisitedWithEscapedPointer(String keyword) {
    ObjectNode root = MAPPER.createObjectNode();
    root.putObject(keyword).putObject("a/b~c").put("type", "string");

    assertEquals(List.of("", "/" + keyword + "/a~1b~0c"), visitedPointers(root));
  }

  @ParameterizedTest
  @ValueSource(strings = {"const", "default", "enum", "examples", "$gsm:extension"})
  void dataValuedKeyword_isNotTraversed(String keyword) {
    ObjectNode root = MAPPER.createObjectNode();
    root.putObject(keyword).putObject("properties").putObject("nested").put("$id", "data");

    assertEquals(List.of(""), visitedPointers(root));
  }

  @Test
  void booleanSchemas_areVisitedAndTerminal() {
    ObjectNode root = MAPPER.createObjectNode();
    ObjectNode properties = root.putObject("properties");
    properties.put("enabled", true);
    properties.put("disabled", false);

    assertEquals(List.of("", "/properties/enabled", "/properties/disabled"), visitedPointers(root));
  }

  @Test
  void wrongShapedKeywords_areIgnored() {
    ObjectNode root = MAPPER.createObjectNode();
    root.putArray("properties").addObject().put("$id", "data");
    root.putObject("allOf").put("$id", "data");
    root.put("not", "data");
    root.putArray("items").addObject().put("$id", "draft-07-data");

    assertEquals(List.of(""), visitedPointers(root));
  }

  @Test
  void keywordGroups_areVisitedInDeterministicOrder() {
    ObjectNode root = MAPPER.createObjectNode();
    root.putObject("properties").putObject("value");
    root.putArray("allOf").addObject();
    root.putObject("if");

    assertEquals(List.of("", "/if", "/allOf/0", "/properties/value"), visitedPointers(root));
  }

  private List<String> visitedPointers(JsonNode schema) {
    List<String> pointers = new ArrayList<>();
    walker.walk(schema, (node, pointer) -> pointers.add(pointer));
    return pointers;
  }
}
