package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Validates Archetype schema composition: {@code $ref} chain convergence to a GSM base, {@code
 * allOf} facet acyclicity, and {@code $gsm:sealed} enforcement.
 *
 * <p>Extracted from {@link ArchetypeService} to separate schema composition validation from
 * lifecycle and entity management. This service is stateless — schema resolution is provided via a
 * {@link Function} parameter at each call site.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class ArchetypeSchemaCompositionValidationService {

  private static final Logger LOG =
      LoggerFactory.getLogger(ArchetypeSchemaCompositionValidationService.class);

  // ========================================================================
  // Schema composition validation
  // ========================================================================

  /**
   * Validates schema composition in non-strict (authoring-time) mode.
   *
   * @param schema the archetype JSON Schema to validate
   * @param schemaResolver resolves an archetype title to its JSON Schema, or {@code null} if not
   *     found
   */
  void validateSchemaComposition(JsonNode schema, Function<String, JsonNode> schemaResolver) {
    validateSchemaComposition(schema, false, schemaResolver);
  }

  /**
   * Validates Archetype schema composition: {@code $ref} chain convergence, {@code allOf} facet
   * validity, acyclicity, and {@code $gsm:sealed} enforcement.
   *
   * @param schema the archetype JSON Schema to validate
   * @param strict when {@code true}, unresolvable intermediaries cause an error (activation-time);
   *     when {@code false}, they emit a warning (authoring-time)
   * @param schemaResolver resolves an archetype title to its JSON Schema, or {@code null} if not
   *     found
   */
  void validateSchemaComposition(
      JsonNode schema, boolean strict, Function<String, JsonNode> schemaResolver) {
    String title = schema.has("title") ? schema.get("title").asText() : null;

    // GSM base archetypes are exempt — they define the bases themselves.
    if (title != null && DefinitionSubjectType.archetypeTitles().contains(title)) {
      return;
    }

    Set<String> visited = new HashSet<>();
    if (title != null) {
      visited.add(title);
    }

    // 1) Validate the top-level $ref chain (base extension).
    Set<String> resolvedBases = new HashSet<>();
    JsonNode refNode = schema.get("$ref");
    if (refNode != null && refNode.isTextual()) {
      walkRefChain(refNode.asText(), resolvedBases, visited, strict, schemaResolver);
    }

    // 2) Validate allOf entries (facets — no base convergence required).
    JsonNode allOf = schema.get("allOf");
    if (allOf != null && allOf.isArray()) {
      validateAllOfEntries(allOf, visited, strict, schemaResolver);
    }

    // 0 bases → rootless archetype (valid: usable as qualifier/facet/data
    // archetype).
    // 1 base → based archetype (valid typing archetype for archetype_id).
    // 2+ bases → impossible via $ref chain (linear), but defensive check.
    if (resolvedBases.size() > 1) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_EXCLUSIVE_BASE_CONVERGENCE,
          "Archetype schema $ref chain converges to multiple GSM base archetypes: " + resolvedBases,
          "resolvedBases",
          resolvedBases);
    }
  }

  /**
   * Resolves the set of GSM base archetype titles reachable through the top-level {@code $ref}
   * chain. Uses strict mode (unresolvable intermediaries cause an error).
   *
   * @param ref the initial {@code $ref} URI to walk
   * @param ownTitle the archetype's own title (added to visited set to detect cycles), or {@code
   *     null}
   * @param schemaResolver resolves an archetype title to its JSON Schema, or {@code null} if not
   *     found
   * @return the set of resolved GSM base titles (empty for rootless archetypes, typically 0 or 1)
   */
  Set<String> resolveGsmBases(
      String ref, String ownTitle, Function<String, JsonNode> schemaResolver) {
    Set<String> resolvedBases = new HashSet<>();
    Set<String> visited = new HashSet<>();
    if (ownTitle != null) {
      visited.add(ownTitle);
    }
    walkRefChain(ref, resolvedBases, visited, true, schemaResolver);
    return resolvedBases;
  }

  // ========================================================================
  // Internal
  // ========================================================================

  /**
   * Walks the top-level $ref chain linearly: current → intermediate → ... → GSM base. Collects GSM
   * bases, enforces acyclicity, sealed checks, and URI format.
   */
  private void walkRefChain(
      String ref,
      Set<String> resolvedBases,
      Set<String> visited,
      boolean strict,
      Function<String, JsonNode> schemaResolver) {
    String refTitle = ArchetypeSchemaService.extractTitleFromRef(ref);

    if (refTitle == null) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_EXCLUSIVE_BASE_CONVERGENCE,
          "Cannot resolve $ref '"
              + ref
              + "': must use gsm://archetypes/{title}/v{version} convention",
          "ref",
          ref);
    }

    if (!visited.add(refTitle)) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_ACYCLICITY,
          "Cycle detected in $ref chain: '" + refTitle + "' already visited",
          "refTitle",
          refTitle);
    }

    if (ArchetypeSchemaService.isGsmBaseTitle(refTitle)) {
      if (isSealedBaseArchetype(refTitle, schemaResolver)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_NON_SEALED,
            "Archetype $ref references sealed schema '"
                + refTitle
                + "' — tenant-defined archetypes MUST NOT extend sealed schemas",
            "sealedArchetype",
            refTitle);
      }
      resolvedBases.add(refTitle);
    } else {
      JsonNode intermediateSchema = schemaResolver.apply(refTitle);
      if (intermediateSchema == null) {
        if (strict) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_EXCLUSIVE_BASE_CONVERGENCE,
              "Cannot resolve intermediary archetype '"
                  + refTitle
                  + "' referenced via $ref — no in-effect Archetype with this title",
              "refTitle",
              refTitle);
        }
        LOG.warn(
            "$ref '{}' not resolvable at authoring time — will be validated at activation",
            refTitle);
        return;
      }

      if (intermediateSchema.has("$gsm:sealed")
          && intermediateSchema.get("$gsm:sealed").asBoolean()) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_NON_SEALED,
            "Archetype $ref references sealed schema '"
                + refTitle
                + "' — tenant-defined archetypes MUST NOT extend sealed schemas",
            "sealedArchetype",
            refTitle);
      }

      // Continue walking the intermediate's own $ref chain.
      JsonNode intermediateRef = intermediateSchema.get("$ref");
      if (intermediateRef != null && intermediateRef.isTextual()) {
        walkRefChain(intermediateRef.asText(), resolvedBases, visited, strict, schemaResolver);
      }
    }
  }

  /**
   * Validates allOf entries (facets). Enforces URI format, acyclicity, and sealed checks. Does NOT
   * collect or check for GSM base convergence — allOf is for facets only.
   */
  private void validateAllOfEntries(
      JsonNode allOf,
      Set<String> visited,
      boolean strict,
      Function<String, JsonNode> schemaResolver) {
    for (JsonNode entry : allOf) {
      if (!entry.has("$ref")) {
        continue;
      }

      String ref = entry.get("$ref").asText();

      // Skip local JSON Pointers (e.g., #/$defs/...)
      if (ref.startsWith("#")) {
        continue;
      }

      String refTitle = ArchetypeSchemaService.extractTitleFromRef(ref);

      if (refTitle == null) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_EXCLUSIVE_BASE_CONVERGENCE,
            "Cannot resolve allOf $ref '"
                + ref
                + "': must use gsm://archetypes/{title}/v{version} convention",
            "ref",
            ref);
      }

      if (!visited.add(refTitle)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_ACYCLICITY,
            "Cycle detected in allOf chain: '" + refTitle + "' already visited",
            "refTitle",
            refTitle);
      }

      if (ArchetypeSchemaService.isGsmBaseTitle(refTitle)) {
        if (isSealedBaseArchetype(refTitle, schemaResolver)) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_NON_SEALED,
              "Archetype allOf references sealed schema '"
                  + refTitle
                  + "' — tenant-defined archetypes MUST NOT extend sealed schemas",
              "sealedArchetype",
              refTitle);
        }
        // Facet referencing an unsealed GSM base in allOf is allowed — it
        // adds base properties as a facet, but does NOT determine subject type.
      } else {
        JsonNode intermediateSchema = schemaResolver.apply(refTitle);
        if (intermediateSchema == null) {
          if (strict) {
            throw RuleViolationException.of(
                AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_EXCLUSIVE_BASE_CONVERGENCE,
                "Cannot resolve intermediary archetype '"
                    + refTitle
                    + "' referenced via allOf — no in-effect Archetype with this title",
                "refTitle",
                refTitle);
          }
          LOG.warn(
              "allOf $ref '{}' not resolvable at authoring time — will be validated at activation",
              refTitle);
          continue;
        }

        if (intermediateSchema.has("$gsm:sealed")
            && intermediateSchema.get("$gsm:sealed").asBoolean()) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_NON_SEALED,
              "Archetype allOf references sealed schema '"
                  + refTitle
                  + "' — tenant-defined archetypes MUST NOT extend sealed schemas",
              "sealedArchetype",
              refTitle);
        }
      }
    }
  }

  private boolean isSealedBaseArchetype(String title, Function<String, JsonNode> schemaResolver) {
    JsonNode schema = schemaResolver.apply(title);
    if (schema != null && schema.has("$gsm:sealed")) {
      return schema.get("$gsm:sealed").asBoolean();
    }
    return false;
  }
}
