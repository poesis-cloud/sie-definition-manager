package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Validates Archetype schema composition: {@code $ref} chain convergence to a
 * GSM base, {@code
 * allOf} facet acyclicity, and {@code $gsm:sealed} enforcement.
 *
 * <p>
 * This service is stateless — schema resolution is provided via a
 * {@link Function} parameter at
 * each call site.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class ArchetypeCompositionValidationService {

  private static final Logger LOG =
      LoggerFactory.getLogger(ArchetypeCompositionValidationService.class);

  private static final List<String> BOOLEAN_ANNOTATIONS =
      List.of("$gsm:identityBound", "$gsm:queryable", "$gsm:unique");

  private static final List<String> PROTECTION_PHASES = List.of("atRest", "inTransit");

  /** Ordered by disclosure: a later measure reveals strictly less than an earlier one. */
  private static final List<String> PROTECTION_STRENGTH =
      List.of("mask", "encryption", "hash", "suppression");

  // ========================================================================
  // Schema composition validation
  // ========================================================================

  /**
   * Validates schema composition in non-strict (authoring-time) mode.
   *
   * @param schema         the archetype JSON Schema to validate
   * @param schemaResolver resolves an Archetype URI to its JSON Schema, or
   *                       {@code null} if not
   *                       found
   */
  void validateSchemaComposition(JsonNode schema, Function<String, JsonNode> schemaResolver) {
    String id = schema.has("$id") && schema.get("$id").isTextual() ? schema.get("$id").asText() : null;

    if (id != null && ArchetypeParsingService.isGsmBaseId(id)) {
      return;
    }

    Set<String> visited = new HashSet<>();
    if (id != null) {
      visited.add(id);
    }

    // 1) Validate the top-level $ref chain (base extension).
    Set<String> resolvedBases = new HashSet<>();
    JsonNode refNode = schema.get("$ref");
    if (refNode != null && refNode.isTextual()) {
      walkRefChain(refNode.asText(), resolvedBases, visited, schemaResolver);
    }

    // 2) Validate allOf entries (facets — no base convergence required).
    JsonNode allOf = schema.get("allOf");
    if (allOf != null && allOf.isArray()) {
      validateAllOfEntries(allOf, visited, schemaResolver);
    }

    Set<String> activeCompositionRefs = new HashSet<>();
    if (id != null) {
      activeCompositionRefs.add(id);
    }
    collectResolvedProperties(schema, schema, schemaResolver, activeCompositionRefs);

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
   * Resolves the set of GSM base archetype titles reachable through the top-level
   * {@code $ref}
   * chain. Uses strict mode (unresolvable intermediaries cause an error).
   *
   * @param ref            the initial {@code $ref} URI to walk
   * @param ownId          the archetype's own exact ID (added to visited set to
   *                       detect cycles), or {@code
   *     null}
   * @param schemaResolver resolves an Archetype URI to its JSON Schema, or
   *                       {@code null} if not
   *                       found
   * @return the set of resolved GSM base titles (empty for rootless archetypes,
   *         typically 0 or 1)
   */
  Set<String> resolveGsmBases(String ref, String ownId, Function<String, JsonNode> schemaResolver) {
    Set<String> resolvedBases = new HashSet<>();
    Set<String> visited = new HashSet<>();
    if (ownId != null) {
      visited.add(ownId);
    }
    walkRefChain(ref, resolvedBases, visited, schemaResolver);
    return resolvedBases;
  }

  /**
   * Resolves a property path against the resolved composition chain formed by
   * direct
   * properties, external
   * {@code $ref} inheritance, and inline or referenced {@code allOf} facets.
   */
  boolean resolvesPropertyPath(
      JsonNode schema, String propertyPath, Function<String, JsonNode> schemaResolver) {
    return resolvesPropertyPath(
        schema,
        schema,
        Arrays.asList(propertyPath.split("\\.")),
        0,
        schemaResolver,
        new HashSet<>());
  }

  /**
   * Resolves the resolved property set of an Archetype schema — GSM §11.1
   * annotation inheritance.
   *
   * <p>
   * The resolved set composes, in increasing precedence: {@code allOf} facet
   * properties, properties
   * reachable through the top-level {@code $ref} chain, and the schema's own
   * {@code properties}. It
   * is the surface over which every property-scoped {@code $gsm:*} keyword MUST
   * be resolved.
   *
   * @param schema         the archetype JSON Schema
   * @param schemaResolver resolves an Archetype URI to its JSON Schema, or
   *                       {@code null} if not found
   * @return resolved property name → property schema, in resolution order
   */
  public Map<String, JsonNode> resolvedProperties(
      JsonNode schema, Function<String, JsonNode> schemaResolver) {
    if (schema == null || !schema.isObject()) {
      return Map.of();
    }
    Set<String> activeRefs = new HashSet<>();
    JsonNode id = schema.get("$id");
    if (id != null && id.isTextual()) {
      activeRefs.add(id.asText());
    }
    return collectResolvedProperties(schema, schema, schemaResolver, activeRefs);
  }

  // ========================================================================
  // Internal
  // ========================================================================

  private boolean resolvesPropertyPath(
      JsonNode schema,
      JsonNode rootSchema,
      java.util.List<String> path,
      int pathIndex,
      Function<String, JsonNode> schemaResolver,
      Set<String> activeRefs) {
    JsonNode properties = schema.get("properties");
    if (properties != null && properties.has(path.get(pathIndex))) {
      if (pathIndex == path.size() - 1
          || resolvesPropertyPath(
              properties.get(path.get(pathIndex)),
              rootSchema,
              path,
              pathIndex + 1,
              schemaResolver,
              activeRefs)) {
        return true;
      }
    }

    JsonNode refNode = schema.get("$ref");
    if (refNode != null && refNode.isTextual()) {
      String ref = refNode.asText();
      String activeRef = (ref.startsWith("#") ? System.identityHashCode(rootSchema) + ":" + ref : ref)
          + "@"
          + pathIndex;
      if (activeRefs.add(activeRef)) {
        try {
          JsonNode referencedSchema;
          if ("#".equals(ref)) {
            referencedSchema = rootSchema;
          } else if (ref.startsWith("#/")) {
            referencedSchema = rootSchema.at(ref.substring(1));
          } else if (ref.startsWith("#")) {
            referencedSchema = null;
          } else {
            referencedSchema = schemaResolver.apply(ref);
          }
          if (referencedSchema != null
              && !referencedSchema.isMissingNode()
              && resolvesPropertyPath(
                  referencedSchema,
                  ref.startsWith("#") ? rootSchema : referencedSchema,
                  path,
                  pathIndex,
                  schemaResolver,
                  activeRefs)) {
            return true;
          }
        } finally {
          activeRefs.remove(activeRef);
        }
      }
    }

    JsonNode allOf = schema.get("allOf");
    if (allOf != null && allOf.isArray()) {
      for (JsonNode entry : allOf) {
        if (resolvesPropertyPath(entry, rootSchema, path, pathIndex, schemaResolver, activeRefs)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Walks the top-level $ref chain linearly: current → intermediate → ... → GSM
   * base. Collects GSM
   * bases, enforces acyclicity, sealed checks, and URI format.
   */
  private void walkRefChain(
      String ref,
      Set<String> resolvedBases,
      Set<String> visited,
      Function<String, JsonNode> schemaResolver) {
    String refTitle;
    try {
      refTitle = ArchetypeParsingService.parseIdentity(ref).title();
    } catch (IllegalArgumentException exception) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_EXCLUSIVE_BASE_CONVERGENCE,
          "Cannot resolve $ref '"
              + ref
              + "': must use gsmarc://{authority}/{segments}/{title}/v{version} convention",
          "ref",
          ref);
    }

    if (!visited.add(ref)) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_ACYCLICITY,
          "Cycle detected in $ref chain: '" + ref + "' already visited",
          "ref",
          ref);
    }

    if (ArchetypeParsingService.isGsmBaseId(ref)) {
      if (isSealedBaseArchetype(ref, schemaResolver)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_NON_SEALED,
            "Archetype $ref references sealed schema '"
                + refTitle
                + "' — tenant-defined archetypes MUST NOT extend sealed schemas",
            "sealedArchetype",
            refTitle);
      }
      resolvedBases.add(ref);
    } else {
      JsonNode intermediateSchema = schemaResolver.apply(ref);
      if (intermediateSchema == null) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY,
            "Cannot resolve Archetype $ref '" + ref + "'",
            "ref",
            ref);
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
        walkRefChain(intermediateRef.asText(), resolvedBases, visited, schemaResolver);
      }
    }
  }

  /**
   * Validates allOf entries (facets). Enforces URI format, acyclicity, and sealed
   * checks. Does NOT
   * collect or check for GSM base convergence — allOf is for facets only.
   */
  private void validateAllOfEntries(
      JsonNode allOf, Set<String> visited, Function<String, JsonNode> schemaResolver) {
    for (JsonNode entry : allOf) {
      if (!entry.has("$ref")) {
        continue;
      }

      String ref = entry.get("$ref").asText();

      // Skip local JSON Pointers (e.g., #/$defs/...)
      if (ref.startsWith("#")) {
        continue;
      }

      String refTitle;
      try {
        refTitle = ArchetypeParsingService.parseIdentity(ref).title();
      } catch (IllegalArgumentException exception) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_EXCLUSIVE_BASE_CONVERGENCE,
            "Cannot resolve allOf $ref '"
                + ref
                + "': must use gsmarc://{authority}/{segments}/{title}/v{version} convention",
            "ref",
            ref);
      }

      if (!visited.add(ref)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_ACYCLICITY,
            "Cycle detected in allOf chain: '" + ref + "' already visited",
            "ref",
            ref);
      }

      if (ArchetypeParsingService.isGsmBaseId(ref)) {
        if (isSealedBaseArchetype(ref, schemaResolver)) {
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
        JsonNode intermediateSchema = schemaResolver.apply(ref);
        if (intermediateSchema == null) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY,
              "Cannot resolve Archetype allOf $ref '" + ref + "'",
              "ref",
              ref);
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

  private Map<String, JsonNode> collectResolvedProperties(
      JsonNode schema,
      JsonNode documentRoot,
      Function<String, JsonNode> schemaResolver,
      Set<String> activeRefs) {
    if (schema == null || !schema.isObject()) {
      return Map.of();
    }

    Map<String, JsonNode> inherited = new LinkedHashMap<>();
    JsonNode refNode = schema.get("$ref");
    if (refNode != null && refNode.isTextual()) {
      ResolvedSchema resolved = resolveSchema(refNode.asText(), documentRoot, schemaResolver);
      if (!activeRefs.add(resolved.cycleKey())) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_ACYCLICITY,
            "Cycle detected in composed Archetype schema at $ref '" + refNode.asText() + "'",
            "ref",
            refNode.asText());
      }
      try {
        inherited.putAll(
            collectResolvedProperties(
                resolved.schema(), resolved.documentRoot(), schemaResolver, activeRefs));
      } finally {
        activeRefs.remove(resolved.cycleKey());
      }
    }

    JsonNode allOf = schema.get("allOf");
    if (allOf != null && allOf.isArray()) {
      Map<String, String> siblingOwners = new LinkedHashMap<>();
      int facetIndex = 0;
      for (JsonNode facet : allOf) {
        Map<String, JsonNode> facetProperties = collectResolvedProperties(facet, documentRoot, schemaResolver,
            activeRefs);
        String facetOwner = facetDescription(facet, facetIndex);
        for (Map.Entry<String, JsonNode> property : facetProperties.entrySet()) {
          String previousOwner = siblingOwners.putIfAbsent(property.getKey(), facetOwner);
          if (previousOwner != null) {
            throw RuleViolationException.of(
                AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_PROPERTY_DISJOINTNESS,
                "Sibling allOf facets "
                    + previousOwner
                    + " and "
                    + facetOwner
                    + " both expose resolved property '"
                    + property.getKey()
                    + "'; use distinct mount properties",
                "field",
                "allOf");
          }
          // The $ref chain outranks facets for the schema body, but never drops their annotations.
          inherited.merge(
              property.getKey(),
              property.getValue(),
              (fromRefChain, fromFacet) ->
                  joinAnnotations(fromFacet, fromRefChain, property.getKey()));
        }
        facetIndex++;
      }
    }

    Map<String, JsonNode> resolved = new LinkedHashMap<>(inherited);
    JsonNode directProperties = schema.get("properties");
    if (directProperties != null && directProperties.isObject()) {
      directProperties
          .properties()
          .forEach(
              property -> {
                JsonNode inheritedProperty = inherited.get(property.getKey());
                if (inheritedProperty != null
                    && explicitTypeSetDiffers(property.getValue(), inheritedProperty)) {
                  throw RuleViolationException.of(
                      AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_PROPERTY_TYPE_STABILITY,
                      "Property '"
                          + property.getKey()
                          + "' redefines an inherited property with a different explicit type set",
                      "field",
                      "/properties/" + escapeJsonPointerToken(property.getKey()));
                }
                resolved.put(
                    property.getKey(),
                    inheritedProperty == null
                        ? property.getValue()
                        : joinAnnotations(
                            inheritedProperty, property.getValue(), property.getKey()));
              });
    }
    return resolved;
  }

  /**
   * Joins an ancestor's {@code $gsm:*} keywords into a descendant's redeclaration of the same
   * property — GSM §11.1. Booleans join by disjunction, aliases by union, and
   * {@code $gsm:dataProtection} by the strongest measure per phase, so narrowing a property's
   * schema can never drop protection, queryability, uniqueness or identity binding.
   */
  private static JsonNode joinAnnotations(
      JsonNode inherited, JsonNode declared, String propertyName) {
    if (!inherited.isObject()) {
      return declared;
    }
    if (!declared.isObject()) {
      // A boolean schema carries no annotations of its own; keep the ancestor's.
      return inherited;
    }
    ObjectNode joined = declared.deepCopy();

    for (String annotation : BOOLEAN_ANNOTATIONS) {
      if (inherited.path(annotation).asBoolean(false)) {
        joined.put(annotation, true);
      }
    }

    JsonNode inheritedAliases = inherited.path("$gsm:aliases");
    if (inheritedAliases.isArray()) {
      Set<String> aliases = new LinkedHashSet<>();
      inheritedAliases.forEach(alias -> aliases.add(alias.asText()));
      joined.path("$gsm:aliases").forEach(alias -> aliases.add(alias.asText()));
      ArrayNode merged = joined.putArray("$gsm:aliases");
      aliases.forEach(merged::add);
    }

    JsonNode inheritedProtection = inherited.path("$gsm:dataProtection");
    if (inheritedProtection.isObject()) {
      ObjectNode protection = joined.path("$gsm:dataProtection").isObject()
          ? (ObjectNode) joined.get("$gsm:dataProtection")
          : joined.putObject("$gsm:dataProtection");
      for (String phase : PROTECTION_PHASES) {
        JsonNode inheritedPhase = inheritedProtection.path(phase);
        if (!inheritedPhase.isObject()) {
          continue;
        }
        JsonNode declaredPhase = protection.path(phase);
        if (measureStrength(declaredPhase) < measureStrength(inheritedPhase)) {
          if (declaredPhase.isObject()) {
            LOG.warn(
                "Property '{}' redeclares $gsm:dataProtection.{} as {} but an ancestor requires {};"
                    + " the ancestor's measure is applied and the redeclaration is inert",
                propertyName,
                phase,
                declaredPhase.fieldNames().next(),
                inheritedPhase.fieldNames().next());
          }
          protection.set(phase, inheritedPhase.deepCopy());
        }
      }
    }
    return joined;
  }

  /** Rank of the strongest measure declared in a protection phase, or -1 when none is declared. */
  private static int measureStrength(JsonNode phase) {
    int strongest = -1;
    if (!phase.isObject()) {
      return strongest;
    }
    for (Iterator<String> measures = phase.fieldNames(); measures.hasNext();) {
      strongest = Math.max(strongest, PROTECTION_STRENGTH.indexOf(measures.next()));
    }
    return strongest;
  }

  private ResolvedSchema resolveSchema(
      String ref, JsonNode documentRoot, Function<String, JsonNode> schemaResolver) {
    if ("#".equals(ref)) {
      return new ResolvedSchema(documentRoot, documentRoot, localCycleKey(documentRoot, ""));
    }
    if (ref.startsWith("#/")) {
      JsonNode resolved = documentRoot.at(ref.substring(1));
      if (resolved.isMissingNode()) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY,
            "Cannot resolve local Archetype $ref '" + ref + "'",
            "ref",
            ref);
      }
      return new ResolvedSchema(
          resolved, documentRoot, localCycleKey(documentRoot, ref.substring(1)));
    }
    if (ref.startsWith("#")) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY,
          "Cannot resolve unsupported local Archetype $ref '" + ref + "'",
          "ref",
          ref);
    }
    try {
      ArchetypeParsingService.parseIdentity(ref);
    } catch (IllegalArgumentException exception) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_REF_NORM,
          "Cannot resolve external Archetype $ref '" + ref + "': expected a gsmarc:// URI",
          "ref",
          ref);
    }
    JsonNode resolved = schemaResolver.apply(ref);
    if (resolved == null) {
      if (ArchetypeParsingService.isGsmBaseId(ref)) {
        return new ResolvedSchema(null, null, ref);
      }
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY,
          "Cannot resolve Archetype $ref '" + ref + "'",
          "ref",
          ref);
    }
    return new ResolvedSchema(resolved, resolved, ref);
  }

  private String localCycleKey(JsonNode documentRoot, String pointer) {
    JsonNode id = documentRoot.get("$id");
    String documentId = id != null && id.isTextual() ? id.asText() : "@" + System.identityHashCode(documentRoot);
    return pointer.isEmpty() ? documentId : documentId + "#" + pointer;
  }

  private String facetDescription(JsonNode facet, int index) {
    JsonNode ref = facet.get("$ref");
    return ref != null && ref.isTextual() ? "'" + ref.asText() + "'" : "at index " + index;
  }

  private boolean explicitTypeSetDiffers(JsonNode leftSchema, JsonNode rightSchema) {
    Set<String> left = explicitTypeSet(leftSchema);
    Set<String> right = explicitTypeSet(rightSchema);
    return left != null && right != null && !left.equals(right);
  }

  private String escapeJsonPointerToken(String token) {
    return token.replace("~", "~0").replace("/", "~1");
  }

  private Set<String> explicitTypeSet(JsonNode schema) {
    if (schema == null || !schema.isObject()) {
      return null;
    }
    JsonNode type = schema.get("type");
    if (type == null) {
      return null;
    }
    if (type.isTextual()) {
      return Set.of(type.asText());
    }
    if (type.isArray()) {
      Set<String> types = new HashSet<>();
      type.forEach(
          entry -> {
            if (entry.isTextual()) {
              types.add(entry.asText());
            }
          });
      return types;
    }
    return null;
  }

  private record ResolvedSchema(JsonNode schema, JsonNode documentRoot, String cycleKey) {
  }

  private boolean isSealedBaseArchetype(String id, Function<String, JsonNode> schemaResolver) {
    JsonNode schema = schemaResolver.apply(id);
    if (schema != null && schema.has("$gsm:sealed")) {
      return schema.get("$gsm:sealed").asBoolean();
    }
    return false;
  }
}
