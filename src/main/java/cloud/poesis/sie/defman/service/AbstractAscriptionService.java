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

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
import cloud.poesis.sie.defman.exception.GsmInternalException;
import cloud.poesis.sie.defman.exception.GsmRuleViolationException;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.type.AscriptionCascadeType;
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

    private static final Logger LOG = LoggerFactory.getLogger(AbstractAscriptionService.class);

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

    // Shared dependencies — constructor-injected into all subclasses
    private final DefinitionService definitionService;
    private final AscriptionStatusTransitionService transitionService;
    private final AscriptionRepository ascriptionRepository;
    private final EntityManager entityManager;
    private final DataProtectionService dataProtectionService;

    protected AbstractAscriptionService(
            DefinitionService definitionService,
            AscriptionStatusTransitionService transitionService,
            AscriptionRepository ascriptionRepository,
            EntityManager entityManager,
            DataProtectionService dataProtectionService) {
        this.definitionService = definitionService;
        this.transitionService = transitionService;
        this.ascriptionRepository = ascriptionRepository;
        this.entityManager = entityManager;
        this.dataProtectionService = dataProtectionService;
    }

    // Referee precondition for [*]→DRAFT creation
    private static final Set<AscriptionStatusType> CREATION_REFEREE_ALLOWED = EnumSet.of(
            AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED,
            AscriptionStatusType.APPROVED, AscriptionStatusType.ACTIVE);

    // In-effect statuses for $gsm:unique enforcement
    private static final List<AscriptionStatusType> GSM_IN_EFFECT = List.of(
            AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);

    // GSM base schema property sets for extensible subject types (sealed — these
    // match the GSM base archetype schemas and never change at runtime).
    // Used to classify validation errors as GSM-base vs tenant-extension.
    private static final Map<DefinitionSubjectType, Set<String>> GSM_BASE_PROPERTIES = Map.of(
            DefinitionSubjectType.STRUCTURE, Set.of("purpose"),
            DefinitionSubjectType.MECHANISM, Set.of("structure", "function", "rule"),
            DefinitionSubjectType.INTERACTION, Set.of("effector", "receptor"));

    // Known PostgreSQL constraint → GsmRuleType mapping (auto-generated FK names
    // and partial unique indexes)
    static final Map<String, GsmRuleType> CONSTRAINT_TO_RULE = Map.ofEntries(
            // Directive reference FKs
            Map.entry("directive_structure_id_fkey", GsmRuleType.DIRECTIVE_STRUCTURE_REFERENCE_INTEGRITY),
            Map.entry("directive_qualifier_id_fkey", GsmRuleType.DIRECTIVE_QUALIFIER_REFERENCE_INTEGRITY),
            Map.entry("directive_purpose_id_fkey", GsmRuleType.DIRECTIVE_PURPOSE_REFERENCE_INTEGRITY),
            // Norm reference FKs
            Map.entry("norm_structure_id_fkey", GsmRuleType.NORM_STRUCTURE_REFERENCE_INTEGRITY),
            Map.entry("norm_qualifier_id_fkey", GsmRuleType.NORM_QUALIFIER_REFERENCE_INTEGRITY),
            // Effector / Receptor reference FKs
            Map.entry("effector_mechanism_id_fkey", GsmRuleType.EFFECTOR_MECHANISM_REFERENCE_INTEGRITY),
            Map.entry("receptor_mechanism_id_fkey", GsmRuleType.RECEPTOR_MECHANISM_REFERENCE_INTEGRITY),
            // Interaction reference FKs
            Map.entry("interaction_effector_id_fkey", GsmRuleType.INTERACTION_EFFECTOR_REFERENCE_INTEGRITY),
            Map.entry("interaction_receptor_id_fkey", GsmRuleType.INTERACTION_RECEPTOR_REFERENCE_INTEGRITY),
            // Archetype self-typing FK
            Map.entry("archetype_typed_by_fk", GsmRuleType.ASCRIPTION_ARCHETYPE_REFERENCE_INTEGRITY),
            // Identity uniqueness indexes
            Map.entry("uq_structure_purpose", GsmRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS),
            Map.entry("uq_mechanism_function", GsmRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS),
            Map.entry("uq_archetype_title", GsmRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS));

    // ======================================================================
    // Persistence exception translation
    // ======================================================================

    /**
     * Translates a {@link DataIntegrityViolationException} to a domain exception.
     *
     * @return a {@link GsmRuleViolationException} for known constraints, or a
     *         {@link GsmInternalException} for unmapped constraints
     */
    static RuntimeException translatePersistenceException(DataIntegrityViolationException ex) {
        String constraintName = extractConstraintName(ex);
        if (constraintName != null) {
            GsmRuleType ruleType = CONSTRAINT_TO_RULE.get(constraintName);
            if (ruleType == null && constraintName.endsWith("_archetype_id_fkey")) {
                ruleType = GsmRuleType.ASCRIPTION_ARCHETYPE_REFERENCE_INTEGRITY;
            }
            if (ruleType == null && (constraintName.endsWith("_output_archetype_id_fkey")
                    || constraintName.endsWith("_input_archetype_id_fkey"))) {
                ruleType = GsmRuleType.ASCRIPTION_ARCHETYPE_REFERENCE_INTEGRITY;
            }
            if (ruleType != null) {
                return GsmRuleViolationException.of(ruleType,
                        "Database constraint violation: " + constraintName,
                        ex,
                        "constraint", constraintName);
            }
        }
        LOG.error("Unmapped DB constraint violation (constraint={})", constraintName, ex);
        return new GsmInternalException(
                "Database constraint violation"
                        + (constraintName != null ? ": " + constraintName : ""),
                ex);
    }

    static String extractConstraintName(DataIntegrityViolationException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof ConstraintViolationException cve) {
            return cve.getConstraintName();
        }
        return null;
    }

    // ======================================================================
    // Subtype identity
    // ======================================================================

    public abstract DefinitionSubjectType getSubjectType();

    protected GsmRuleType statementValidationRule() {
        return switch (getSubjectType()) {
            case STRUCTURE -> GsmRuleType.STRUCTURE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
            case MECHANISM -> GsmRuleType.MECHANISM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
            case EFFECTOR -> GsmRuleType.EFFECTOR_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
            case RECEPTOR -> GsmRuleType.RECEPTOR_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
            case INTERACTION -> GsmRuleType.INTERACTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
            case DIRECTIVE -> GsmRuleType.DIRECTIVE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
            case NORM -> GsmRuleType.NORM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
            case ARCHETYPE -> GsmRuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
        };
    }

    protected GsmRuleType nonGsmStatementValidationRule() {
        return switch (getSubjectType()) {
            case STRUCTURE -> GsmRuleType.STRUCTURE_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE;
            case MECHANISM -> GsmRuleType.MECHANISM_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE;
            case INTERACTION -> GsmRuleType.INTERACTION_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE;
            default -> null; // Other types have no tenant-extensible base schemas
        };
    }

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
        AscriptionEntity saved;
        try {
            saved = save(entity);

            // 8. Record initial DRAFT transition
            transitionService.recordTransition(saved, null, AscriptionStatusType.DRAFT);

            // 9. Refresh to pick up DB-trigger-assigned fields (status, timestamp)
            entityManager.refresh(saved);
        } catch (DataIntegrityViolationException ex) {
            throw translatePersistenceException(ex);
        }

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

    public Map<DefinitionSubjectType, AscriptionCascadeType> getCascadeTargetRoles() {
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
            throw GsmRuleViolationException.of(statementValidationRule(),
                    "Required field '" + field + "' missing in statement payload",
                    "field", field);
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            throw GsmRuleViolationException.of(statementValidationRule(),
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
                throw GsmRuleViolationException.of(statementValidationRule(),
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

        if (errors.isEmpty()) {
            return;
        }

        // For extensible subject types (Structure, Mechanism, Interaction),
        // classify errors as GSM-base vs tenant-extension violations.
        Set<String> baseProps = GSM_BASE_PROPERTIES.get(getSubjectType());
        GsmRuleType nonGsmRule = nonGsmStatementValidationRule();

        if (baseProps != null && nonGsmRule != null) {
            List<String> gsmMessages = new ArrayList<>();
            List<String> nonGsmMessages = new ArrayList<>();

            for (ValidationMessage err : errors) {
                if (isGsmBaseError(err, baseProps)) {
                    gsmMessages.add(err.getMessage());
                } else {
                    nonGsmMessages.add(err.getMessage());
                }
            }

            // Throw GSM violations first (higher precedence)
            if (!gsmMessages.isEmpty()) {
                throw GsmRuleViolationException.of(statementValidationRule(),
                        "Statement validation failed against archetype "
                                + archetype.getDefinition().getId() + ": " + gsmMessages,
                        "archetypeDefinitionId", archetype.getDefinition().getId(),
                        "violations", gsmMessages);
            }
            if (!nonGsmMessages.isEmpty()) {
                throw GsmRuleViolationException.of(nonGsmRule,
                        "Statement validation failed against tenant-extended archetype "
                                + archetype.getDefinition().getId() + ": " + nonGsmMessages,
                        "archetypeDefinitionId", archetype.getDefinition().getId(),
                        "violations", nonGsmMessages);
            }
        }

        // Non-extensible types or fallback: all errors are GSM violations
        List<String> messages = errors.stream().map(ValidationMessage::getMessage).toList();
        throw GsmRuleViolationException.of(statementValidationRule(),
                "Statement validation failed against archetype " + archetype.getDefinition().getId()
                        + ": " + messages,
                "archetypeDefinitionId", archetype.getDefinition().getId(),
                "violations", messages);
    }

    /**
     * Determines whether a validation error pertains to a GSM base schema property.
     * Uses instance location path and the property hint from the validation
     * message.
     */
    private static boolean isGsmBaseError(ValidationMessage error, Set<String> baseProps) {
        // Check instance location: root property of the failing path
        var instanceLoc = error.getInstanceLocation();
        if (instanceLoc != null && instanceLoc.getNameCount() > 0) {
            String rootProp = instanceLoc.getName(0);
            return baseProps.contains(rootProp);
        }
        // Check the property hint (used by 'required', 'additionalProperties', etc.)
        String property = error.getProperty();
        if (property != null && !property.isEmpty()) {
            return baseProps.contains(property);
        }
        // Root-level structural errors (e.g., type mismatch on root) → GSM
        return true;
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
                dataProtectionService.applyAtRestProtection(propSchema.get("$gsm:dataProtection"),
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
                    throw GsmRuleViolationException.of(statementValidationRule(),
                            "$gsm:validation[" + i + "] CEL parse error: " + compileResult.getErrorString(),
                            "keyword", "$gsm:validation", "index", i);
                }
                CelAbstractSyntaxTree ast = compileResult.getAst();
                CelRuntime.Program program = celRuntime.createProgram(ast);
                Object result = program.eval(Map.of("this", statementMap));

                if (!(result instanceof Boolean b) || !b) {
                    throw GsmRuleViolationException.of(statementValidationRule(),
                            "$gsm:validation[" + i + "] constraint failed: expression '"
                                    + expr + "' evaluated to " + result,
                            "keyword", "$gsm:validation", "index", i, "expression", expr);
                }
            } catch (GsmRuleViolationException e) {
                throw e; // re-throw our own exceptions
            } catch (Exception e) {
                throw GsmRuleViolationException.of(statementValidationRule(),
                        "$gsm:validation[" + i + "] evaluation error for expression '"
                                + expr + "': " + e.getMessage(),
                        "keyword", "$gsm:validation", "index", i, "expression", expr);
            }
        }
    }
}
