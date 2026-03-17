package cloud.poesis.sie.defman.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;

@Service
public class ArchetypeService extends AbstractAscriptionService {

    /** Maps schema title to DefinitionSubjectType for base archetypes. */
    private static final Map<String, DefinitionSubjectType> SCHEMA_TITLE_TO_SUBJECT_TYPE = Map.of(
            "Archetype", DefinitionSubjectType.ARCHETYPE,
            "StructureArchetype", DefinitionSubjectType.STRUCTURE,
            "MechanismArchetype", DefinitionSubjectType.MECHANISM,
            "InterfaceArchetype", DefinitionSubjectType.INTERFACE,
            "EffectorArchetype", DefinitionSubjectType.EFFECTOR,
            "ReceptorArchetype", DefinitionSubjectType.RECEPTOR,
            "InteractionArchetype", DefinitionSubjectType.INTERACTION,
            "DirectiveArchetype", DefinitionSubjectType.DIRECTIVE,
            "NormArchetype", DefinitionSubjectType.NORM);

    // ======================================================================
    // AllOf chain validation constants (from AllOfChainValidator)
    // ======================================================================

    private static final Set<String> GSM_BASE_TITLES = Set.of(
            "StructureArchetype", "MechanismArchetype", "InteractionArchetype",
            "InterfaceArchetype", "Archetype", "EffectorArchetype",
            "ReceptorArchetype", "DirectiveArchetype", "NormArchetype");

    private static final Pattern GSM_URI_PATTERN = Pattern.compile("^gsm://archetypes/([^/]+)/v\\d+$");

    // ======================================================================
    // GsmAnnotation constants (from GsmAnnotationValidator)
    // ======================================================================

    private static final Set<String> KNOWN_ANNOTATIONS = Set.of(
            "$gsm:sealed", "$gsm:identityBound", "$gsm:queryable",
            "$gsm:unique", "$gsm:validation", "$gsm:dataProtection");

    private static final Set<String> TOP_LEVEL_ANNOTATIONS = Set.of(
            "$gsm:sealed", "$gsm:validation");

    private static final Set<String> INDEXABLE_TYPES = Set.of(
            "string", "number", "integer", "boolean");

    private static final int DEFAULT_MAX_QUERYABLE = 8;

    private static final Collection<AscriptionStatusType> IN_EFFECT = List.of(AscriptionStatusType.ACTIVE,
            AscriptionStatusType.DEPRECATED);

    public record ArchetypeResolution(ArchetypeEntity archetype, DefinitionSubjectType subjectType) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(ArchetypeService.class);

    private final ArchetypeRepository archetypeRepo;
    private final JdbcTemplate jdbcTemplate;

    /**
     * CEL compiler for $gsm:validation expression validation. Uses 'this' as
     * root variable.
     */
    private final CelCompiler validationCompiler;

    public ArchetypeService(
            ArchetypeRepository archetypeRepo,
            JdbcTemplate jdbcTemplate) {
        this.archetypeRepo = archetypeRepo;
        this.jdbcTemplate = jdbcTemplate;
        this.validationCompiler = CelCompilerFactory.standardCelCompilerBuilder()
                .addVar("this", SimpleType.DYN)
                .build();
    }

    @Override
    public DefinitionSubjectType getSubjectType() {
        return DefinitionSubjectType.ARCHETYPE;
    }

    @Override
    public AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
        if (statement == null || !statement.isObject()) {
            throw new IllegalArgumentException("Archetype statement must be a JSON object");
        }

        // GSM §5: allOf chain convergence + §8: $gsm:sealed enforcement
        validateAllOfChain(statement);

        // GSM §8: $gsm:* annotation well-formedness
        validateArchetypeAnnotations(statement, definition.getId());

