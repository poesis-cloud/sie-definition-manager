package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.exception.UnsupportedProtectionMeasureException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import org.springframework.stereotype.Service;

/**
 * GSM §8 {@code $gsm:dataProtection} — applies data protection measures (hash, mask, suppression)
 * at two lifecycle phases:
 *
 * <ul>
 *   <li><b>atRest</b>: write-time transformation before persistence (called from {@link
 *       AscriptionService#create})
 *   <li><b>inTransit</b>: read-time transformation before API responses (called from controllers)
 * </ul>
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class AscriptionProtectionService {

  private final ArchetypeSchemaResolverService resolvedSchema;

  public AscriptionProtectionService(ArchetypeSchemaResolverService resolvedSchema) {
    this.resolvedSchema = resolvedSchema;
  }

  // ======================================================================
  // atRest — write-time protection (Ascription authoring)
  // ======================================================================

  /**
   * Applies {@code $gsm:dataProtection.atRest} measures to a single statement property. Mutates the
   * statement in place.
   *
   * @param dpNode the {@code $gsm:dataProtection} JSON node from the archetype schema
   * @param propName the property name being protected
   * @param statement the statement object node (mutated in place)
   */
  public void applyAtRestProtection(JsonNode dpNode, String propName, ObjectNode statement) {
    if (dpNode == null || !dpNode.has("atRest")) {
      return;
    }

    JsonNode atRest = dpNode.get("atRest");
    JsonNode value = statement.get(propName);
    if (value == null || value.isNull()) {
      return;
    }
    String textValue = value.isTextual() ? value.asText() : value.toString();

    if (atRest.has("encryption")) {
      throw unsupportedMeasure("atRest", "encryption", propName);
    }

    if (atRest.has("hash")) {
      String algorithm = "SHA-256";
      if (atRest.get("hash").has("algorithm")) {
        algorithm = atRest.get("hash").get("algorithm").asText();
      }
      statement.put(propName, computeHash(textValue, algorithm));
    }

    if (atRest.has("mask")) {
      statement.put(propName, applyMask(textValue, atRest.get("mask")));
    }

    if (atRest.has("suppression")) {
      statement.remove(propName);
    }
  }

  // ======================================================================
  // inTransit — read-time protection (API responses)
  // ======================================================================

  /**
   * Applies {@code $gsm:dataProtection.inTransit} measures to the statement, returning a (possibly
   * deep-copied) result safe for API responses. The original statement is never mutated.
   *
   * <p>Annotations are resolved over the archetype's resolved composition chain (GSM §11.1), so a
   * property whose protection is declared by an ancestor archetype is protected here.
   *
   * @param statement the ascription statement payload
   * @param archetype the typing archetype whose resolved composition chain carries {@code
   *     $gsm:dataProtection} annotations
   * @return the transformed statement (deep-copied only when transformation is needed)
   */
  public JsonNode applyInTransitProtection(JsonNode statement, ArchetypeEntity archetype) {
    JsonNode properties = resolvedSchema.resolvedProperties(archetype);
    if (properties.isEmpty()) {
      return statement;
    }

    // Check whether any inTransit protection is needed
    boolean needsCopy = false;
    Iterator<String> fieldNames = properties.fieldNames();
    while (fieldNames.hasNext()) {
      String fieldName = fieldNames.next();
      JsonNode propSchema = properties.get(fieldName);
      if (propSchema.has("$gsm:dataProtection") && statement.has(fieldName)) {
        JsonNode dp = propSchema.get("$gsm:dataProtection");
        if (dp.has("inTransit")) {
          needsCopy = true;
          break;
        }
      }
    }

    if (!needsCopy) {
      return statement;
    }

    // Deep-copy only when transformation is needed
    ObjectNode result = statement.deepCopy();
    fieldNames = properties.fieldNames();
    while (fieldNames.hasNext()) {
      String fieldName = fieldNames.next();
      JsonNode propSchema = properties.get(fieldName);
      if (!propSchema.has("$gsm:dataProtection") || !result.has(fieldName)) {
        continue;
      }
      JsonNode dp = propSchema.get("$gsm:dataProtection");
      if (!dp.has("inTransit")) {
        continue;
      }

      JsonNode inTransit = dp.get("inTransit");
      JsonNode value = result.get(fieldName);
      String textValue = value.isTextual() ? value.asText() : value.toString();

      if (inTransit.has("encryption")) {
        throw unsupportedMeasure("inTransit", "encryption", fieldName);
      }

      if (inTransit.has("hash")) {
        String algorithm = "SHA-256";
        if (inTransit.get("hash").has("algorithm")) {
          algorithm = inTransit.get("hash").get("algorithm").asText();
        }
        result.put(fieldName, computeHash(textValue, algorithm));
      }

      if (inTransit.has("mask")) {
        result.put(fieldName, applyMask(textValue, inTransit.get("mask")));
      }

      if (inTransit.has("suppression")) {
        result.remove(fieldName);
      }
    }
    return result;
  }

  // ======================================================================
  // Shared primitives
  // ======================================================================

  /**
   * Guards the write and read paths against a declared measure this processor cannot apply.
   * Archetype authoring rejects these already; this is the last line before an unprotected value
   * would be persisted or returned.
   */
  private static UnsupportedProtectionMeasureException unsupportedMeasure(
      String phase, String measure, String propName) {
    return new UnsupportedProtectionMeasureException(phase, measure, propName);
  }

  /**
   * Computes a hex-encoded hash of the given value.
   *
   * @param value the plaintext value to hash
   * @param algorithm the hash algorithm name (e.g. {@code "SHA-256"})
   * @return hex-encoded hash string
   * @throws RuleViolationException if the algorithm is not supported
   */
  String computeHash(String value, String algorithm) {
    try {
      MessageDigest digest = MessageDigest.getInstance(algorithm);
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          "$gsm:dataProtection hash algorithm '" + algorithm + "' is not supported",
          "keyword",
          "$gsm:dataProtection",
          "property",
          "hash.algorithm");
    }
  }

  /**
   * Applies a masking transformation to the given value.
   *
   * @param value the plaintext value to mask
   * @param maskNode the mask configuration node ({@code from}, {@code with})
   * @return the masked string
   */
  String applyMask(String value, JsonNode maskNode) {
    JsonNode fromNode = maskNode.get("from");
    if (fromNode == null) {
      return value;
    }
    String direction = fromNode.asText();
    JsonNode withNode = maskNode.get("with");
    if (withNode == null) {
      return value;
    }
    char maskChar = withNode.has("character") ? withNode.get("character").asText().charAt(0) : '*';
    JsonNode occurrenceNode = withNode.get("occurrence");
    if (occurrenceNode == null) {
      return value;
    }
    int occurrence = occurrenceNode.asInt();

    if (value.length() <= occurrence) {
      return String.valueOf(maskChar).repeat(value.length());
    }

    char[] chars = value.toCharArray();
    if ("LEFT".equals(direction)) {
      for (int i = occurrence; i < chars.length; i++) {
        chars[i] = maskChar;
      }
    } else {
      for (int i = 0; i < chars.length - occurrence; i++) {
        chars[i] = maskChar;
      }
    }
    return new String(chars);
  }
}
