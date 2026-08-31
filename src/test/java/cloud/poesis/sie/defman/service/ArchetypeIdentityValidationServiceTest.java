package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ArchetypeIdentityValidationServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ArchetypeIdentityValidationService service =
      new ArchetypeIdentityValidationService(new JsonSchemaPositionWalker());

  @Test
  void validRootIdentityAndTitle_pass() {
    assertDoesNotThrow(() -> service.validate(validSchema()));
  }

  @Test
  void missingNonTextualMalformedAndOverflowIdentity_useGrammarRuleFirst() {
    ObjectNode missing = MAPPER.createObjectNode().put("title", "ServiceContract");
    ObjectNode nonTextual = MAPPER.createObjectNode().put("title", "ServiceContract");
    nonTextual.put("$id", 1);
    ObjectNode malformed = validSchema();
    malformed.put("$id", "gsmarc://Tenant/ServiceContract/v0");
    malformed.put("title", "WrongTitle");
    ObjectNode overflow = validSchema();
    overflow.put("$id", "gsmarc://tenant/catalog/ServiceContract/v2147483648");

    RuleViolationException missingException = assertViolation(missing);
    RuleViolationException nonTextualException = assertViolation(nonTextual);
    RuleViolationException malformedException = assertViolation(malformed);
    RuleViolationException overflowException = assertViolation(overflow);

    assertEquals(
        AscriptionConsistencyRuleType.ARCHETYPE_ID_GRAMMAR, missingException.getRuleType());
    assertEquals("$id", missingException.getSite().get("field"));
    assertEquals(
        AscriptionConsistencyRuleType.ARCHETYPE_ID_GRAMMAR, nonTextualException.getRuleType());
    assertEquals(
        AscriptionConsistencyRuleType.ARCHETYPE_ID_GRAMMAR, malformedException.getRuleType());
    assertEquals(
        AscriptionConsistencyRuleType.ARCHETYPE_ID_GRAMMAR, overflowException.getRuleType());
    assertTrue(malformedException.getCause() instanceof IllegalArgumentException);
    assertTrue(overflowException.getCause() instanceof IllegalArgumentException);
  }

  @Test
  void missingOrNonTextualTitle_usesTitleCoherenceRuleWithIdentitySite() {
    ObjectNode missing = validSchema();
    missing.remove("title");
    ObjectNode nonTextual = validSchema();
    nonTextual.putObject("title");

    RuleViolationException missingException = assertViolation(missing);
    RuleViolationException nonTextualException = assertViolation(nonTextual);

    assertEquals(
        AscriptionConsistencyRuleType.ARCHETYPE_ID_TITLE_COHERENCE, missingException.getRuleType());
    assertEquals("title", missingException.getSite().get("field"));
    assertEquals(
        "gsmarc://tenant/catalog/ServiceContract/v2", missingException.getSite().get("$id"));
    assertEquals(
        AscriptionConsistencyRuleType.ARCHETYPE_ID_TITLE_COHERENCE,
        nonTextualException.getRuleType());
  }

  @Test
  void mismatchedTitle_usesTitleCoherenceRuleWithBothValues() {
    ObjectNode schema = validSchema();
    schema.put("title", "serviceContract");

    RuleViolationException exception = assertViolation(schema);

    assertEquals(
        AscriptionConsistencyRuleType.ARCHETYPE_ID_TITLE_COHERENCE, exception.getRuleType());
    assertEquals("gsmarc://tenant/catalog/ServiceContract/v2", exception.getSite().get("$id"));
    assertEquals("serviceContract", exception.getSite().get("title"));
  }

  @Test
  void nestedNonTextualIdentity_isRejectedAtEscapedPointer() {
    ObjectNode schema = validSchema();
    schema.putObject("properties").putObject("a/b~c").put("$id", 1);

    RuleViolationException exception = assertViolation(schema);

    assertEquals(
        AscriptionConsistencyRuleType.ARCHETYPE_ID_ROOT_EXCLUSIVITY, exception.getRuleType());
    assertEquals("/properties/a~1b~0c/$id", exception.getSite().get("path"));
  }

  @Test
  void identityLookalikeInsideDataValue_isIgnored() {
    ObjectNode schema = validSchema();
    schema.putArray("examples").addObject().put("$id", "application-data");

    assertDoesNotThrow(() -> service.validate(schema));
  }

  private RuleViolationException assertViolation(ObjectNode schema) {
    return assertThrows(RuleViolationException.class, () -> service.validate(schema));
  }

  private ObjectNode validSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("$id", "gsmarc://tenant/catalog/ServiceContract/v2");
    schema.put("title", "ServiceContract");
    return schema;
  }
}