        return new ArchetypeEntity(definition, archetypeRef, statement);
    }

    @Override
    public AscriptionEntity save(AscriptionEntity entity) {
        return archetypeRepo.save((ArchetypeEntity) entity);
    }

    @Override
    public Page<? extends AscriptionEntity> findAll(Pageable pageable) {
        return archetypeRepo.findAll(pageable);
    }

    @Override
    public Page<? extends AscriptionEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable) {
        return archetypeRepo.findAllByStatus(status, pageable);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionId(UUID definitionId) {
        return archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return archetypeRepo.findAllByDefinitionIdAndStatusIn(definitionId, statuses);
    }

    // ======================================================================
    // Subject type resolution + entity lookup
    // ======================================================================

    /**
     * Resolves an Archetype by id and derives the DefinitionSubjectType from
     * its schema title. Used by the controller to dispatch creation requests.
     */
    public ArchetypeResolution resolveForCreation(UUID archetypeId) {
        ArchetypeEntity archetype = findEntityById(archetypeId);
        DefinitionSubjectType type = resolveSubjectType(archetype);
        return new ArchetypeResolution(archetype, type);
    }

    public ArchetypeEntity findEntityById(UUID id) {
        return archetypeRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Archetype not found: " + id));
    }

    /**
     * Batch-fetches Archetypes by their IDs.
     *
     * <p>Part of the explicit-fetch design (see README.md
     * § "Batch fetch pattern").
     *
     * @param ids collection of Archetype IDs to retrieve
     * @return map of ID → ArchetypeEntity; IDs not found in the database
     *         are silently absent from the returned map
     */
    public Map<UUID, ArchetypeEntity> getByIds(Collection<UUID> ids) {
        return archetypeRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(ArchetypeEntity::getId, Function.identity()));
    }

    /**
     * Find an in-effect Archetype by its title.
     * Returns null if no matching in-effect Archetype exists.
     */
    public ArchetypeEntity findInEffectBySchemaTitle(String title) {
        List<ArchetypeEntity> inEffect = archetypeRepo.findAllByStatusIn(IN_EFFECT);
        for (ArchetypeEntity a : inEffect) {
            JsonNode stmt = a.getStatement();
            if (stmt != null && stmt.has("title") && title.equals(stmt.get("title").asText())) {
                return a;
            }
        }
        return null;
    }

    private DefinitionSubjectType resolveSubjectType(ArchetypeEntity archetype) {
        JsonNode stmt = archetype.getStatement();
        if (stmt == null || !stmt.has("title")) {
            throw new IllegalArgumentException(
                    "Cannot derive subject type: archetype has no title: " + archetype.getId());
        }
        String title = stmt.get("title").asText();

        // Direct match for GSM base archetypes.
        DefinitionSubjectType type = SCHEMA_TITLE_TO_SUBJECT_TYPE.get(title);
        if (type != null) {
            return type;
        }

        // Tenant archetype: walk the allOf chain to find the structural base.
        JsonNode allOf = stmt.get("allOf");
        if (allOf == null || !allOf.isArray() || allOf.isEmpty()) {
            throw new IllegalArgumentException(
                    "Rootless archetype '" + title
                            + "' cannot be used as archetype_id — no structural base (allOf chain required)");
        }

        Set<String> resolvedBases = new HashSet<>();
        Set<String> visited = new HashSet<>();
        visited.add(title);
        walkAllOfChain(allOf, resolvedBases, visited);

        if (resolvedBases.isEmpty()) {
            throw new IllegalArgumentException(
                    "Rootless archetype '" + title
                            + "' cannot be used as archetype_id — allOf chain does not converge to any GSM base");
        }
        // resolvedBases.size() > 1 is already rejected by validateAllOfChain at
        // authoring time;
        // defensive check here for safety.
        if (resolvedBases.size() > 1) {
            throw new IllegalArgumentException(
                    "Archetype '" + title + "' allOf chain converges to multiple GSM bases: " + resolvedBases);
        }

        String baseName = resolvedBases.iterator().next();
        type = SCHEMA_TITLE_TO_SUBJECT_TYPE.get(baseName);
        if (type == null) {
            throw new IllegalArgumentException(
                    "Cannot map structural base '" + baseName + "' to a DefinitionSubjectType");
        }
        return type;
    }

    // ---- Lifecycle descriptors ----

    @Override
    public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
        JsonNode stmt = entity.getStatement();
        if (stmt == null || !stmt.has("title"))
            return Map.of();
        return Map.of("title", stmt.get("title").asText());
    }

    @Override
    public void validateActivationUniqueness(AscriptionEntity entity) {
        JsonNode stmt = entity.getStatement();
        if (stmt == null || !stmt.has("title")) {
            throw new IllegalArgumentException("Archetype title must not be null or empty");
        }
        String title = stmt.get("title").asText();
        if (title.isBlank()) {
            throw new IllegalArgumentException("Archetype title must not be empty");
        }
        UUID thisDefId = entity.getDefinition().getId();
        List<ArchetypeEntity> inEffect = archetypeRepo.findAllByStatusIn(
                List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED));
        for (ArchetypeEntity a : inEffect) {
            if (a.getDefinition().getId().equals(thisDefId))
                continue;
            JsonNode aStmt = a.getStatement();
            String aTitle = (aStmt != null && aStmt.has("title")) ? aStmt.get("title").asText() : null;
            if (title.equals(aTitle)) {
                throw new IllegalArgumentException(
                        "Archetype title '" + title + "' duplicates in-effect Archetype "
                                + a.getId() + " (definition " + a.getDefinition().getId() + ")");
            }
        }
    }

    // ---- Lifecycle hooks ----

    @Override
    public void onActivation(AscriptionEntity entity) {
        if (entity instanceof ArchetypeEntity archetypeEntity) {
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

    private record IndexSpec(String indexName, String ddl, String type, String propertyName) {
    }

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

        List<IndexSpec> specs = collectIndexSpecs(properties, archetypeDefId, schemaTitle);

        for (IndexSpec spec : specs) {
            try {
                jdbcTemplate.execute(spec.ddl());
                LOG.info("Provisioned {} index '{}' for Archetype '{}' property '{}'",
                        spec.type(), spec.indexName(), schemaTitle, spec.propertyName());
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

        List<IndexSpec> specs = collectIndexSpecs(properties, archetypeDefId, schemaTitle);

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
            JsonNode properties, UUID archetypeDefId, String schemaTitle) {

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
                    ddl = "CREATE INDEX IF NOT EXISTS " + indexName
                            + " ON ascription USING GIN (" + jsonbPath + ")"
                            + " WHERE archetype_id = " + archetypeIdLiteral;
                } else {
                    ddl = "CREATE INDEX IF NOT EXISTS " + indexName
                            + " ON ascription (" + jsonbPath + ")"
                            + " WHERE archetype_id = " + archetypeIdLiteral;
                }

                specs.add(new IndexSpec(indexName, ddl, "queryable/" + indexType, propName));
            }

            // $gsm:unique
            if (hasAnnotation(propSchema, "$gsm:unique")) {
                String indexName = "idx_gsm_u_" + sanitizedTitle + "_" + sanitizedProp;
                String jsonbPath = "(statement->>'" + escapeJsonbKey(propName) + "')";

                String ddl = "CREATE UNIQUE INDEX IF NOT EXISTS " + indexName
                        + " ON ascription (" + jsonbPath + ")"
                        + " WHERE archetype_id = " + archetypeIdLiteral
                        + " AND status IN ('ACTIVE','DEPRECATED')";

                specs.add(new IndexSpec(indexName, ddl, "unique", propName));
            }
        }

        return specs;
    }

    private static String sanitizeIdentifier(String input) {
        return input.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase()
                .substring(0, Math.min(input.length(), 30));
    }

    private static String escapeJsonbKey(String key) {
        return key.replace("'", "''").replace("\\", "\\\\");
    }

    private static boolean hasAnnotation(JsonNode node, String annotation) {
        return node.has(annotation) && node.get(annotation).asBoolean(false);
    }

    // ========================================================================
    // AllOf chain validation (inlined from AllOfChainValidator)
    // ========================================================================

    void validateAllOfChain(JsonNode schema) {
        String title = schema.has("title") ? schema.get("title").asText() : null;

        // GSM base archetypes are exempt — they define the bases themselves.
        if (title != null && GSM_BASE_TITLES.contains(title)) {
            return;
        }

        JsonNode allOf = schema.get("allOf");

        // No allOf → rootless archetype (valid: usable as qualifier/facet/data
        // archetype).
        if (allOf == null || !allOf.isArray() || allOf.isEmpty()) {
            return;
        }

        Set<String> resolvedBases = new HashSet<>();
        Set<String> visited = new HashSet<>();
        if (title != null) {
            visited.add(title);
        }

        walkAllOfChain(allOf, resolvedBases, visited);

        // 0 bases → rootless archetype with allOf (e.g., facet extending another
        // facet).
        // 1 base → structural archetype (valid typing archetype for archetype_id).
        // 2+ bases → divergent structural bases (ambiguous subject type → rejected).
        if (resolvedBases.size() > 1) {
            throw new IllegalArgumentException(
                    "Archetype schema allOf chain converges to multiple GSM base archetypes: "
                            + resolvedBases);
        }
    }

    private void walkAllOfChain(JsonNode allOf, Set<String> resolvedBases, Set<String> visited) {
        for (JsonNode entry : allOf) {
            if (!entry.has("$ref")) {
                continue;
            }

            String ref = entry.get("$ref").asText();
            String refTitle = extractTitleFromRef(ref);

            if (refTitle == null) {
                throw new IllegalArgumentException(
                        "Cannot resolve allOf $ref '" + ref
                                + "': must use gsm://archetypes/{title}/v{version} convention");
            }

            if (!visited.add(refTitle)) {
                throw new IllegalArgumentException(
                        "Cycle detected in allOf chain: '" + refTitle + "' already visited");
            }

            if (GSM_BASE_TITLES.contains(refTitle)) {
                if (isSealedBaseArchetype(refTitle)) {
                    throw new IllegalArgumentException(
                            "Archetype schema allOf references sealed schema '"
                                    + refTitle + "' — tenant-defined archetypes MUST NOT extend sealed schemas");
                }
                resolvedBases.add(refTitle);
            } else {
                JsonNode intermediateSchema = resolveArchetypeSchema(refTitle);
                if (intermediateSchema == null) {
                    throw new IllegalArgumentException(
                            "Cannot resolve intermediary archetype '" + refTitle
                                    + "' referenced via allOf — no in-effect Archetype with this title");
                }

                if (intermediateSchema.has("$gsm:sealed")
                        && intermediateSchema.get("$gsm:sealed").asBoolean()) {
                    throw new IllegalArgumentException(
                            "Archetype schema allOf references sealed schema '"
                                    + refTitle + "' — tenant-defined archetypes MUST NOT extend sealed schemas");
                }

                JsonNode intermediateAllOf = intermediateSchema.get("allOf");
                if (intermediateAllOf != null && intermediateAllOf.isArray()) {
                    walkAllOfChain(intermediateAllOf, resolvedBases, visited);
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
        List<ArchetypeEntity> archetypes = archetypeRepo.findAllByStatusIn(IN_EFFECT);
        for (ArchetypeEntity a : archetypes) {
            JsonNode stmt = a.getStatement();
            if (stmt.has("title") && title.equals(stmt.get("title").asText())) {
                return stmt;
            }
        }
        return null;
    }

    static String extractTitleFromRef(String ref) {
        Matcher m = GSM_URI_PATTERN.matcher(ref);
        if (m.matches()) {
            return m.group(1);
        }
        return null;
    }

    // ========================================================================
    // Archetype $gsm:* annotation validation (inlined from GsmAnnotationValidator)
    // ========================================================================

    void validateArchetypeAnnotations(JsonNode schema, UUID definitionId) {
        validateTopLevelAnnotations(schema);

        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }

        int queryableCount = 0;
        Set<String> identityBoundFields = new HashSet<>();

        for (Map.Entry<String, JsonNode> entry : properties.properties()) {
            String propName = entry.getKey();
            JsonNode propSchema = entry.getValue();

            checkUnknownAnnotations(propSchema, propName);

            if (hasAnnotation(propSchema, "$gsm:queryable")) {
                queryableCount++;
                validateQueryableType(propSchema, propName);
            }

            if (propSchema.has("$gsm:dataProtection")) {
                validateDataProtection(propSchema.get("$gsm:dataProtection"), propName,
                        hasAnnotation(propSchema, "$gsm:queryable"));
            }

            if (hasAnnotation(propSchema, "$gsm:identityBound")) {
                identityBoundFields.add(propName);
            }
        }

        if (queryableCount > DEFAULT_MAX_QUERYABLE) {
            throw new IllegalArgumentException(
                    "Too many $gsm:queryable properties (" + queryableCount
                            + "): maximum allowed is " + DEFAULT_MAX_QUERYABLE);
        }

        validateIdentityBoundSetImmutability(definitionId, identityBoundFields);

        // GSM §8: $gsm:validation — validate each expression is parseable,
        // deterministic CEL
        if (schema.has("$gsm:validation")) {
            validateValidationExpressions(schema.get("$gsm:validation"));
        }
    }

    private void validateTopLevelAnnotations(JsonNode schema) {
        Iterator<String> fieldNames = schema.fieldNames();
        while (fieldNames.hasNext()) {
            String name = fieldNames.next();
            if (name.startsWith("$gsm:") && !KNOWN_ANNOTATIONS.contains(name)) {
                throw new IllegalArgumentException(
                        "Unknown $gsm:* annotation '" + name + "' — sealed annotation vocabulary");
            }
        }
    }

    private void checkUnknownAnnotations(JsonNode propSchema, String propName) {
        Iterator<String> fieldNames = propSchema.fieldNames();
        while (fieldNames.hasNext()) {
            String name = fieldNames.next();
            if (name.startsWith("$gsm:")) {
                if (!KNOWN_ANNOTATIONS.contains(name)) {
                    throw new IllegalArgumentException(
                            "Unknown $gsm:* annotation '" + name + "' on property '"
                                    + propName + "' — sealed annotation vocabulary");
                }
                if (TOP_LEVEL_ANNOTATIONS.contains(name)) {
                    throw new IllegalArgumentException(
                            "Annotation '" + name + "' is top-level only, not valid on property '"
                                    + propName + "'");
                }
            }
        }
    }

    private static void validateQueryableType(JsonNode propSchema, String propName) {
        String type = propSchema.has("type") ? propSchema.get("type").asText() : null;
        if (type == null) {
            throw new IllegalArgumentException(
                    "$gsm:queryable on property '" + propName
                            + "' requires an explicit type (string, number, integer, boolean, or array of scalars)");
        }

        if ("array".equals(type)) {
            JsonNode items = propSchema.get("items");
            if (items == null || !items.has("type")
                    || !INDEXABLE_TYPES.contains(items.get("type").asText())) {
                throw new IllegalArgumentException(
                        "$gsm:queryable on array property '" + propName
                                + "' requires items of indexable type (string, number, integer, boolean)");
            }
        } else if (!INDEXABLE_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "$gsm:queryable on property '" + propName + "' with type '" + type
                            + "' — type must be string, number, integer, boolean, or array of scalars");
        }
    }

    // ========================================================================
    // $gsm:identityBound set immutability (Archetype authoring time)
    // ========================================================================

    private void validateIdentityBoundSetImmutability(UUID definitionId, Set<String> currentSet) {
        if (definitionId == null || currentSet.isEmpty()) {
            return;
        }

        List<ArchetypeEntity> existing = archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(definitionId);
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
            throw new IllegalArgumentException(
                    "$gsm:identityBound set immutability violation: first Ascription had identity-bound fields "
                            + firstIdentityBound + " but new Ascription declares " + currentSet
                            + ". Changing the identity-bound set requires a new Archetype Definition.");
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
    // $gsm:validation expression validation (Archetype authoring time)
    // ========================================================================

    void validateValidationExpressions(JsonNode validationNode) {
        if (!validationNode.isArray()) {
            throw new IllegalArgumentException(
                    "$gsm:validation must be an array of CEL expression strings");
        }
        if (validationNode.isEmpty()) {
            return;
        }
        for (int i = 0; i < validationNode.size(); i++) {
            JsonNode exprNode = validationNode.get(i);
            if (!exprNode.isTextual()) {
                throw new IllegalArgumentException(
                        "$gsm:validation[" + i + "] must be a string, got " + exprNode.getNodeType());
            }
            String expr = exprNode.asText();
            if (expr.isBlank()) {
                throw new IllegalArgumentException("$gsm:validation[" + i + "] must not be blank");
            }
            CelValidationResult result = validationCompiler.parse(expr);
            if (result.hasError()) {
                throw new IllegalArgumentException(
                        "$gsm:validation[" + i + "] CEL parse error: " + result.getErrorString());
            }
            try {
                result.getAst();
            } catch (CelValidationException e) {
                throw new IllegalArgumentException(
                        "$gsm:validation[" + i + "] CEL validation error: " + e.getMessage(), e);
            }
        }
    }

    // ========================================================================
    // $gsm:dataProtection validation (Archetype authoring time)
    // ========================================================================

    private static void validateDataProtection(JsonNode dpNode, String propName, boolean isQueryable) {
        if (!dpNode.isObject()) {
            throw new IllegalArgumentException(
                    "$gsm:dataProtection on property '" + propName + "' must be an object");
        }

        // Check encryption unsupported in any phase
        checkEncryptionUnsupported(dpNode.get("atRest"), propName, "atRest");
        checkEncryptionUnsupported(dpNode.get("inTransit"), propName, "inTransit");

        // Cross-phase mutual exclusion (GSM §8)
        if (dpNode.has("atRest") && dpNode.has("inTransit")) {
            JsonNode atRest = dpNode.get("atRest");
            JsonNode inTransit = dpNode.get("inTransit");

            if (atRest.has("suppression")) {
                throw new IllegalArgumentException(
                        "$gsm:dataProtection on property '" + propName
                                + "': atRest.suppression requires inTransit to be absent "
                                + "(suppressed data does not exist at rest)");
            }

            if (atRest.has("hash") && !inTransit.has("suppression")) {
                throw new IllegalArgumentException(
                        "$gsm:dataProtection on property '" + propName
                                + "': atRest.hash constrains inTransit to suppression or absent "
                                + "(hashed data cannot be meaningfully re-transformed)");
            }
        }

        // $gsm:queryable + atRest.encryption mutual exclusion
        // (subsumed by encryption unsupported check above, but kept for when
        // encryption is implemented)
        if (isQueryable && dpNode.has("atRest")) {
            JsonNode atRest = dpNode.get("atRest");
            if (atRest.has("encryption")) {
                throw new IllegalArgumentException(
                        "$gsm:dataProtection on property '" + propName
                                + "': $gsm:queryable + atRest.encryption is forbidden "
                                + "(ciphertext is not indexable)");
            }
        }
    }

    private static void checkEncryptionUnsupported(JsonNode phaseNode, String propName, String phase) {
        if (phaseNode != null && phaseNode.has("encryption")) {
            throw new UnsupportedOperationException(
                    "$gsm:dataProtection on property '" + propName
                            + "': " + phase + ".encryption is not yet supported");
        }
    }
}
