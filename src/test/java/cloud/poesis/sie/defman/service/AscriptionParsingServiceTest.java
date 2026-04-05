package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AscriptionParsingServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final AscriptionConsistencyRuleType RULE =
      AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;

  // ========================================================================
  // extractRequiredUuid
  // ========================================================================

  @Nested
  class ExtractRequiredUuidTests {

    @Test
    void valid_returnsUuid() {
      UUID expected = UUID.randomUUID();
      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("field", expected.toString());

      UUID result = AscriptionParsingService.extractRequiredUuid(statement, "field", RULE);

      assertEquals(expected, result);
    }

    @Test
    void missingField_throws() {
      ObjectNode statement = MAPPER.createObjectNode();

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> AscriptionParsingService.extractRequiredUuid(statement, "field", RULE));
      assertTrue(ex.getMessage().contains("Required field"));
    }

    @Test
    void nullField_throws() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.putNull("field");

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> AscriptionParsingService.extractRequiredUuid(statement, "field", RULE));
      assertTrue(ex.getMessage().contains("Required field"));
    }

    @Test
    void blankField_throws() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("field", "   ");

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> AscriptionParsingService.extractRequiredUuid(statement, "field", RULE));
      assertTrue(ex.getMessage().contains("Required field"));
    }

    @Test
    void invalidUuid_throws() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("field", "not-a-uuid");

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> AscriptionParsingService.extractRequiredUuid(statement, "field", RULE));
      assertTrue(ex.getMessage().contains("Invalid UUID"));
    }
  }

  // ========================================================================
  // extractUuidList
  // ========================================================================

  @Nested
  class ExtractUuidListTests {

    @Test
    void validArray_returnsUuids() {
      UUID id1 = UUID.randomUUID();
      UUID id2 = UUID.randomUUID();
      ObjectNode statement = MAPPER.createObjectNode();
      statement.putArray("ids").add(id1.toString()).add(id2.toString());

      List<UUID> result = AscriptionParsingService.extractUuidList(statement, "ids", RULE);

      assertEquals(2, result.size());
      assertEquals(id1, result.get(0));
      assertEquals(id2, result.get(1));
    }

    @Test
    void missingField_returnsEmpty() {
      ObjectNode statement = MAPPER.createObjectNode();

      List<UUID> result = AscriptionParsingService.extractUuidList(statement, "ids", RULE);

      assertTrue(result.isEmpty());
    }

    @Test
    void nullField_returnsEmpty() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.putNull("ids");

      List<UUID> result = AscriptionParsingService.extractUuidList(statement, "ids", RULE);

      assertTrue(result.isEmpty());
    }

    @Test
    void notArray_returnsEmpty() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("ids", "not-an-array");

      List<UUID> result = AscriptionParsingService.extractUuidList(statement, "ids", RULE);

      assertTrue(result.isEmpty());
    }

    @Test
    void invalidElement_throws() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.putArray("ids").add("not-a-uuid");

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> AscriptionParsingService.extractUuidList(statement, "ids", RULE));
      assertTrue(ex.getMessage().contains("Invalid UUID"));
    }
  }
}
