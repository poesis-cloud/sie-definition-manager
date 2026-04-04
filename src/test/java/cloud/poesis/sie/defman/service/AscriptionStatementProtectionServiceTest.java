package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AscriptionStatementProtectionService} — GSM §8 {@code $gsm:dataProtection}.
 *
 * <p>Tests cover the four data protection measures (hash, mask, suppression, encryption-noop)
 * across both lifecycle phases (atRest, inTransit), plus the two shared primitives ({@code
 * computeHash}, {@code applyMask}).
 */
class AscriptionStatementProtectionServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final AscriptionStatementProtectionService service =
      new AscriptionStatementProtectionService();

  // ==================================================================
  // applyAtRestProtection
  // ==================================================================

  @Nested
  class ApplyAtRestProtection {

    @Test
    void hashSha256_replacesWithHexDigest() {
      ObjectNode dp = dpAtRest();
      dp.withObject("atRest").putObject("hash").put("algorithm", "SHA-256");
      ObjectNode statement = MAPPER.createObjectNode().put("ssn", "123-45-6789");

      service.applyAtRestProtection(dp, "ssn", statement);

      String hashed = statement.get("ssn").asText();
      assertTrue(hashed.matches("[0-9a-f]{64}"), "Expected SHA-256 hex, got: " + hashed);
    }

    @Test
    void hashSha512_replacesWithHexDigest() {
      ObjectNode dp = dpAtRest();
      dp.withObject("atRest").putObject("hash").put("algorithm", "SHA-512");
      ObjectNode statement = MAPPER.createObjectNode().put("ssn", "123-45-6789");

      service.applyAtRestProtection(dp, "ssn", statement);

      String hashed = statement.get("ssn").asText();
      assertTrue(hashed.matches("[0-9a-f]{128}"), "Expected SHA-512 hex, got: " + hashed);
    }

    @Test
    void hashSha3_256_replacesWithHexDigest() {
      ObjectNode dp = dpAtRest();
      dp.withObject("atRest").putObject("hash").put("algorithm", "SHA3-256");
      ObjectNode statement = MAPPER.createObjectNode().put("ssn", "123-45-6789");

      service.applyAtRestProtection(dp, "ssn", statement);

      String hashed = statement.get("ssn").asText();
      assertTrue(hashed.matches("[0-9a-f]{64}"), "Expected SHA3-256 hex, got: " + hashed);
    }

    @Test
    void hashDefaultAlgorithm_usesSha256() {
      ObjectNode dp = dpAtRest();
      dp.withObject("atRest").putObject("hash"); // no "algorithm" key
      ObjectNode statement = MAPPER.createObjectNode().put("ssn", "123-45-6789");

      service.applyAtRestProtection(dp, "ssn", statement);

      // SHA-256 default → 64 hex chars
      String hashed = statement.get("ssn").asText();
      assertTrue(hashed.matches("[0-9a-f]{64}"), "Expected SHA-256 hex, got: " + hashed);
      // Verify it matches explicit SHA-256
      assertEquals(service.computeHash("123-45-6789", "SHA-256"), hashed);
    }

    @Test
    void mask_rightDirection_keepsLastVisible() {
      ObjectNode dp = dpAtRest();
      ObjectNode mask = dp.withObject("atRest").putObject("mask");
      mask.put("from", "RIGHT");
      ObjectNode with = mask.putObject("with");
      with.put("character", "*");
      with.put("occurrence", 4);
      ObjectNode statement = MAPPER.createObjectNode().put("phone", "555-1234");

      service.applyAtRestProtection(dp, "phone", statement);

      assertEquals("****1234", statement.get("phone").asText());
    }

    @Test
    void mask_leftDirection_keepsFirstVisible() {
      ObjectNode dp = dpAtRest();
      ObjectNode mask = dp.withObject("atRest").putObject("mask");
      mask.put("from", "LEFT");
      ObjectNode with = mask.putObject("with");
      with.put("character", "#");
      with.put("occurrence", 3);
      ObjectNode statement = MAPPER.createObjectNode().put("card", "4111222233334444");

      service.applyAtRestProtection(dp, "card", statement);

      assertEquals("411#############", statement.get("card").asText());
    }

    @Test
    void mask_shortValue_masksEntirely() {
      ObjectNode dp = dpAtRest();
      ObjectNode mask = dp.withObject("atRest").putObject("mask");
      mask.put("from", "RIGHT");
      ObjectNode with = mask.putObject("with");
      with.put("character", "*");
      with.put("occurrence", 4);
      ObjectNode statement = MAPPER.createObjectNode().put("pin", "12");

      service.applyAtRestProtection(dp, "pin", statement);

      assertEquals("**", statement.get("pin").asText());
    }

    @Test
    void suppression_removesField() {
      ObjectNode dp = dpAtRest();
      dp.withObject("atRest").put("suppression", true);
      ObjectNode statement = MAPPER.createObjectNode().put("secret", "top-secret");

      service.applyAtRestProtection(dp, "secret", statement);

      assertFalse(statement.has("secret"));
    }

    @Test
    void encryption_silentlyIgnored() {
      ObjectNode dp = dpAtRest();
      dp.withObject("atRest").putObject("encryption").put("algorithm", "AES-256-GCM");
      ObjectNode statement = MAPPER.createObjectNode().put("card", "4111-1111");

      service.applyAtRestProtection(dp, "card", statement);

      assertEquals("4111-1111", statement.get("card").asText());
    }

    @Test
    void nullDpNode_noop() {
      ObjectNode statement = MAPPER.createObjectNode().put("field", "value");

      assertDoesNotThrow(() -> service.applyAtRestProtection(null, "field", statement));
      assertEquals("value", statement.get("field").asText());
    }

    @Test
    void noAtRestKey_noop() {
      ObjectNode dp = MAPPER.createObjectNode();
      // dpNode exists but has no "atRest" key
      ObjectNode statement = MAPPER.createObjectNode().put("field", "value");

      service.applyAtRestProtection(dp, "field", statement);

      assertEquals("value", statement.get("field").asText());
    }

    @Test
    void propertyMissingInStatement_noop() {
      ObjectNode dp = dpAtRest();
      dp.withObject("atRest").putObject("hash").put("algorithm", "SHA-256");
      ObjectNode statement = MAPPER.createObjectNode().put("other", "data");

      assertDoesNotThrow(() -> service.applyAtRestProtection(dp, "missing", statement));
      assertFalse(statement.has("missing"));
    }

    @Test
    void nullPropertyValue_noop() {
      ObjectNode dp = dpAtRest();
      dp.withObject("atRest").putObject("hash").put("algorithm", "SHA-256");
      ObjectNode statement = MAPPER.createObjectNode();
      statement.putNull("ssn");

      assertDoesNotThrow(() -> service.applyAtRestProtection(dp, "ssn", statement));
      assertTrue(statement.get("ssn").isNull());
    }

    @Test
    void nonTextualValue_usesToString() {
      ObjectNode dp = dpAtRest();
      dp.withObject("atRest").putObject("hash").put("algorithm", "SHA-256");
      ObjectNode statement = MAPPER.createObjectNode().put("score", 42);

      service.applyAtRestProtection(dp, "score", statement);

      String hashed = statement.get("score").asText();
      assertTrue(hashed.matches("[0-9a-f]{64}"), "Expected SHA-256 hex, got: " + hashed);
      assertEquals(service.computeHash("42", "SHA-256"), hashed);
    }
  }

  // ==================================================================
  // applyInTransitProtection
  // ==================================================================

  @Nested
  class ApplyInTransitProtection {

    @Test
    void hashInTransit_replacesValueInDeepCopy() {
      ObjectNode archetypeSchema =
          schemaWithInTransit(
              "password",
              MAPPER
                  .createObjectNode()
                  .set("hash", MAPPER.createObjectNode().put("algorithm", "SHA-256")));

      ObjectNode statement = MAPPER.createObjectNode().put("password", "s3cret");

      JsonNode result = service.applyInTransitProtection(statement, archetypeSchema);

      // Result is transformed
      String hashed = result.get("password").asText();
      assertTrue(hashed.matches("[0-9a-f]{64}"), "Expected SHA-256 hex, got: " + hashed);
      // Original is not mutated
      assertEquals("s3cret", statement.get("password").asText());
    }

    @Test
    void maskInTransit_masksValueInDeepCopy() {
      ObjectNode mask = MAPPER.createObjectNode();
      mask.put("from", "RIGHT");
      ObjectNode with = mask.putObject("with");
      with.put("character", "*");
      with.put("occurrence", 4);

      ObjectNode archetypeSchema =
          schemaWithInTransit("phone", MAPPER.createObjectNode().set("mask", mask));

      ObjectNode statement = MAPPER.createObjectNode().put("phone", "555-1234");

      JsonNode result = service.applyInTransitProtection(statement, archetypeSchema);

      assertEquals("****1234", result.get("phone").asText());
      assertEquals("555-1234", statement.get("phone").asText());
    }

    @Test
    void suppressionInTransit_removesFieldInDeepCopy() {
      ObjectNode archetypeSchema =
          schemaWithInTransit("secret", MAPPER.createObjectNode().put("suppression", true));

      ObjectNode statement =
          MAPPER.createObjectNode().put("secret", "top-secret").put("env", "prod");

      JsonNode result = service.applyInTransitProtection(statement, archetypeSchema);

      assertNull(result.get("secret"));
      assertEquals("prod", result.get("env").asText());
      // Original preserved
      assertEquals("top-secret", statement.get("secret").asText());
    }

    @Test
    void encryptionInTransit_silentlyIgnored() {
      ObjectNode archetypeSchema =
          schemaWithInTransit(
              "card",
              MAPPER
                  .createObjectNode()
                  .set("encryption", MAPPER.createObjectNode().put("algorithm", "AES-256-GCM")));

      ObjectNode statement = MAPPER.createObjectNode().put("card", "4111-1111");

      JsonNode result = service.applyInTransitProtection(statement, archetypeSchema);

      assertEquals("4111-1111", result.get("card").asText());
    }

    @Test
    void noDataProtection_returnsSameReference() {
      ObjectNode archetypeSchema = MAPPER.createObjectNode();
      archetypeSchema.put("title", "TestSchema");
      ObjectNode props = archetypeSchema.putObject("properties");
      ObjectNode nameProp = props.putObject("name");
      nameProp.put("type", "string");

      ObjectNode statement = MAPPER.createObjectNode().put("name", "test");

      JsonNode result = service.applyInTransitProtection(statement, archetypeSchema);

      assertSame(statement, result, "Should return original reference when no transform needed");
    }

    @Test
    void onlyAtRestDefined_returnsSameReference() {
      ObjectNode archetypeSchema = MAPPER.createObjectNode();
      archetypeSchema.put("title", "TestSchema");
      ObjectNode props = archetypeSchema.putObject("properties");
      ObjectNode ssnProp = props.putObject("ssn");
      ssnProp.put("type", "string");
      ObjectNode dp = ssnProp.putObject("$gsm:dataProtection");
      dp.putObject("atRest").putObject("hash").put("algorithm", "SHA-256");
      // No inTransit → no transform

      ObjectNode statement = MAPPER.createObjectNode().put("ssn", "hashed-at-rest");

      JsonNode result = service.applyInTransitProtection(statement, archetypeSchema);

      assertSame(statement, result, "Should return original reference when only atRest defined");
    }

    @Test
    void nullArchetypeSchema_returnsSameReference() {
      ObjectNode statement = MAPPER.createObjectNode().put("name", "test");

      JsonNode result = service.applyInTransitProtection(statement, null);

      assertSame(statement, result);
    }

    @Test
    void noPropertiesInSchema_returnsSameReference() {
      ObjectNode archetypeSchema = MAPPER.createObjectNode();
      // No "properties" key at all
      ObjectNode statement = MAPPER.createObjectNode().put("name", "test");

      JsonNode result = service.applyInTransitProtection(statement, archetypeSchema);

      assertSame(statement, result);
    }

    @Test
    void protectedPropertyNotInStatement_noError() {
      ObjectNode archetypeSchema =
          schemaWithInTransit("password", MAPPER.createObjectNode().put("suppression", true));

      // Statement does NOT contain "password"
      ObjectNode statement = MAPPER.createObjectNode().put("name", "test");

      JsonNode result = service.applyInTransitProtection(statement, archetypeSchema);

      assertSame(statement, result, "No inTransit field present → no copy needed");
    }

    @Test
    void multipleProperties_differentProtections() {
      ObjectNode archetypeSchema = MAPPER.createObjectNode();
      archetypeSchema.put("title", "TestSchema");
      ObjectNode props = archetypeSchema.putObject("properties");

      // password → hash
      ObjectNode passwordProp = props.putObject("password");
      passwordProp.put("type", "string");
      ObjectNode dp1 = passwordProp.putObject("$gsm:dataProtection");
      dp1.putObject("inTransit").putObject("hash").put("algorithm", "SHA-256");

      // phone → mask
      ObjectNode phoneProp = props.putObject("phone");
      phoneProp.put("type", "string");
      ObjectNode dp2 = phoneProp.putObject("$gsm:dataProtection");
      ObjectNode mask = dp2.putObject("inTransit").putObject("mask");
      mask.put("from", "RIGHT");
      ObjectNode with = mask.putObject("with");
      with.put("character", "*");
      with.put("occurrence", 4);

      // secret → suppression
      ObjectNode secretProp = props.putObject("secret");
      secretProp.put("type", "string");
      ObjectNode dp3 = secretProp.putObject("$gsm:dataProtection");
      dp3.putObject("inTransit").put("suppression", true);

      // name → no protection
      ObjectNode nameProp = props.putObject("name");
      nameProp.put("type", "string");

      ObjectNode statement =
          MAPPER
              .createObjectNode()
              .put("password", "s3cret")
              .put("phone", "555-1234")
              .put("secret", "top-secret")
              .put("name", "test-service");

      JsonNode result = service.applyInTransitProtection(statement, archetypeSchema);

      assertTrue(result.get("password").asText().matches("[0-9a-f]{64}"));
      assertEquals("****1234", result.get("phone").asText());
      assertNull(result.get("secret"));
      assertEquals("test-service", result.get("name").asText());
    }

    @Test
    void originalStatement_neverMutated() {
      ObjectNode archetypeSchema =
          schemaWithInTransit("secret", MAPPER.createObjectNode().put("suppression", true));

      ObjectNode statement =
          MAPPER.createObjectNode().put("secret", "top-secret").put("env", "prod");
      String originalJson = statement.toString();

      service.applyInTransitProtection(statement, archetypeSchema);

      assertEquals(
          originalJson,
          statement.toString(),
          "Original statement must not be mutated by inTransit protection");
    }
  }

  // ==================================================================
  // computeHash
  // ==================================================================

  @Nested
  class ComputeHash {

    @Test
    void sha256_produces64HexChars() {
      String hash = service.computeHash("hello", "SHA-256");
      assertTrue(hash.matches("[0-9a-f]{64}"), "Expected 64 hex, got: " + hash);
    }

    @Test
    void sha512_produces128HexChars() {
      String hash = service.computeHash("hello", "SHA-512");
      assertTrue(hash.matches("[0-9a-f]{128}"), "Expected 128 hex, got: " + hash);
    }

    @Test
    void sha3_256_produces64HexChars() {
      String hash = service.computeHash("hello", "SHA3-256");
      assertTrue(hash.matches("[0-9a-f]{64}"), "Expected 64 hex, got: " + hash);
    }

    @Test
    void deterministic_sameInputSameOutput() {
      String hash1 = service.computeHash("test-value", "SHA-256");
      String hash2 = service.computeHash("test-value", "SHA-256");
      assertEquals(hash1, hash2);
    }

    @Test
    void differentInputs_differentOutputs() {
      String hash1 = service.computeHash("value-a", "SHA-256");
      String hash2 = service.computeHash("value-b", "SHA-256");
      assertNotEquals(hash1, hash2, "Different inputs should produce different hashes");
    }

    @Test
    void unsupportedAlgorithm_throwsRuleViolation() {
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.computeHash("hello", "BOGUS-ALG"));

      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("BOGUS-ALG"));
    }
  }

  // ==================================================================
  // applyMask
  // ==================================================================

  @Nested
  class ApplyMask {

    @Test
    void leftDirection_keepsFirstNVisible() {
      JsonNode maskNode = maskNode("LEFT", "*", 3);
      assertEquals("411*************", service.applyMask("4111222233334444", maskNode));
    }

    @Test
    void rightDirection_keepsLastNVisible() {
      JsonNode maskNode = maskNode("RIGHT", "*", 4);
      assertEquals("****1234", service.applyMask("555-1234", maskNode));
    }

    @Test
    void shortValue_masksEntirely() {
      JsonNode maskNode = maskNode("RIGHT", "*", 4);
      assertEquals("**", service.applyMask("12", maskNode));
    }

    @Test
    void exactLengthEqualsOccurrence_masksEntirely() {
      JsonNode maskNode = maskNode("LEFT", "*", 4);
      assertEquals("****", service.applyMask("abcd", maskNode));
    }

    @Test
    void customMaskCharacter() {
      JsonNode maskNode = maskNode("RIGHT", "#", 2);
      assertEquals("####ef", service.applyMask("abcdef", maskNode));
    }

    @Test
    void defaultMaskCharacter_whenOmitted() {
      // Build mask node without "character" key
      ObjectNode mask = MAPPER.createObjectNode();
      mask.put("from", "LEFT");
      ObjectNode with = mask.putObject("with");
      with.put("occurrence", 2);
      // No "character" → defaults to '*'

      assertEquals("ab****", service.applyMask("abcdef", mask));
    }

    @Test
    void singleCharacterValue_left() {
      JsonNode maskNode = maskNode("LEFT", "*", 5);
      assertEquals("*", service.applyMask("x", maskNode));
    }

    @Test
    void singleCharacterValue_right() {
      JsonNode maskNode = maskNode("RIGHT", "*", 5);
      assertEquals("*", service.applyMask("x", maskNode));
    }
  }

  // ==================================================================
  // Helpers
  // ==================================================================

  /** Creates a {@code $gsm:dataProtection} node with an empty {@code atRest} container. */
  private static ObjectNode dpAtRest() {
    ObjectNode dp = MAPPER.createObjectNode();
    dp.putObject("atRest");
    return dp;
  }

  /** Builds an archetype schema with one {@code $gsm:dataProtection.inTransit} property. */
  private static ObjectNode schemaWithInTransit(String propName, JsonNode inTransitContent) {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("title", "TestSchema");
    ObjectNode props = schema.putObject("properties");
    ObjectNode propNode = props.putObject(propName);
    propNode.put("type", "string");
    ObjectNode dp = propNode.putObject("$gsm:dataProtection");
    dp.set("inTransit", inTransitContent);
    return schema;
  }

  /** Builds a compact mask configuration node. */
  private static JsonNode maskNode(String direction, String character, int occurrence) {
    ObjectNode mask = MAPPER.createObjectNode();
    mask.put("from", direction);
    ObjectNode with = mask.putObject("with");
    with.put("character", character);
    with.put("occurrence", occurrence);
    return mask;
  }
}
