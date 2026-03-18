package cloud.poesis.sie.defman.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.GsmRuleViolationException;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.GsmRuleType;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import jakarta.persistence.EntityManager;

/**
 * Base class for all GSM ascription services. Provides:
 * <ul>
 * <li>Subtype identity ({@link #getSubjectType()})</li>
 * <li>Entity CRUD ({@link #buildEntity}, {@link #save}, find methods)</li>
 * <li>Create template method ({@link #create})</li>
 * <li>Lifecycle descriptors (referee references, cascade roles, identity-bound
 * values)</li>
 * <li>Lifecycle hooks ({@link #onActivation}, {@link #onDeactivation})</li>
 * <li>Statement JSON extraction utilities ({@link #extractRequiredUuid},
 * {@link #extractUuidList})</li>
 * </ul>
 */
public abstract class AbstractAscriptionService {

    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /**
     * CEL compiler + runtime for $gsm:validation enforcement at Ascription
     * authoring.
     */
    private final CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder()
            .addVar("this", SimpleType.DYN)
            .build();
    private final CelRuntime celRuntime = CelRuntimeFactory.standardCelRuntimeBuilder().build();
    private final ObjectMapper celMapper = new ObjectMapper();

    // Shared dependencies — field-injected to avoid constructor bloat across 9
    // subclasses
    @Autowired
    private DefinitionService definitionService;
    @Autowired
    private AscriptionStatusTransitionService transitionService;
    @Autowired
    private AscriptionRepository ascriptionRepository;
    @Autowired
    private EntityManager entityManager;

    // Referee precondition for [*]→DRAFT creation
    private static final Set<AscriptionStatusType> CREATION_REFEREE_ALLOWED = EnumSet.of(
            AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED,
            AscriptionStatusType.APPROVED, AscriptionStatusType.ACTIVE);

    // In-effect statuses for $gsm:unique enforcement
    private static final List<AscriptionStatusType> GSM_IN_EFFECT = List.of(
            AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);

    // ======================================================================
    // Subtype identity
    // ======================================================================

    public abstract DefinitionSubjectType getSubjectType();

    // ======================================================================
    // Entity CRUD (abstract)
    // ======================================================================

    public abstract AscriptionEntity buildEntity(
            DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement);

    public abstract AscriptionEntity save(AscriptionEntity entity);

    public abstract Page<? extends AscriptionEntity> findAll(Pageable pageable);

    public abstract Page<? extends AscriptionEntity> findAllByStatus(
            AscriptionStatusType status, Pageable pageable);

    public abstract List<? extends AscriptionEntity> findAllByDefinitionId(UUID definitionId);

    public abstract List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    /**
     * Returns Ascription history for a Definition, with definition and archetype
     * eagerly fetched via {@code @EntityGraph} on the repository method.
     */
    @Transactional(value = "transactionManager", readOnly = true)
    public List<? extends AscriptionEntity> getHistory(UUID definitionId) {
        return findAllByDefinitionId(definitionId);
    }

    // ======================================================================
    // CREATE (template method — do not override)
    // ======================================================================

    @Transactional("transactionManager")
    public AscriptionEntity create(ArchetypeEntity archetypeRef, JsonNode statement,
            UUID definitionId) {
        // 1. Validate statement against archetype schema
        validateStatement(statement, archetypeRef);

        // 2. Resolve or create Definition
        DefinitionEntity definition = definitionService.resolveOrCreate(definitionId, getSubjectType());

        // 3. Enforce $gsm:* annotations on statement
        enforceGsmAnnotations(statement, archetypeRef, definition.getId());

        // 4. Build subtype-specific entity
        AscriptionEntity entity = buildEntity(definition, archetypeRef, statement);

        // 5. Validate identity-bound invariant
        validateIdentityBound(entity);

        // 6. Validate creation referee preconditions
        validateCreationPreconditions(entity);

        // 7. Persist
        AscriptionEntity saved = save(entity);

        // 8. Record initial DRAFT transition
        transitionService.recordTransition(saved, null, AscriptionStatusType.DRAFT);

        // 9. Refresh to pick up DB-trigger-assigned fields (status, timestamp)
        entityManager.refresh(saved);

        // 10. Post-creation hook (e.g., auto-derivation of ports)
        afterCreate(saved);

        return saved;
    }

    // ======================================================================
    // Inner types (lifecycle descriptors)
    // ======================================================================

