package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.exception.InternalException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.exception.UnsupportedProtectionMeasureException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
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

  /**
   * Author-facing digest names (the {@code hash.algorithm} enum) mapped to the HMAC construction
   * that actually computes them.
   */
  private static final Map<String, String> MAC_ALGORITHMS =
      Map.of(
          "SHA-256", "HmacSHA256",
          "SHA-512", "HmacSHA512",
          "SHA3-256", "HmacSHA3-256");

  /**
   * Constant hashed under the configured key to derive the key id that prefixes every stored hash.
   * Truncated HMAC output, so it identifies the key without disclosing it.
   */
  private static final byte[] KEY_ID_LABEL =
      "$gsm:dataProtection/keyId".getBytes(StandardCharsets.UTF_8);

  private static final int KEY_ID_LENGTH = 8;

  private final ArchetypeSchemaResolverService resolvedSchema;
  private final byte[] hashKey;

  public AscriptionProtectionService(
      ArchetypeSchemaResolverService resolvedSchema,
      @Value("${dm.protection.hash-key:}") String hashKey) {
    this.resolvedSchema = resolvedSchema;
    this.hashKey = hashKey.getBytes(StandardCharsets.UTF_8);
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

  /**
   * Transforms a statement query filter operand into the form actually stored, so that an equality
   * filter on a property protected at rest still matches (GSM §11, {@code GSM-PROC-49}).
   *
   * @param dpNode the resolved {@code $gsm:dataProtection} node for the property, or {@code null}
   * @param propName the property being filtered on
   * @param operand the caller-supplied filter value
   * @return the operand in stored form, unchanged when the property has no at-rest measure
   * @throws IllegalArgumentException if the property is suppressed at rest and so unmatchable
   */
  public String protectFilterOperand(JsonNode dpNode, String propName, String operand) {
    if (dpNode == null || !dpNode.has("atRest")) {
      return operand;
    }
    if (dpNode.get("atRest").has("suppression")) {
      throw new IllegalArgumentException(
          "Property '"
              + propName
              + "' is suppressed at rest by $gsm:dataProtection and cannot be filtered on.");
    }
    // Route the operand through the write path itself, so the two can never diverge.
    ObjectNode probe = JsonNodeFactory.instance.objectNode().put(propName, operand);
    applyAtRestProtection(dpNode, propName, probe);
    return probe.path(propName).asText(operand);
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
   * Computes a hex-encoded keyed hash (HMAC) of the given value.
   *
   * <p>The key is a service-held secret, not a per-value salt: the transform has to stay
   * deterministic for {@code $gsm:queryable} properties to remain equality-searchable. What the key
   * buys is that an attacker holding the stored digests cannot brute-force a low-entropy value (an
   * email, a phone number) without also holding the key.
   *
   * <p>The result is prefixed with a key id — a truncated HMAC of a constant under the same key —
   * because the transform is one-way and therefore cannot be re-keyed: changing the key silently
   * makes every previously stored value unmatchable. The prefix makes that visible, and lets values
   * written under a superseded key be identified.
   *
   * @param value the plaintext value to hash
   * @param algorithm the digest name declared by {@code hash.algorithm} (e.g. {@code "SHA-256"})
   * @return {@code <keyId>:<hex>} keyed hash string
   * @throws RuleViolationException if the algorithm is not supported
   * @throws InternalException if no hash key is configured
   */
  String computeHash(String value, String algorithm) {
    String macAlgorithm = MAC_ALGORITHMS.get(algorithm);
    if (macAlgorithm == null) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          "$gsm:dataProtection hash algorithm '" + algorithm + "' is not supported",
          "keyword",
          "$gsm:dataProtection",
          "property",
          "hash.algorithm");
    }
    if (hashKey.length == 0) {
      throw new InternalException(
          "$gsm:dataProtection hash requires 'dm.protection.hash-key' to be configured");
    }
    try {
      Mac mac = Mac.getInstance(macAlgorithm);
      mac.init(new SecretKeySpec(hashKey, macAlgorithm));
      // doFinal resets the Mac to its post-init state, so both digests use the same key.
      String keyId =
          HexFormat.of().formatHex(mac.doFinal(KEY_ID_LABEL)).substring(0, KEY_ID_LENGTH);
      return keyId
          + ":"
          + HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new InternalException(
          "$gsm:dataProtection hash could not be computed with '" + macAlgorithm + "'", e);
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
