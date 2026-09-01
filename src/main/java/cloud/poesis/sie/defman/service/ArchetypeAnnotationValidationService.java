package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.exception.UnsupportedProtectionMeasureException;
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
  private final ArchetypeSchemaResolverService resolvedSchema;

  ArchetypeAnnotationValidationService(
      JsonSchemaPositionWalker schemaPositionWalker,
      ArchetypeSchemaResolverService resolvedSchema) {
    this.schemaPositionWalker = schemaPositionWalker;
    this.resolvedSchema = resolvedSchema;
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

    JsonNode ownProperties = schema.get("properties");
    if (ownProperties != null && ownProperties.isObject()) {
      for (Map.Entry<String, JsonNode> entry : ownProperties.properties()) {
        checkUnknownAnnotations(entry.getValue(), entry.getKey());
      }
    }

    // GSM §11.1: annotation-driven rules resolve over the inherited surface.
    JsonNode resolvedProperties = resolvedSchema.resolvedProperties(schema);
    if (resolvedProperties.isEmpty()) {
      return;
    }

    validateAliasUnambiguity(resolvedProperties);
    validateDataProtection(resolvedProperties);
    validateIdentityBoundSetImmutability(
        existingAscriptions, collectIdentityBoundFields(resolvedProperties));
  }

  // ========================================================================
  // $gsm:dataProtection — GSM-PROC-14 exclusivity, GSM-PROC-48 measure support
  // ========================================================================

  /** Measures the processor implements; a declared measure outside this set fails closed. */
  private static final Set<String> SUPPORTED_MEASURES = Set.of("hash", "mask", "suppression");

  private static final List<String> PROTECTION_PHASES = List.of("atRest", "inTransit");

  private void validateDataProtection(JsonNode properties) {
    for (Map.Entry<String, JsonNode> entry : properties.properties()) {
      String propName = entry.getKey();
      JsonNode dataProtection = entry.getValue().path("$gsm:dataProtection");
      if (!dataProtection.isObject()) {
        continue;
      }

      if (dataProtection.path("atRest").has("encryption")
          && ArchetypeParsingService.hasAnnotation(entry.getValue(), "$gsm:queryable")) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ARCHETYPE_DATA_PROTECTION_QUERYABLE_EXCLUSIVITY,
            "Property '"
                + propName
                + "' declares both $gsm:queryable and $gsm:dataProtection.atRest.encryption; "
                + "ciphertext is not indexable",
            "property",
            propName);
      }

      for (String phase : PROTECTION_PHASES) {
        JsonNode measures = dataProtection.path(phase);
        if (!measures.isObject()) {
          continue;
        }
        measures
            .fieldNames()
            .forEachRemaining(
                measure -> {
                  if (!SUPPORTED_MEASURES.contains(measure)) {
                    throw new UnsupportedProtectionMeasureException(phase, measure, propName);
                  }
                });
      }
    }
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

    Set<String> firstIdentityBound =
        collectIdentityBoundFields(resolvedSchema.resolvedProperties(firstStmt));
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

  static Set<String> collectIdentityBoundFields(JsonNode properties) {
    Set<String> result = new HashSet<>();
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