    /**
     * A reference edge: the entity being referenced (referee → reference).
     * Used by the lifecycle service to check referee preconditions.
     */
    public record RefereeReference(AscriptionEntity reference, String label) {
    }

    // ======================================================================
    // Lifecycle descriptor methods (overridable)
    // ======================================================================

    public List<RefereeReference> getRefereeReferences(AscriptionEntity entity) {
        return List.of();
    }

    public Map<DefinitionSubjectType, AscriptionStatusTransitionCascadeType> getCascadeTargetRoles() {
        return Map.of();
    }

    public List<? extends AscriptionEntity> findCascadeTargetsFrom(
            DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
        return List.of();
    }

    public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
        return Map.of();
    }

    public void validateActivationUniqueness(AscriptionEntity entity) {
        // Default: no activation uniqueness checks
    }

    // ======================================================================
    // Lifecycle hooks (called by AscriptionLifecycleService)
    // ======================================================================

    /**
     * Hook called when an entity transitions to ACTIVE.
     * Override in concrete services for subtype-specific activation logic
     * (e.g., index provisioning for Archetypes).
     */
    public void onActivation(AscriptionEntity entity) {
        // Default: no-op
    }

    /**
     * Hook called when an entity leaves in-effect status (ACTIVE/DEPRECATED).
     * Override in concrete services for subtype-specific deactivation logic
     * (e.g., index deprovisioning for Archetypes).
     */
    public void onDeactivation(AscriptionEntity entity) {
        // Default: no-op
    }

    /**
     * Hook called after an entity is created and persisted.
     * Override in concrete services for subtype-specific post-creation logic
     * (e.g., auto-derivation of Effectors/Receptors for generative Mechanisms).
     */
    protected void afterCreate(AscriptionEntity saved) {
        // Default: no-op
    }

    // Protected service accessors for subtype post-creation logic
    protected DefinitionService getDefinitionService() {
        return definitionService;
    }

    protected AscriptionStatusTransitionService getTransitionService() {
        return transitionService;
    }

    protected EntityManager getEntityManager() {
        return entityManager;
    }

    // ======================================================================
    // Statement JSON extraction utilities (protected)
    // ======================================================================

    protected UUID extractRequiredUuid(JsonNode statement, String field) {
        JsonNode node = statement.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw GsmRuleViolationException.of(GsmRuleType.ASCRIPTION_PROPERTY_REQUIREMENT,
                    "Required field '" + field + "' missing in statement payload",
                    "field", field);
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            throw GsmRuleViolationException.of(GsmRuleType.ASCRIPTION_PROPERTY_FORMATTING,
                    "Invalid UUID for field '" + field + "': " + node.asText(),
                    "field", field, "value", node.asText());
        }
    }

    protected List<UUID> extractUuidList(JsonNode statement, String field) {
        JsonNode node = statement.get(field);
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        List<UUID> result = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            try {
                result.add(UUID.fromString(element.asText()));
            } catch (IllegalArgumentException e) {
                throw GsmRuleViolationException.of(GsmRuleType.ASCRIPTION_PROPERTY_FORMATTING,
                        "Invalid UUID in '" + field + "': " + element.asText(),
                        "field", field, "value", element.asText());
            }
        }
        return result;
    }

    // ======================================================================
    // Identity-bound validation (private — called from create())
    // ======================================================================

    void validateIdentityBound(AscriptionEntity entity) {
        Map<String, Object> newValues = getIdentityBoundValues(entity);
        if (newValues.isEmpty()) {
            return;
        }

        UUID definitionId = entity.getDefinition().getId();
        List<? extends AscriptionEntity> existing = findAllByDefinitionId(definitionId);
        if (existing.isEmpty()) {
            return;
        }

        // Compare against the first (earliest) ascription's identity-bound values
        AscriptionEntity first = existing.getLast(); // ordered by timestamp DESC, so last = earliest
        Map<String, Object> firstValues = getIdentityBoundValues(first);

        for (var entry : newValues.entrySet()) {
            String field = entry.getKey();
            Object newVal = entry.getValue();
            Object firstVal = firstValues.get(field);
            if (!Objects.equals(newVal, firstVal)) {
                throw GsmRuleViolationException.of(GsmRuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION,
                        "Identity-bound field '" + field + "' changed: expected '" + firstVal
                                + "' but got '" + newVal + "'",
                        "field", field, "definitionId", definitionId,
                        "expectedValue", String.valueOf(firstVal),
                        "actualValue", String.valueOf(newVal));
            }
        }
    }

    // ======================================================================
    // Creation referee preconditions (private — called from create())
    // ======================================================================

    void validateCreationPreconditions(AscriptionEntity entity) {
        List<RefereeReference> refs = getRefereeReferences(entity);
        if (refs.isEmpty()) {
            return;
        }

        for (RefereeReference ref : refs) {
            AscriptionStatusType refStatus = ref.reference().getStatus();
            if (!CREATION_REFEREE_ALLOWED.contains(refStatus)) {
                throw GsmRuleViolationException.of(
                        GsmRuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
                        "Referee '" + ref.label() + "' (" + ref.reference().getId()
                                + ") is " + refStatus.name() + "; creation requires one of "
                                + CREATION_REFEREE_ALLOWED.stream().map(Enum::name).collect(Collectors.toSet()),
                        "fromStatus", null, "toStatus", AscriptionStatusType.DRAFT.name(),
                        "refereeLabel", ref.label(), "refereeId", ref.reference().getId(),
                        "refereeStatus", refStatus.name(),
                        "requiredStatuses",
                        CREATION_REFEREE_ALLOWED.stream().map(Enum::name).collect(Collectors.toSet()));
            }
        }
    }

    // ======================================================================
    // Statement validation (inlined from StatementValidator)
    // ======================================================================

    void validateStatement(JsonNode statement, ArchetypeEntity archetype) {
        JsonNode archetypeStatement = archetype.getStatement();

        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        JsonSchema schema = schemaFactory.getSchema(archetypeStatement, config);
        Set<ValidationMessage> errors = schema.validate(statement);

        if (!errors.isEmpty()) {
            List<String> messages = errors.stream().map(ValidationMessage::getMessage).toList();
            throw GsmRuleViolationException.of(GsmRuleType.ASCRIPTION_PROPERTY_REQUIREMENT,
                    "Statement validation failed against archetype " + archetype.getDefinition().getId()
                            + ": " + messages,
                    "archetypeDefinitionId", archetype.getDefinition().getId(),
                    "violations", messages);
        }
    }

    // ======================================================================
    // $gsm:* annotation enforcement on Ascription (inlined from
    // GsmAnnotationValidator)
    // ======================================================================

    void enforceGsmAnnotations(JsonNode statement, ArchetypeEntity archetype, UUID definitionId) {
        JsonNode archetypeStmt = archetype.getStatement();
        if (archetypeStmt == null) {
            return;
        }

        JsonNode properties = archetypeStmt.get("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }

        for (Map.Entry<String, JsonNode> entry : properties.properties()) {
            String propName = entry.getKey();
            JsonNode propSchema = entry.getValue();

            if (!statement.has(propName)) {
                continue;
            }

            JsonNode value = statement.get(propName);

            if (hasGsmAnnotation(propSchema, "$gsm:unique")) {
                enforceUnique(propName, value, archetype, definitionId);
            }

            if (propSchema.has("$gsm:dataProtection")) {
                applyDataProtectionAtRest(propSchema.get("$gsm:dataProtection"),
                        propName, (ObjectNode) statement);
            }
        }

        // GSM §8: $gsm:validation — evaluate top-level CEL constraints against
        // statement
        if (archetypeStmt.has("$gsm:validation")) {
            evaluateValidation(archetypeStmt.get("$gsm:validation"), statement);
        }
    }

    private void enforceUnique(String propName, JsonNode value, ArchetypeEntity archetype,
            UUID definitionId) {
        List<AscriptionEntity> inEffect = ascriptionRepository
                .findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
                        archetype.getId(), GSM_IN_EFFECT, definitionId);

        String valueStr = value.isTextual() ? value.asText() : value.toString();
        for (AscriptionEntity existing : inEffect) {
            JsonNode existingStmt = existing.getStatement();
            if (existingStmt.has(propName)) {
                JsonNode existingVal = existingStmt.get(propName);
                String existingStr = existingVal.isTextual() ? existingVal.asText() : existingVal.toString();
                if (valueStr.equals(existingStr)) {
                    throw GsmRuleViolationException.of(GsmRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
                            propName + " '" + valueStr + "' duplicates ascription " + existing.getId()
                                    + " (definition " + existing.getDefinition().getId() + ")",
                            "field", propName, "value", valueStr,
                            "conflictingAscriptionId", existing.getId(),
                            "conflictingDefinitionId", existing.getDefinition().getId());
                }
            }
        }
    }

    private static boolean hasGsmAnnotation(JsonNode node, String annotation) {
        return node.has(annotation) && node.get(annotation).asBoolean(false);
    }

    // ======================================================================
    // $gsm:dataProtection atRest enforcement at Ascription authoring
    // ======================================================================

    static void applyDataProtectionAtRest(JsonNode dpNode, String propName, ObjectNode statement) {
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
            String hashed = computeHash(textValue, algorithm);
            statement.put(propName, hashed);
        }

        if (atRest.has("mask")) {
            JsonNode maskNode = atRest.get("mask");
            String masked = applyMask(textValue, maskNode);
            statement.put(propName, masked);
        }

        if (atRest.has("suppression")) {
            statement.remove(propName);
        }
    }

    public static String computeHash(String value, String algorithm) {
        try {
            String javaAlgorithm = algorithm.replace("SHA3-256", "SHA3-256")
                    .replace("SHA-256", "SHA-256")
                    .replace("SHA-512", "SHA-512");
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance(javaAlgorithm);
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw GsmRuleViolationException.of(GsmRuleType.ARCHETYPE_VOCABULARY_WELLFORMEDNESS,
                    "$gsm:dataProtection hash algorithm '" + algorithm + "' is not supported",
                    "keyword", "$gsm:dataProtection", "property", "hash.algorithm");
        }
    }

    public static String applyMask(String value, JsonNode maskNode) {
        String direction = maskNode.get("from").asText();
        JsonNode withNode = maskNode.get("with");
        char maskChar = withNode.has("character") ? withNode.get("character").asText().charAt(0) : '*';
        int occurrence = withNode.get("occurrence").asInt();

        if (value.length() <= occurrence) {
            // Mask the entire value — do not expose short sensitive data
            return String.valueOf(maskChar).repeat(value.length());
        }

        char[] chars = value.toCharArray();
        if ("LEFT".equals(direction)) {
            // Keep first 'occurrence' characters visible, mask the rest
            for (int i = occurrence; i < chars.length; i++) {
                chars[i] = maskChar;
            }
        } else {
            // RIGHT: keep last 'occurrence' characters visible, mask the rest
            for (int i = 0; i < chars.length - occurrence; i++) {
                chars[i] = maskChar;
            }
        }
        return new String(chars);
    }

    // ======================================================================
    // $gsm:validation evaluation at Ascription authoring
    // ======================================================================

    @SuppressWarnings("unchecked")
    void evaluateValidation(JsonNode validationNode, JsonNode statement) {
        if (!validationNode.isArray() || validationNode.isEmpty()) {
            return;
        }

        Map<String, Object> statementMap = celMapper.convertValue(statement, Map.class);

        for (int i = 0; i < validationNode.size(); i++) {
            JsonNode exprNode = validationNode.get(i);
            if (!exprNode.isTextual()) {
                continue;
            }
            String expr = exprNode.asText();
            if (expr.isBlank()) {
                continue;
            }

            try {
                CelValidationResult compileResult = celCompiler.compile(expr);
                if (compileResult.hasError()) {
                    throw GsmRuleViolationException.of(GsmRuleType.ARCHETYPE_VOCABULARY_WELLFORMEDNESS,
                            "$gsm:validation[" + i + "] CEL parse error: " + compileResult.getErrorString(),
                            "keyword", "$gsm:validation", "index", i);
                }
                CelAbstractSyntaxTree ast = compileResult.getAst();
                CelRuntime.Program program = celRuntime.createProgram(ast);
                Object result = program.eval(Map.of("this", statementMap));

                if (!(result instanceof Boolean b) || !b) {
                    throw GsmRuleViolationException.of(GsmRuleType.ARCHETYPE_VOCABULARY_WELLFORMEDNESS,
                            "$gsm:validation[" + i + "] constraint failed: expression '"
                                    + expr + "' evaluated to " + result,
                            "keyword", "$gsm:validation", "index", i, "expression", expr);
                }
            } catch (GsmRuleViolationException e) {
                throw e; // re-throw our own exceptions
            } catch (Exception e) {
                throw GsmRuleViolationException.of(GsmRuleType.ARCHETYPE_VOCABULARY_WELLFORMEDNESS,
                        "$gsm:validation[" + i + "] evaluation error for expression '"
                                + expr + "': " + e.getMessage(),
                        "keyword", "$gsm:validation", "index", i, "expression", expr);
            }
        }
    }
}
