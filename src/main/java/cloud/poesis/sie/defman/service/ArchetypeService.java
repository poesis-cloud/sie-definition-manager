package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * GSM Archetype ascription service.
 *
 * <p>Manages lifecycle and persistence of {@link ArchetypeEntity} ascriptions including schema
 * composition validation ({@code $ref} chain + {@code allOf} facets), {@code $gsm:*} annotation
 * well-formedness, subject type resolution, and vocabulary-driven index provisioning.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class ArchetypeService extends AbstractAscriptionService<ArchetypeEntity> {

  /** Maps schema title to DefinitionSubjectType for base archetypes. */
  private static final Map<String, DefinitionSubjectType> SCHEMA_TITLE_TO_SUBJECT_TYPE =
      Map.of(
          "Archetype", DefinitionSubjectType.ARCHETYPE,
          "StructureArchetype", DefinitionSubjectType.STRUCTURE,
          "MechanismArchetype", DefinitionSubjectType.MECHANISM,
          "EffectorArchetype", DefinitionSubjectType.EFFECTOR,
          "ReceptorArchetype", DefinitionSubjectType.RECEPTOR,
          "InteractionArchetype", DefinitionSubjectType.INTERACTION,
          "DirectiveArchetype", DefinitionSubjectType.DIRECTIVE,
          "NormArchetype", DefinitionSubjectType.NORM);

  // ======================================================================
  // Schema composition validation constants ($ref chain + allOf facets)
  // ======================================================================

  private static final Set<String> GSM_BASE_TITLES =
      Set.of(
          "StructureArchetype",
          "MechanismArchetype",
          "InteractionArchetype",
          "Archetype",
          "EffectorArchetype",
          "ReceptorArchetype",
          "DirectiveArchetype",
          "NormArchetype");

  private static final Pattern GSM_URI_PATTERN =
      Pattern.compile("^gsm://archetypes/([^/]+)/v\\d+$");

  // ======================================================================
  // $gsm:* annotation constants
  // ======================================================================

  private static final Set<String> KNOWN_ANNOTATIONS =
      Set.of(
          "$gsm:sealed",
          "$gsm:identityBound",
          "$gsm:queryable",
          "$gsm:unique",
          "$gsm:dataProtection");

  private static final Set<String> TOP_LEVEL_ANNOTATIONS = Set.of("$gsm:sealed");

  public record ArchetypeResolution(ArchetypeEntity archetype, DefinitionSubjectType subjectType) {}

  private static final Logger LOG = LoggerFactory.getLogger(ArchetypeService.class);

  private final ArchetypeRepository archetypeRepo;
  private final JdbcTemplate jdbcTemplate;

  /**
   * Constructs the Archetype service with its required dependencies.
   *
   * @param archetypeRepo the archetype repository
   * @param jdbcTemplate the JDBC template for index provisioning
   * @param definitionService the definition service
   * @param transitionService the status transition service
   * @param ascriptionService the ascription service for cross-subtype queries
   * @param entityManager the JPA entity manager
   * @param dataProtectionService the data protection service
   */
  public ArchetypeService(
      ArchetypeRepository archetypeRepo,
      JdbcTemplate jdbcTemplate,
      DefinitionService definitionService,
      AscriptionStatusTransitionService transitionService,
      AscriptionService ascriptionService,
      EntityManager entityManager,
      DataProtectionService dataProtectionService) {
    super(
        definitionService,
        transitionService,
        ascriptionService,
        archetypeRepo,
        entityManager,
        dataProtectionService);
    this.archetypeRepo = archetypeRepo;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public DefinitionSubjectType getSubjectType() {
    return DefinitionSubjectType.ARCHETYPE;
  }

  @Override
  protected AbstractAscriptionRepository<ArchetypeEntity> getRepository() {
    return archetypeRepo;
  }

  @Override
  public ArchetypeEntity buildEntity(
      DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
    if (statement == null || !statement.isObject()) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          "Archetype statement must be a JSON object",
          "field",
          "statement");
    }

    // GSM §5: $ref chain convergence + §8: $gsm:sealed enforcement
    validateSchemaComposition(statement);

    // GSM §8: deep $ref URI policy — reject external URIs everywhere
    validateRefUriPolicy(statement);

    // GSM §8: $gsm:* annotation well-formedness
    validateArchetypeAnnotations(statement, definition.getId());

    return new ArchetypeEntity(definition, archetypeRef, statement);
  }

  // ======================================================================
  // Subject type resolution + entity lookup
  // ======================================================================

  /**
   * Resolves an Archetype by id and derives the DefinitionSubjectType from its schema title. Used
   * by the controller to dispatch creation requests.
   *
   * @param archetypeId the archetype UUID
   * @return the resolved archetype with its derived subject type
   * @throws ResourceNotFoundException if no archetype exists with the given id
   * @throws RuleViolationException if the archetype has no title or is rootless
   */
  public ArchetypeResolution resolveForCreation(UUID archetypeId) {
    ArchetypeEntity archetype = findEntityById(archetypeId);
    DefinitionSubjectType type = resolveSubjectType(archetype);
    return new ArchetypeResolution(archetype, type);
  }

  /**
   * Finds an Archetype entity by its ascription id.
   *
   * @param id the ascription UUID
   * @return the archetype entity
   * @throws ResourceNotFoundException if no archetype exists with the given id
   */
  public ArchetypeEntity findEntityById(UUID id) {
    return archetypeRepo
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(PrimitiveType.ARCHETYPE, id));
  }

  /**
   * Finds an in-effect Archetype by its schema title via repository query.
   *
   * @param title the Archetype's schema title
   * @return an optional containing the archetype, or empty if not found
   */
  public java.util.Optional<ArchetypeEntity> findInEffectByTitle(String title) {
    return archetypeRepo.findInEffectByTitle(title);
  }

  /**
   * Batch-fetches Archetypes by their IDs.
   *
   * <p>Part of the explicit-fetch design (see README.md § "Batch fetch pattern").
   *
   * @param ids collection of Archetype IDs to retrieve
   * @return map of ID → ArchetypeEntity; IDs not found in the database are silently absent from the
   *     returned map
   */
  public Map<UUID, ArchetypeEntity> getByIds(Collection<UUID> ids) {
    return archetypeRepo.findAllById(ids).stream()
        .collect(Collectors.toMap(ArchetypeEntity::getId, Function.identity()));
  }

  /**
   * Find an in-effect Archetype by its title. Returns null if no matching in-effect Archetype
   * exists.
   *
   * @param title the Archetype's schema title
   * @return the archetype entity, or {@code null} if not found
   */
  public ArchetypeEntity findInEffectBySchemaTitle(String title) {
    return archetypeRepo.findInEffectByTitle(title).orElse(null);
  }

  private DefinitionSubjectType resolveSubjectType(ArchetypeEntity archetype) {
    JsonNode stmt = archetype.getStatement();
    if (stmt == null || !stmt.has("title")) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          "Cannot derive subject type: archetype has no title: " + archetype.getId(),
          "archetypeId",
          archetype.getId());
    }
    String title = stmt.get("title").asText();

    // Direct match for GSM base archetypes.
    DefinitionSubjectType type = SCHEMA_TITLE_TO_SUBJECT_TYPE.get(title);
    if (type != null) {
      return type;
    }

    // Tenant archetype: walk the top-level $ref chain to find the structural base.
    JsonNode refNode = stmt.get("$ref");
    if (refNode == null || !refNode.isTextual()) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          "Rootless archetype '"
              + title
              + "' cannot be used as archetype_id — no structural base "
              + "(top-level $ref to a GSM base required)",
          "title",
          title);
    }

    Set<String> resolvedBases = new HashSet<>();
    Set<String> visited = new HashSet<>();
    visited.add(title);
    walkRefChain(refNode.asText(), resolvedBases, visited, true);

    if (resolvedBases.isEmpty()) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          "Rootless archetype '"
              + title
              + "' cannot be used as archetype_id — $ref chain does not "
              + "converge to any GSM base",
          "title",
          title);
    }
    // resolvedBases.size() > 1 is already rejected by validateSchemaComposition
    // at authoring time; defensive check here for safety.
    if (resolvedBases.size() > 1) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_REF_CHAIN_EXCLUSIVE_BASE_CONVERGENCE,
          "Archetype '" + title + "' $ref chain converges to multiple GSM bases: " + resolvedBases,
          "title",
          title,
          "resolvedBases",
          resolvedBases);
    }

    String baseName = resolvedBases.iterator().next();
    type = SCHEMA_TITLE_TO_SUBJECT_TYPE.get(baseName);
    if (type == null) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          "Cannot map structural base '" + baseName + "' to a DefinitionSubjectType",
          "baseName",
          baseName);
    }
    return type;
  }

  // ---- Lifecycle descriptors ----

  @Override
  public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
    JsonNode stmt = entity.getStatement();
    if (stmt == null || !stmt.has("title")) return Map.of();
    return Map.of("title", stmt.get("title").asText());
  }

  @Override
  public void validateActivationUniqueness(AscriptionEntity entity) {
    // Statement is immutable and was validated at creation — title is guaranteed
    // non-null/non-blank.
    String title = entity.getStatement().get("title").asText();
    validatePropertyUniquenessAcrossDefinitions(
        "title",
        title,
        entity.getDefinition().getId(),
        archetypeRepo.findAllByStatusIn(
            EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)));
  }

  // ---- Lifecycle hooks ----

  @Override
  public void onActivation(AscriptionEntity entity) {
    if (entity instanceof ArchetypeEntity archetypeEntity) {
      // GSM §5: strict schema composition — all intermediaries must be in-effect.
      validateSchemaComposition(archetypeEntity.getStatement(), true);
      // $ref URI policy is NOT re-checked: statement is immutable (validated at
      // creation).
      provisionIndexes(archetypeEntity);
    }
  }

  @Override
  public void onDeactivation(AscriptionEntity entity) {
    if (entity instanceof ArchetypeEntity archetypeEntity) {
      deprovisionIndexes(archetypeEntity);
    }
  }

  // ========================================================================
  // Annotation-driven index management (from AnnotationIndexManager)
  // ========================================================================

  private record IndexSpec(String indexName, String ddl, String type, String propertyName) {}

  private void provisionIndexes(ArchetypeEntity archetype) {
    JsonNode stmt = archetype.getStatement();
    if (stmt == null) {
      return;
    }

    UUID archetypeDefId = archetype.getDefinition().getId();
    String schemaTitle = stmt.has("title") ? stmt.get("title").asText() : archetypeDefId.toString();

    JsonNode properties = stmt.get("properties");
    if (properties == null || !properties.isObject()) {
      return;
    }

    String tableName = resolveTableName(archetype);
    List<IndexSpec> specs = collectIndexSpecs(properties, archetypeDefId, schemaTitle, tableName);

    for (IndexSpec spec : specs) {
      try {
        jdbcTemplate.execute(spec.ddl());
        LOG.info(
            "Provisioned {} index '{}' for Archetype '{}' property '{}'",
            spec.type(),
            spec.indexName(),
            schemaTitle,
            spec.propertyName());
      } catch (Exception e) {
        LOG.warn("Failed to provision index '{}': {}", spec.indexName(), e.getMessage());
      }
    }
  }

  private void deprovisionIndexes(ArchetypeEntity archetype) {
    JsonNode stmt = archetype.getStatement();
    if (stmt == null) {
      return;
    }

    UUID archetypeDefId = archetype.getDefinition().getId();
    String schemaTitle = stmt.has("title") ? stmt.get("title").asText() : archetypeDefId.toString();

    JsonNode properties = stmt.get("properties");
    if (properties == null || !properties.isObject()) {
      return;
    }

    String tableName = resolveTableName(archetype);
    List<IndexSpec> specs = collectIndexSpecs(properties, archetypeDefId, schemaTitle, tableName);

    for (IndexSpec spec : specs) {
      String dropDdl = "DROP INDEX IF EXISTS " + spec.indexName();
      try {
        jdbcTemplate.execute(dropDdl);
        LOG.info("Deprovisioned index '{}' for Archetype '{}'", spec.indexName(), schemaTitle);
      } catch (Exception e) {
        LOG.warn("Failed to deprovision index '{}': {}", spec.indexName(), e.getMessage());
      }
    }
  }

  private List<IndexSpec> collectIndexSpecs(
      JsonNode properties, UUID archetypeDefId, String schemaTitle, String tableName) {

    List<IndexSpec> specs = new ArrayList<>();
    String sanitizedTitle = sanitizeIdentifier(schemaTitle);
    String archetypeIdLiteral = "'" + archetypeDefId + "'";

    for (Map.Entry<String, JsonNode> entry : properties.properties()) {
      String propName = entry.getKey();
      JsonNode propSchema = entry.getValue();
      String sanitizedProp = sanitizeIdentifier(propName);

      // $gsm:queryable
      if (hasAnnotation(propSchema, "$gsm:queryable")) {
        String indexName = "idx_gsm_q_" + sanitizedTitle + "_" + sanitizedProp;
        String type = propSchema.has("type") ? propSchema.get("type").asText() : "string";
        String indexType = "array".equals(type) ? "GIN" : "BTREE";
        String jsonbPath = "(statement->>'" + escapeJsonbKey(propName) + "')";

        String ddl;
        if ("GIN".equals(indexType)) {
          jsonbPath = "(statement->'" + escapeJsonbKey(propName) + "')";
          ddl =
              "CREATE INDEX IF NOT EXISTS "
                  + indexName
                  + " ON "
                  + tableName
                  + " USING GIN ("
                  + jsonbPath
                  + ")"
                  + " WHERE archetype_id = "
                  + archetypeIdLiteral;
        } else {
          ddl =
              "CREATE INDEX IF NOT EXISTS "
                  + indexName
                  + " ON "
                  + tableName
                  + " ("
                  + jsonbPath
                  + ")"
                  + " WHERE archetype_id = "
                  + archetypeIdLiteral;
        }

        specs.add(new IndexSpec(indexName, ddl, "queryable/" + indexType, propName));
      }

      // $gsm:unique
      if (hasAnnotation(propSchema, "$gsm:unique")) {
        String indexName = "idx_gsm_u_" + sanitizedTitle + "_" + sanitizedProp;
        String jsonbPath = "(statement->>'" + escapeJsonbKey(propName) + "')";

        String ddl =
            "CREATE UNIQUE INDEX IF NOT EXISTS "
                + indexName
                + " ON "
                + tableName
                + " ("
                + jsonbPath
                + ")"
                + " WHERE archetype_id = "
                + archetypeIdLiteral
                + " AND status IN ('ACTIVE','DEPRECATED')";

        specs.add(new IndexSpec(indexName, ddl, "unique", propName));
      }
    }

    return specs;
  }

  private static String sanitizeIdentifier(String input) {
    // Strict allowlist: only [a-zA-Z0-9_] survive, then lowercase + truncate.
    // Result is safe as an unquoted SQL identifier (no injection possible).
    String sanitized = input.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    return sanitized.substring(0, Math.min(sanitized.length(), 30));
  }

  private static String escapeJsonbKey(String key) {
    // Escape single quotes and backslashes for safe use in PostgreSQL JSONB key
    // literals
    return key.replace("\\", "\\\\").replace("'", "''");
  }

  private static boolean hasAnnotation(JsonNode node, String annotation) {
    return node.has(annotation) && node.get(annotation).asBoolean(false);
  }

  private String resolveTableName(ArchetypeEntity archetype) {
    DefinitionSubjectType subjectType = resolveSubjectType(archetype);
    return subjectType.name().toLowerCase();
  }

  // ========================================================================
  // AllOf chain validation
  // ========================================================================

  // ---- Public descendant resolution API ----

  /**
   * Returns the set of all ancestor Archetype titles reachable through the schema composition chain
   * (top-level {@code $ref} and {@code allOf} entries) of the given archetype. Includes the
   * archetype's own title if present. Does not include titles that cannot be resolved at the time
   * of the call (lenient mode). GSM base titles are included when reached.
   *
   * <p><b>Ordering guarantee:</b> the returned {@link LinkedHashSet} preserves insertion order,
   * which is nearest-ancestor-first (depth-first traversal of the composition chain). The
   * archetype's own title, when present, is always the first element. Callers (e.g., {@code
   * NormService} governance chain validation) may rely on this ordering.
   *
   * @param archetypeId the ascription ID of the archetype to inspect
   * @return the set of ancestor titles in nearest-ancestor-first order (may be empty for rootless
   *     archetypes)
   */
  public Set<String> getAncestorTitles(UUID archetypeId) {
    ArchetypeEntity archetype = findEntityById(archetypeId);
    JsonNode schema = archetype.getStatement();
    String title = schema.has("title") ? schema.get("title").asText() : null;

    Set<String> ancestors = new LinkedHashSet<>();
    if (title != null) {
      ancestors.add(title);
    }

    Set<String> visited = new HashSet<>();
    if (title != null) {
      visited.add(title);
    }

    collectAncestorTitles(schema, ancestors, visited);
    return ancestors;
  }

  /**
   * Checks whether the given archetype is a descendant (via schema composition chain) of an
   * ancestor identified by title. Returns true if the archetype's own title matches, or if any
   * ancestor matches.
   *
   * @param archetypeId the ascription ID of the archetype to check
   * @param ancestorTitle the title of the potential ancestor
   * @return true if the archetype descends from the ancestor
   */
  public boolean isDescendantOf(UUID archetypeId, String ancestorTitle) {
    return getAncestorTitles(archetypeId).contains(ancestorTitle);
  }

  private void collectAncestorTitles(JsonNode schema, Set<String> ancestors, Set<String> visited) {
    // Walk top-level $ref (base extension chain)
    if (schema.has("$ref") && schema.get("$ref").isTextual()) {
      String ref = schema.get("$ref").asText();
      String refTitle = extractTitleFromRef(ref);
      if (refTitle != null && visited.add(refTitle)) {
        ancestors.add(refTitle);
        if (!GSM_BASE_TITLES.contains(refTitle)) {
          JsonNode intermediateSchema = resolveArchetypeSchema(refTitle);
          if (intermediateSchema != null) {
            collectAncestorTitles(intermediateSchema, ancestors, visited);
          }
        }
      }
    }

    // Walk allOf entries (facets)
    JsonNode allOf = schema.get("allOf");
    if (allOf != null && allOf.isArray()) {
      for (JsonNode entry : allOf) {
        if (!entry.has("$ref")) {
          continue;
        }
        String ref = entry.get("$ref").asText();
        String refTitle = extractTitleFromRef(ref);
        if (refTitle == null || !visited.add(refTitle)) {
          continue;
        }
        ancestors.add(refTitle);
        if (GSM_BASE_TITLES.contains(refTitle)) {
          continue;
        }
        JsonNode intermediateSchema = resolveArchetypeSchema(refTitle);
        if (intermediateSchema == null) {
          continue; // lenient: unresolvable intermediary skipped
        }
        collectAncestorTitles(intermediateSchema, ancestors, visited);
      }
    }
  }

  // ---- Schema composition validation ----

  void validateSchemaComposition(JsonNode schema) {
    validateSchemaComposition(schema, false);
  }

  void validateSchemaComposition(JsonNode schema, boolean strict) {
    String title = schema.has("title") ? schema.get("title").asText() : null;

    // GSM base archetypes are exempt — they define the bases themselves.
    if (title != null && GSM_BASE_TITLES.contains(title)) {
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
      walkRefChain(refNode.asText(), resolvedBases, visited, strict);
    }

    // 2) Validate allOf entries (facets — no base convergence required).
    JsonNode allOf = schema.get("allOf");
    if (allOf != null && allOf.isArray()) {
      validateAllOfEntries(allOf, visited, strict);
    }

    // 0 bases → rootless archetype (valid: usable as qualifier/facet/data
    // archetype).
    // 1 base → based archetype (valid typing archetype for archetype_id).
    // 2+ bases → impossible via $ref chain (linear), but defensive check.
    if (resolvedBases.size() > 1) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_REF_CHAIN_EXCLUSIVE_BASE_CONVERGENCE,
          "Archetype schema $ref chain converges to multiple GSM base archetypes: " + resolvedBases,
          "resolvedBases",
          resolvedBases);
    }
  }

  /**
   * Walks the top-level $ref chain linearly: current → intermediate → ... → GSM base. Collects GSM
   * bases, enforces acyclicity, sealed checks, and URI format.
   */
  private void walkRefChain(
      String ref, Set<String> resolvedBases, Set<String> visited, boolean strict) {
    String refTitle = extractTitleFromRef(ref);

    if (refTitle == null) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_REF_CHAIN_EXCLUSIVE_BASE_CONVERGENCE,
          "Cannot resolve $ref '"
              + ref
              + "': must use gsm://archetypes/{title}/v{version} convention",
          "ref",
          ref);
    }

    if (!visited.add(refTitle)) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_REF_CHAIN_ACYCLICITY,
          "Cycle detected in $ref chain: '" + refTitle + "' already visited",
          "refTitle",
          refTitle);
    }

    if (GSM_BASE_TITLES.contains(refTitle)) {
      if (isSealedBaseArchetype(refTitle)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ARCHETYPE_REF_CHAIN_NON_SEALED,
            "Archetype $ref references sealed schema '"
                + refTitle
                + "' — tenant-defined archetypes MUST NOT extend sealed schemas",
            "sealedArchetype",
            refTitle);
      }
      resolvedBases.add(refTitle);
    } else {
      JsonNode intermediateSchema = resolveArchetypeSchema(refTitle);
      if (intermediateSchema == null) {
        if (strict) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.ARCHETYPE_REF_CHAIN_EXCLUSIVE_BASE_CONVERGENCE,
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
            AscriptionConsistencyRuleType.ARCHETYPE_REF_CHAIN_NON_SEALED,
            "Archetype $ref references sealed schema '"
                + refTitle
                + "' — tenant-defined archetypes MUST NOT extend sealed schemas",
            "sealedArchetype",
            refTitle);
      }

      // Continue walking the intermediate's own $ref chain.
      JsonNode intermediateRef = intermediateSchema.get("$ref");
      if (intermediateRef != null && intermediateRef.isTextual()) {
        walkRefChain(intermediateRef.asText(), resolvedBases, visited, strict);
      }
    }
  }

  /**
   * Validates allOf entries (facets). Enforces URI format, acyclicity, and sealed checks. Does NOT
   * collect or check for GSM base convergence — allOf is for facets only.
   */
  private void validateAllOfEntries(JsonNode allOf, Set<String> visited, boolean strict) {
    for (JsonNode entry : allOf) {
      if (!entry.has("$ref")) {
        continue;
      }

      String ref = entry.get("$ref").asText();

      // Skip local JSON Pointers (e.g., #/$defs/...)
      if (ref.startsWith("#")) {
        continue;
      }

      String refTitle = extractTitleFromRef(ref);

      if (refTitle == null) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ARCHETYPE_REF_CHAIN_EXCLUSIVE_BASE_CONVERGENCE,
            "Cannot resolve allOf $ref '"
                + ref
                + "': must use gsm://archetypes/{title}/v{version} convention",
            "ref",
            ref);
      }

      if (!visited.add(refTitle)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_CHAIN_ACYCLICITY,
            "Cycle detected in allOf chain: '" + refTitle + "' already visited",
            "refTitle",
            refTitle);
      }

      if (GSM_BASE_TITLES.contains(refTitle)) {
        if (isSealedBaseArchetype(refTitle)) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_CHAIN_NON_SEALED,
              "Archetype allOf references sealed schema '"
                  + refTitle
                  + "' — tenant-defined archetypes MUST NOT extend sealed schemas",
              "sealedArchetype",
              refTitle);
        }
        // Facet referencing an unsealed GSM base in allOf is allowed — it
        // adds base properties as a facet, but does NOT determine subject type.
      } else {
        JsonNode intermediateSchema = resolveArchetypeSchema(refTitle);
        if (intermediateSchema == null) {
          if (strict) {
            throw RuleViolationException.of(
                AscriptionConsistencyRuleType.ARCHETYPE_REF_CHAIN_EXCLUSIVE_BASE_CONVERGENCE,
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
              AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_CHAIN_NON_SEALED,
              "Archetype allOf references sealed schema '"
                  + refTitle
                  + "' — tenant-defined archetypes MUST NOT extend sealed schemas",
              "sealedArchetype",
              refTitle);
        }
      }
    }
  }

  private boolean isSealedBaseArchetype(String title) {
    JsonNode schema = resolveArchetypeSchema(title);
    if (schema != null && schema.has("$gsm:sealed")) {
      return schema.get("$gsm:sealed").asBoolean();
    }
    return false;
  }

  private JsonNode resolveArchetypeSchema(String title) {
    return archetypeRepo.findInEffectByTitle(title).map(ArchetypeEntity::getStatement).orElse(null);
  }

  static String extractTitleFromRef(String ref) {
    Matcher m = GSM_URI_PATTERN.matcher(ref);
    if (m.matches()) {
      return m.group(1);
    }
    return null;
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
   * <p>Called at authoring time (buildEntity) to catch prohibited URIs early. The composition
   * validation in {@link #validateSchemaComposition} remains the authoritative check for schema
   * chain semantics; this method is a complementary breadth scan.
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
  // Archetype $gsm:* annotation validation
  // ========================================================================

  void validateArchetypeAnnotations(JsonNode schema, UUID definitionId) {
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

    validateIdentityBoundSetImmutability(definitionId, identityBoundFields);
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
  // $gsm:identityBound set immutability (Archetype authoring time)
  // ========================================================================

  private void validateIdentityBoundSetImmutability(UUID definitionId, Set<String> currentSet) {
    if (definitionId == null || currentSet.isEmpty()) {
      return;
    }

    List<ArchetypeEntity> existing =
        archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(definitionId);
    if (existing.isEmpty()) {
      return;
    }

    ArchetypeEntity first = existing.getLast();
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
}
