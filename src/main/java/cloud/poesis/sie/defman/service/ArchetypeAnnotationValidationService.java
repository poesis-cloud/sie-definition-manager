package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Validates {@code $gsm:*} annotation vocabulary and {@code $ref} URI policy on Archetype JSON
 * Schemas.
 *
 * <p>Extracted from {@link ArchetypeService} to separate annotation validation from schema
 * composition and lifecycle logic. This service is stateless — it receives the pre-fetched existing
 * ascriptions when identity-bound immutability checks are needed.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class ArchetypeAnnotationValidationService {

  // ========================================================================
  // $gsm:* annotation constants
  // ========================================================================

  private static final Set<String> KNOWN_ANNOTATIONS =
      Set.of(
          "$gsm:sealed",
          "$gsm:identityBound",
          "$gsm:queryable",
          "$gsm:unique",
          "$gsm:dataProtection");

  private static final Set<String> TOP_LEVEL_ANNOTATIONS = Set.of("$gsm:sealed");

  // ========================================================================
  // $ref URI policy constants
  // ========================================================================

  private static final Pattern GSM_URI_PATTERN =
      Pattern.compile("^gsm://archetypes/([^/]+)/v\\d+$");

  // ========================================================================
  // Annotation validation
  // ========================================================================

  /**
   * Validates {@code $gsm:*} annotations on the given archetype schema. Checks annotation
   * vocabulary compliance, top-level placement rules, and identity-bound set immutability against
   * existing ascriptions for the same definition.
   *
   * @param schema the archetype JSON Schema to validate
   * @param existingAscriptions existing ascriptions for the definition (ordered by timestamp desc),
   *     used for identity-bound immutability check; may be empty
   */
  void validateArchetypeAnnotations(JsonNode schema, List<ArchetypeEntity> existingAscriptions) {
    validateTopLevelAnnotations(schema);

    JsonNode properties = schema.get("properties");
    if (properties == null || !properties.isObject()) {
      return;
    }

    Set<String> identityBoundFields = new HashSet<>();

    for (Map.Entry<String, JsonNode> entry : properties.properties()) {
      String propName = entry.getKey();
      JsonNode propSchema = entry.getValue();

      checkUnknownAnnotations(propSchema, propName);

      if (hasAnnotation(propSchema, "$gsm:identityBound")) {
        identityBoundFields.add(propName);
      }
    }

    validateIdentityBoundSetImmutability(existingAscriptions, identityBoundFields);
  }

  private void validateTopLevelAnnotations(JsonNode schema) {
    Iterator<String> fieldNames = schema.fieldNames();
    while (fieldNames.hasNext()) {
      String name = fieldNames.next();
      if (name.startsWith("$gsm:") && !KNOWN_ANNOTATIONS.contains(name)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
            "Unknown $gsm:* annotation '" + name + "' — sealed annotation vocabulary",
            "annotation",
            name);
      }
    }
  }

  private void checkUnknownAnnotations(JsonNode propSchema, String propName) {
    Iterator<String> fieldNames = propSchema.fieldNames();
    while (fieldNames.hasNext()) {
      String name = fieldNames.next();
      if (name.startsWith("$gsm:")) {
        if (!KNOWN_ANNOTATIONS.contains(name)) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
              "Unknown $gsm:* annotation '"
                  + name
                  + "' on property '"
                  + propName
                  + "' — sealed annotation vocabulary",
              "annotation",
              name,
              "property",
              propName);
        }
        if (TOP_LEVEL_ANNOTATIONS.contains(name)) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
              "Annotation '"
                  + name
                  + "' is top-level only, not valid on property '"
                  + propName
                  + "'",
              "annotation",
              name,
              "property",
              propName);
        }
      }
    }
  }

  // ========================================================================
  // $gsm:identityBound set immutability
  // ========================================================================

  private void validateIdentityBoundSetImmutability(
      List<ArchetypeEntity> existingAscriptions, Set<String> currentSet) {
    if (existingAscriptions == null || existingAscriptions.isEmpty() || currentSet.isEmpty()) {
      return;
    }

    ArchetypeEntity first = existingAscriptions.getLast();
    JsonNode firstStmt = first.getStatement();
    if (firstStmt == null) {
      return;
    }

    Set<String> firstIdentityBound = collectIdentityBoundFields(firstStmt);
    if (!firstIdentityBound.equals(currentSet)) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_IDENTITY_BOUND_PROPERTY_IMMUTABILITY,
          "$gsm:identityBound set immutability violation: first Ascription had identity-bound fields "
              + firstIdentityBound
              + " but new Ascription declares "
              + currentSet
              + ". Changing the identity-bound set requires a new Archetype Definition.",
          "annotation",
          "$gsm:identityBound",
          "expectedFields",
          firstIdentityBound,
          "actualFields",
          currentSet);
    }
  }

  static Set<String> collectIdentityBoundFields(JsonNode schema) {
    Set<String> result = new HashSet<>();
    JsonNode properties = schema.get("properties");
    if (properties == null || !properties.isObject()) {
      return result;
    }
    for (Map.Entry<String, JsonNode> entry : properties.properties()) {
      if (hasAnnotation(entry.getValue(), "$gsm:identityBound")) {
        result.add(entry.getKey());
      }
    }
    return result;
  }

  // ========================================================================
  // Deep $ref URI policy scan (R2/R3 from E1 gap register)
  // ========================================================================

  /**
   * Recursively scans the entire schema tree for {@code $ref} values and enforces the URI policy:
   * only local JSON Pointers ({@code #/...}) and {@code gsm://archetypes/{title}/v{version}} URIs
   * are allowed. Rejects external URIs (http, https, file, etc.) to prevent SSRF and ensure all
   * schema resolution is local.
   *
   * @param schema the archetype JSON Schema to scan
   * @throws RuleViolationException if any {@code $ref} violates the URI policy
   */
  void validateRefUriPolicy(JsonNode schema) {
    scanRefsRecursively(schema, "$");
  }

  private void scanRefsRecursively(JsonNode node, String path) {
    if (node == null) {
      return;
    }

    if (node.isObject()) {
      if (node.has("$ref")) {
        String ref = node.get("$ref").asText();
        if (!isAllowedRef(ref)) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.ARCHETYPE_REF_NORM,
              "Prohibited $ref URI at "
                  + path
                  + ": '"
                  + ref
                  + "'. "
                  + "Only local JSON Pointers (#/...) and gsm://archetypes/{title}/v{version} "
                  + "URIs are allowed",
              "path",
              path,
              "ref",
              ref);
        }
      }
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        scanRefsRecursively(field.getValue(), path + "." + field.getKey());
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        scanRefsRecursively(node.get(i), path + "[" + i + "]");
      }
    }
  }

  private static boolean isAllowedRef(String ref) {
    return ref.startsWith("#") || GSM_URI_PATTERN.matcher(ref).matches();
  }

  // ========================================================================
  // Utilities
  // ========================================================================

  private static boolean hasAnnotation(JsonNode node, String annotation) {
    return node.has(annotation) && node.get(annotation).asBoolean(false);
  }
}
