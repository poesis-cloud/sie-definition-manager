package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Validates {@code $gsm:*} annotation vocabulary and {@code $ref} URI policy on Archetype JSON
 * Schemas.
 *
 * <p>This service is stateless — it receives the pre-fetched existing ascriptions when
 * identity-bound immutability checks are needed.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class ArchetypeAnnotationValidationService {

  private final JsonSchemaPositionWalker schemaPositionWalker;

  ArchetypeAnnotationValidationService(JsonSchemaPositionWalker schemaPositionWalker) {
    this.schemaPositionWalker = schemaPositionWalker;
  }

  // ========================================================================
  // $gsm:* annotation constants
  // ========================================================================

  private static final Set<String> KNOWN_ANNOTATIONS =
      Set.of(
          "$gsm:sealed",
          "$gsm:identityBound",
          "$gsm:queryable",
          "$gsm:unique",
          "$gsm:aliases",
          "$gsm:dataProtection");

  private static final Set<String> TOP_LEVEL_ANNOTATIONS = Set.of("$gsm:sealed");

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

      if (ArchetypeParsingService.hasAnnotation(propSchema, "$gsm:identityBound")) {
        identityBoundFields.add(propName);
      }
    }

    validateAliasUnambiguity(properties);
    validateIdentityBoundSetImmutability(existingAscriptions, identityBoundFields);
  }

  // ========================================================================
  // $gsm:aliases unambiguity
  // ========================================================================

  /**
   * Enforces alias-to-canonical resolution unambiguity within the declaring schema: a {@code
   * $gsm:aliases} entry must not equal any canonical property name, nor any alias declared on
   * another property.
   */
  private void validateAliasUnambiguity(JsonNode properties) {
    Set<String> canonicalNames = new HashSet<>();
    properties.fieldNames().forEachRemaining(canonicalNames::add);

    Map<String, String> seenAliases = new HashMap<>();
    for (Map.Entry<String, JsonNode> entry : properties.properties()) {
      String propName = entry.getKey();
      JsonNode aliases = entry.getValue().path("$gsm:aliases");
      if (!aliases.isArray()) {
        continue;
      }
      for (JsonNode aliasNode : aliases) {
        String alias = aliasNode.asText();
        if (canonicalNames.contains(alias)) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.ARCHETYPE_ALIAS_UNAMBIGUITY,
              "Alias '"
                  + alias
                  + "' on property '"
                  + propName
                  + "' collides with the canonical property '"
                  + alias
                  + "'",
              "alias",
              alias,
              "property",
              propName);
        }
        String previousOwner = seenAliases.putIfAbsent(alias, propName);
        if (previousOwner != null) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.ARCHETYPE_ALIAS_UNAMBIGUITY,
              "Alias '"
                  + alias
                  + "' on property '"
                  + propName
                  + "' is already declared on property '"
                  + previousOwner
                  + "'",
              "alias",
              alias,
              "property",
              propName,
              "conflictingProperty",
              previousOwner);
        }
      }
    }
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
      if (ArchetypeParsingService.hasAnnotation(entry.getValue(), "$gsm:identityBound")) {
        result.add(entry.getKey());
      }
    }
    return result;
  }

  /**
   * Visits Draft 2020-12 schema-valued positions and enforces the authored reference policy. Local
   * URI fragments ({@code #...}) and normative {@code gsmarc://} identities are allowed as {@code
   * $ref} values; authored {@code $dynamicRef} and every other external URI are rejected.
   *
   * @param schema the archetype JSON Schema to scan
   * @throws RuleViolationException if any {@code $ref} violates the URI policy
   */
  void validateRefUriPolicy(JsonNode schema) {
    schemaPositionWalker.walk(schema, this::validateSchemaNodeReferences);
  }

  private void validateSchemaNodeReferences(JsonNode schema, String pointer) {
    if (!schema.isObject()) {
      return;
    }

    if (schema.has("$dynamicRef")) {
      String dynamicRefPointer = pointer + "/$dynamicRef";
      JsonNode dynamicRef = schema.get("$dynamicRef");
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_REF_NORM,
          "Authored $dynamicRef is prohibited at " + dynamicRefPointer,
          "path",
          dynamicRefPointer,
          "ref",
          dynamicRef.isTextual() ? dynamicRef.asText() : null);
    }

    if (!schema.has("$ref")) {
      return;
    }

    JsonNode refNode = schema.get("$ref");
    String refPointer = pointer + "/$ref";
    if (!refNode.isTextual()) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_REF_NORM,
          "Authored $ref must be textual at " + refPointer,
          "path",
          refPointer);
    }

    String ref = refNode.asText();
    if (!ArchetypeParsingService.isAllowedRef(ref)) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_REF_NORM,
          "Prohibited $ref URI at "
              + refPointer
              + ": '"
              + ref
              + "'. Only local URI fragments (#...) and normative gsmarc:// identities are allowed",
          "path",
          refPointer,
          "ref",
          ref);
    }
  }
}
