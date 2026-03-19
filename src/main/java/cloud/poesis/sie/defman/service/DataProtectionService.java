package cloud.poesis.sie.defman.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.poesis.sie.defman.exception.GsmRuleViolationException;
import cloud.poesis.sie.defman.type.GsmRuleType;

/**
 * GSM §8 {@code $gsm:dataProtection} — applies data protection measures
 * (hash, mask, suppression) at two lifecycle phases:
 * <ul>
 * <li><b>atRest</b>: write-time transformation before persistence
 * (called from {@link AbstractAscriptionService#enforceGsmAnnotations})</li>
 * <li><b>inTransit</b>: read-time transformation before API responses
 * (called from controllers)</li>
 * </ul>
 */
@Service
public class DataProtectionService {

    // ======================================================================
    // atRest — write-time protection (Ascription authoring)
    // ======================================================================

    /**
     * Applies {@code $gsm:dataProtection.atRest} measures to a single
     * statement property. Mutates the statement in place.
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
            // Encryption not yet implemented — silently skip
            return;
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
     * Applies {@code $gsm:dataProtection.inTransit} measures to the
     * statement, returning a (possibly deep-copied) result safe for API
     * responses. The original statement is never mutated.
     */
    public JsonNode applyInTransitProtection(JsonNode statement, JsonNode archetypeSchema) {
        if (archetypeSchema == null) {
            return statement;
        }
        JsonNode properties = archetypeSchema.get("properties");
        if (properties == null || !properties.isObject()) {
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
                // Encryption not yet implemented — silently skip
                continue;
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
            throw GsmRuleViolationException.of(
                    GsmRuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                    "$gsm:dataProtection hash algorithm '" + algorithm + "' is not supported",
                    "keyword", "$gsm:dataProtection", "property", "hash.algorithm");
        }
    }

    String applyMask(String value, JsonNode maskNode) {
        String direction = maskNode.get("from").asText();
        JsonNode withNode = maskNode.get("with");
        char maskChar = withNode.has("character") ? withNode.get("character").asText().charAt(0) : '*';
        int occurrence = withNode.get("occurrence").asInt();

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
