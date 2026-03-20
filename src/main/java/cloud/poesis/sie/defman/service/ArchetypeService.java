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
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import cloud.poesis.sie.defman.type.RuleType;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import jakarta.persistence.EntityManager;

/**
 * GSM Archetype ascription service.
 *
 * <p>
 * Manages lifecycle and persistence of {@link ArchetypeEntity} ascriptions
 * including allOf chain validation, {@code $gsm:*} annotation well-formedness,
 * subject type resolution, and vocabulary-driven index provisioning.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class ArchetypeService extends AbstractAscriptionService {

    /** Maps schema title to DefinitionSubjectType for base archetypes. */
    private static final Map<String, DefinitionSubjectType> SCHEMA_TITLE_TO_SUBJECT_TYPE = Map.of(
            "Archetype", DefinitionSubjectType.ARCHETYPE,
            "StructureArchetype", DefinitionSubjectType.STRUCTURE,
            "MechanismArchetype", DefinitionSubjectType.MECHANISM,
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
            "Archetype", "EffectorArchetype",
            "ReceptorArchetype", "DirectiveArchetype", "NormArchetype");

    private static final Pattern GSM_URI_PATTERN = Pattern.compile("^gsm://archetypes/([^/]+)/v\\d+$");

    // ======================================================================
    // $gsm:* annotation constants
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

    /**
     * Constructs the Archetype service with its required dependencies.
     *
     * @param archetypeRepo         the archetype repository
     * @param jdbcTemplate          the JDBC template for index provisioning
     * @param definitionService     the definition service
     * @param transitionService     the status transition service
     * @param ascriptionRepository  the base ascription repository
     * @param entityManager         the JPA entity manager
     * @param dataProtectionService the data protection service
     */
    public ArchetypeService(
            ArchetypeRepository archetypeRepo,
            JdbcTemplate jdbcTemplate,
            DefinitionService definitionService,
            AscriptionStatusTransitionService transitionService,
            AscriptionRepository ascriptionRepository,
            EntityManager entityManager,
            DataProtectionService dataProtectionService) {
        super(definitionService, transitionService, ascriptionRepository, entityManager, dataProtectionService);
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
            throw RuleViolationException.of(RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                    "Archetype statement must be a JSON object", "field", "statement");
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
     *
     * @param archetypeId the archetype UUID
     * @return the resolved archetype with its derived subject type
     * @throws ResourceNotFoundException if no archetype exists with the given id
     * @throws RuleViolationException    if the archetype has no title or is
     *                                   rootless
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
        return archetypeRepo.findById(id)
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
     * <p>
     * Part of the explicit-fetch design (see README.md
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
     *
     * @param title the Archetype's schema title
     * @return the archetype entity, or {@code null} if not found
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
            throw RuleViolationException.of(RuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
                    "Cannot derive subject type: archetype has no title: " + archetype.getId(),
                    "archetypeId", archetype.getId());
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
            throw RuleViolationException.of(RuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
                    "Rootless archetype '" + title
                            + "' cannot be used as archetype_id — no structural base (allOf chain required)",
                    "title", title);
        }

        Set<String> resolvedBases = new HashSet<>();
        Set<String> visited = new HashSet<>();
        visited.add(title);
        walkAllOfChain(allOf, resolvedBases, visited, true);

        if (resolvedBases.isEmpty()) {
            throw RuleViolationException.of(RuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
                    "Rootless archetype '" + title
                            + "' cannot be used as archetype_id — allOf chain does not converge to any GSM base",
                    "title", title);
        }
        // resolvedBases.size() > 1 is already rejected by validateAllOfChain at
        // authoring time;
        // defensive check here for safety.
        if (resolvedBases.size() > 1) {
            throw RuleViolationException.of(RuleType.ARCHETYPE_ALLOF_CHAIN_EXCLUSIVE_BASE_CONVERGENCE,
                    "Archetype '" + title + "' allOf chain converges to multiple GSM bases: " + resolvedBases,
                    "title", title, "resolvedBases", resolvedBases);
        }

        String baseName = resolvedBases.iterator().next();
        type = SCHEMA_TITLE_TO_SUBJECT_TYPE.get(baseName);
        if (type == null) {
            throw RuleViolationException.of(RuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
                    "Cannot map structural base '" + baseName + "' to a DefinitionSubjectType",
                    "baseName", baseName);
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
            throw RuleViolationException.of(RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                    "Archetype title must not be null or empty", "field", "title");
        }
        String title = stmt.get("title").asText();
        if (title.isBlank()) {
            throw RuleViolationException.of(RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                    "Archetype title must not be empty", "field", "title");
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
                throw RuleViolationException.of(RuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
                        "Archetype title '" + title + "' already in effect",
                        "field", "title", "value", title,
                        "conflictingAscriptionId", a.getId(),
                        "conflictingDefinitionId", a.getDefinition().getId());
            }
        }
    }

    // ---- Lifecycle hooks ----

    @Override
    public void onActivation(AscriptionEntity entity) {
        if (entity instanceof ArchetypeEntity archetypeEntity) {
            // GSM §5: strict allOf chain resolution — all intermediaries must be in-effect.
            validateAllOfChain(archetypeEntity.getStatement(), true);
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
        validateAllOfChain(schema, false);
    }

    void validateAllOfChain(JsonNode schema, boolean strict) {
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

        walkAllOfChain(allOf, resolvedBases, visited, strict);

        // 0 bases → rootless archetype with allOf (e.g., facet extending another
        // facet).
        // 1 base → structural archetype (valid typing archetype for archetype_id).
        // 2+ bases → divergent structural bases (ambiguous subject type → rejected).
        if (resolvedBases.size() > 1) {
            throw RuleViolationException.of(RuleType.ARCHETYPE_ALLOF_CHAIN_EXCLUSIVE_BASE_CONVERGENCE,
                    "Archetype schema allOf chain converges to multiple GSM base archetypes: "
                            + resolvedBases,
                    "resolvedBases", resolvedBases);
        }
    }

    private void walkAllOfChain(JsonNode allOf, Set<String> resolvedBases, Set<String> visited, boolean strict) {
        for (JsonNode entry : allOf) {
            if (!entry.has("$ref")) {
                continue;
            }

            String ref = entry.get("$ref").asText();
            String refTitle = extractTitleFromRef(ref);

            if (refTitle == null) {
                // Format error — always rejected (authoring + activation).
                throw RuleViolationException.of(RuleType.ARCHETYPE_ALLOF_CHAIN_EXCLUSIVE_BASE_CONVERGENCE,
                        "Cannot resolve allOf $ref '" + ref
                                + "': must use gsm://archetypes/{title}/v{version} convention",
                        "ref", ref);
            }

            if (!visited.add(refTitle)) {
                throw RuleViolationException.of(RuleType.ARCHETYPE_ALLOF_CHAIN_ACYCLICITY,
                        "Cycle detected in allOf chain: '" + refTitle + "' already visited",
                        "refTitle", refTitle);
            }

            if (GSM_BASE_TITLES.contains(refTitle)) {
                if (isSealedBaseArchetype(refTitle)) {
                    throw RuleViolationException.of(RuleType.ARCHETYPE_ALLOF_SEAL,
                            "Archetype schema allOf references sealed schema '"
                                    + refTitle + "' — tenant-defined archetypes MUST NOT extend sealed schemas",
                            "sealedArchetype", refTitle);
                }
                resolvedBases.add(refTitle);
            } else {
                JsonNode intermediateSchema = resolveArchetypeSchema(refTitle);
                if (intermediateSchema == null) {
                    if (strict) {
                        // Activation-time: intermediary MUST be resolvable.
                        throw RuleViolationException.of(RuleType.ARCHETYPE_ALLOF_CHAIN_EXCLUSIVE_BASE_CONVERGENCE,
                                "Cannot resolve intermediary archetype '" + refTitle
                                        + "' referenced via allOf — no in-effect Archetype with this title",
                                "refTitle", refTitle);
                    }
                    // Authoring-time: intermediary may not be in-effect yet — warn and skip.
                    LOG.warn("allOf $ref '{}' not resolvable at authoring time — will be validated at activation",
                            refTitle);
                    continue;
                }

                if (intermediateSchema.has("$gsm:sealed")
                        && intermediateSchema.get("$gsm:sealed").asBoolean()) {
                    throw RuleViolationException.of(RuleType.ARCHETYPE_ALLOF_SEAL,
                            "Archetype schema allOf references sealed schema '"
                                    + refTitle + "' — tenant-defined archetypes MUST NOT extend sealed schemas",
                            "sealedArchetype", refTitle);
                }

                JsonNode intermediateAllOf = intermediateSchema.get("allOf");
                if (intermediateAllOf != null && intermediateAllOf.isArray()) {
                    walkAllOfChain(intermediateAllOf, resolvedBases, visited, strict);
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
    // Archetype $gsm:* annotation validation
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
            throw RuleViolationException.of(RuleType.ARCHETYPE_ANNOTATION_QUERYABLE,
                    "Too many $gsm:queryable properties (" + queryableCount
                            + "): maximum allowed is " + DEFAULT_MAX_QUERYABLE,
                    "annotation", "$gsm:queryable", "count", queryableCount, "max", DEFAULT_MAX_QUERYABLE);
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
                throw RuleViolationException.of(RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                        "Unknown $gsm:* annotation '" + name + "' — sealed annotation vocabulary",
                        "annotation", name);
            }
        }
    }

    private void checkUnknownAnnotations(JsonNode propSchema, String propName) {
        Iterator<String> fieldNames = propSchema.fieldNames();
        while (fieldNames.hasNext()) {
            String name = fieldNames.next();
            if (name.startsWith("$gsm:")) {
                if (!KNOWN_ANNOTATIONS.contains(name)) {
                    throw RuleViolationException.of(RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                            "Unknown $gsm:* annotation '" + name + "' on property '"
                                    + propName + "' — sealed annotation vocabulary",
                            "annotation", name, "property", propName);
                }
                if (TOP_LEVEL_ANNOTATIONS.contains(name)) {
                    throw RuleViolationException.of(RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                            "Annotation '" + name + "' is top-level only, not valid on property '"
                                    + propName + "'",
                            "annotation", name, "property", propName);
                }
            }
        }
    }

    private static void validateQueryableType(JsonNode propSchema, String propName) {
        String type = propSchema.has("type") ? propSchema.get("type").asText() : null;
        if (type == null) {
            throw RuleViolationException.of(RuleType.ARCHETYPE_ANNOTATION_QUERYABLE,
                    "$gsm:queryable on property '" + propName
                            + "' requires an explicit type (string, number, integer, boolean, or array of scalars)",
                    "annotation", "$gsm:queryable", "property", propName);
        }

        if ("array".equals(type)) {
            JsonNode items = propSchema.get("items");
            if (items == null || !items.has("type")
                    || !INDEXABLE_TYPES.contains(items.get("type").asText())) {
                throw RuleViolationException.of(RuleType.ARCHETYPE_ANNOTATION_QUERYABLE,
                        "$gsm:queryable on array property '" + propName
                                + "' requires items of indexable type (string, number, integer, boolean)",
                        "annotation", "$gsm:queryable", "property", propName);
            }
        } else if (!INDEXABLE_TYPES.contains(type)) {
            throw RuleViolationException.of(RuleType.ARCHETYPE_ANNOTATION_QUERYABLE,
                    "$gsm:queryable on property '" + propName + "' with type '" + type
                            + "' — type must be string, number, integer, boolean, or array of scalars",
                    "annotation", "$gsm:queryable", "property", propName, "type", type);
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
            throw RuleViolationException.of(RuleType.ARCHETYPE_ANNOTATION_IDENTITY_BOUND_SET_IMMUTABILITY,
                    "$gsm:identityBound set immutability violation: first Ascription had identity-bound fields "
                            + firstIdentityBound + " but new Ascription declares " + currentSet
                            + ". Changing the identity-bound set requires a new Archetype Definition.",
                    "annotation", "$gsm:identityBound",
                    "expectedFields", firstIdentityBound, "actualFields", currentSet);
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
            throw RuleViolationException.of(RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                    "$gsm:validation must be an array of CEL expression strings",
                    "annotation", "$gsm:validation");
        }
        if (validationNode.isEmpty()) {
            return;
        }
        for (int i = 0; i < validationNode.size(); i++) {
            JsonNode exprNode = validationNode.get(i);
            if (!exprNode.isTextual()) {
                throw RuleViolationException.of(RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                        "$gsm:validation[" + i + "] must be a string, got " + exprNode.getNodeType(),
                        "annotation", "$gsm:validation", "index", i);
            }
            String expr = exprNode.asText();
            if (expr.isBlank()) {
                throw RuleViolationException.of(RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                        "$gsm:validation[" + i + "] must not be blank",
                        "annotation", "$gsm:validation", "index", i);
            }
            CelValidationResult result = validationCompiler.parse(expr);
            if (result.hasError()) {
                throw RuleViolationException.of(RuleType.ARCHETYPE_VALIDATION_CEL_PARSING,
                        "$gsm:validation[" + i + "] CEL parse error: " + result.getErrorString(),
                        "annotation", "$gsm:validation", "index", i, "expression", expr);
            }
            CelAbstractSyntaxTree ast;
            try {
                ast = result.getAst();
            } catch (CelValidationException e) {
                throw RuleViolationException.of(RuleType.ARCHETYPE_VALIDATION_CEL_CONSTRUCT_BLACKLIST,
                        "$gsm:validation[" + i + "] CEL validation error: " + e.getMessage(), e,
                        "annotation", "$gsm:validation", "index", i, "expression", expr);
            }
            // F5: Root binding — all idents must be "this"
            CelExpr rootExpr = ast.getExpr();
            Set<String> rootIdents = new HashSet<>();
            collectRootIdents(rootExpr, rootIdents);
            rootIdents.remove("this");
            if (!rootIdents.isEmpty()) {
                throw RuleViolationException.of(RuleType.ARCHETYPE_VALIDATION_CEL_THIS_ROOT_BINDING,
                        "$gsm:validation[" + i + "] must use 'this' as root — unbound identifiers: "
                                + rootIdents,
                        "annotation", "$gsm:validation", "index", i, "expression", expr,
                        "unboundIdents", rootIdents.toString());
            }
            // F5: Boolean result — top-level must evaluate to bool
            validateCelBooleanResult(rootExpr, i, expr);
        }
    }

    private static void collectRootIdents(CelExpr expr, Set<String> idents) {
        CelExpr.ExprKind kind = expr.exprKind();
        switch (kind.getKind()) {
            case IDENT -> idents.add(kind.ident().name());
            case SELECT -> collectRootIdents(kind.select().operand(), idents);
            case CALL -> {
                CelExpr.CelCall call = kind.call();
                call.target().ifPresent(t -> collectRootIdents(t, idents));
                for (CelExpr arg : call.args()) {
                    collectRootIdents(arg, idents);
                }
            }
            case LIST -> {
                for (CelExpr el : kind.list().elements()) {
                    collectRootIdents(el, idents);
                }
            }
            default -> {
                /* CONSTANT — no ident */ }
        }
    }

    private static final Set<String> CEL_BOOLEAN_OPS = Set.of(
            "_==_", "_!=_", "_<_", "_<=_", "_>_", "_>=_",
            "_&&_", "_||_", "!_", "_!_", "@in",
            "matches", "startsWith", "endsWith", "contains",
            "has", "exists", "all", "exists_one");
    private static final Set<String> CEL_ARITHMETIC_OPS = Set.of(
            "_+_", "_-_", "_*_", "_/_", "_%_");

    private static void validateCelBooleanResult(CelExpr root, int index, String expr) {
        CelExpr.ExprKind kind = root.exprKind();
        switch (kind.getKind()) {
            case CALL -> {
                String fn = kind.call().function();
                if ("_?_:_".equals(fn))
                    return; // ternary — accept (result type depends on branches)
                if (CEL_BOOLEAN_OPS.contains(fn))
                    return; // known boolean-producing operation — accept
                if (CEL_ARITHMETIC_OPS.contains(fn)) {
                    throw RuleViolationException.of(RuleType.ARCHETYPE_VALIDATION_CEL_BOOLEAN_RESULT,
                            "$gsm:validation[" + index + "] top-level is arithmetic ('"
                                    + fn + "') — must evaluate to bool",
                            "annotation", "$gsm:validation", "index", index, "expression", expr);
                }
                throw RuleViolationException.of(RuleType.ARCHETYPE_VALIDATION_CEL_BOOLEAN_RESULT,
                        "$gsm:validation[" + index + "] top-level function '" + fn
                                + "' is not a known boolean-producing operation — must evaluate to bool",
                        "annotation", "$gsm:validation", "index", index, "expression", expr);
            }
            case CONSTANT -> {
                CelConstant c = kind.constant();
                if (c.getKind() != CelConstant.Kind.BOOLEAN_VALUE) {
                    throw RuleViolationException.of(RuleType.ARCHETYPE_VALIDATION_CEL_BOOLEAN_RESULT,
                            "$gsm:validation[" + index
                                    + "] top-level is a non-boolean constant — must evaluate to bool",
                            "annotation", "$gsm:validation", "index", index, "expression", expr);
                }
            }
            default -> {
                /* IDENT, SELECT, LIST — DYN, accept */ }
        }
    }

    // ========================================================================
    // $gsm:dataProtection validation (Archetype authoring time)
    // ========================================================================

    private static void validateDataProtection(JsonNode dpNode, String propName, boolean isQueryable) {
        if (!dpNode.isObject()) {
            throw RuleViolationException.of(RuleType.ARCHETYPE_ANNOTATION_DATA_PROTECTION,
                    "$gsm:dataProtection on property '" + propName + "' must be an object",
                    "annotation", "$gsm:dataProtection", "property", propName);
        }

        // Check encryption unsupported in any phase
        checkEncryptionUnsupported(dpNode.get("atRest"), propName, "atRest");
        checkEncryptionUnsupported(dpNode.get("inTransit"), propName, "inTransit");

        // Cross-phase mutual exclusion (GSM §8)
        if (dpNode.has("atRest") && dpNode.has("inTransit")) {
            JsonNode atRest = dpNode.get("atRest");
            JsonNode inTransit = dpNode.get("inTransit");

            if (atRest.has("suppression")) {
                throw RuleViolationException.of(RuleType.ARCHETYPE_ANNOTATION_DATA_PROTECTION,
                        "$gsm:dataProtection on property '" + propName
                                + "': atRest.suppression requires inTransit to be absent "
                                + "(suppressed data does not exist at rest)",
                        "annotation", "$gsm:dataProtection", "property", propName);
            }

            if (atRest.has("hash") && !inTransit.has("suppression")) {
                throw RuleViolationException.of(RuleType.ARCHETYPE_ANNOTATION_DATA_PROTECTION,
                        "$gsm:dataProtection on property '" + propName
                                + "': atRest.hash constrains inTransit to suppression or absent "
                                + "(hashed data cannot be meaningfully re-transformed)",
                        "annotation", "$gsm:dataProtection", "property", propName);
            }
        }

        // $gsm:queryable + atRest.encryption mutual exclusion
        // (subsumed by encryption unsupported check above, but kept for when
        // encryption is implemented)
        if (isQueryable && dpNode.has("atRest")) {
            JsonNode atRest = dpNode.get("atRest");
            if (atRest.has("encryption")) {
                throw RuleViolationException.of(RuleType.ARCHETYPE_ANNOTATION_DATA_PROTECTION,
                        "$gsm:dataProtection on property '" + propName
                                + "': $gsm:queryable + atRest.encryption is forbidden "
                                + "(ciphertext is not indexable)",
                        "annotation", "$gsm:dataProtection", "property", propName);
            }
        }
    }

    private static void checkEncryptionUnsupported(JsonNode phaseNode, String propName, String phase) {
        // Encryption not yet implemented — silently ignored
    }
}
