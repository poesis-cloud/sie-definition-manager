package io.poesis.sie.defman.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import io.poesis.sie.defman.entity.ArchetypeEntity;
import io.poesis.sie.defman.entity.AscriptionEntity;
import io.poesis.sie.defman.entity.DefinitionEntity;
import io.poesis.sie.defman.repository.DefinitionRepository;
import io.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import io.poesis.sie.defman.type.AscriptionStatusType;
import io.poesis.sie.defman.type.DefinitionSubjectType;
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

    // Shared dependencies — field-injected to avoid constructor bloat across 9
    // subclasses
    @Autowired
    private DefinitionService definitionService;
    @Autowired
    private AscriptionStatusTransitionService transitionService;
    @Autowired
    private DefinitionRepository definitionRepository;
    @Autowired
    private EntityManager entityManager;

    // Referee precondition for [*]→DRAFT creation
    private static final Set<AscriptionStatusType> CREATION_REFEREE_ALLOWED = EnumSet.of(
            AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED,
            AscriptionStatusType.APPROVED, AscriptionStatusType.ACTIVE);

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
     * Returns Ascription history for a Definition, with lazy
     * properties initialized for DTO mapping outside the persistence context.
     */
    @Transactional(value = "transactionManager", readOnly = true)
    public List<? extends AscriptionEntity> getHistory(UUID definitionId) {
        List<? extends AscriptionEntity> entities = findAllByDefinitionId(definitionId);
        entities.forEach(e -> Hibernate.initialize(e.getDefinition()));
        return entities;
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

    // ======================================================================
    // Statement JSON extraction utilities (protected)
    // ======================================================================

    protected UUID extractRequiredUuid(JsonNode statement, String field) {
        JsonNode node = statement.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "Required field '" + field + "' missing in statement payload");
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid UUID for field '" + field + "': " + node.asText());
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
                throw new IllegalArgumentException(
                        "Invalid UUID in '" + field + "': " + element.asText());
            }
        }
        return result;
    }

    // ======================================================================
    // Identity-bound validation (private — called from create())
    // ======================================================================

    private void validateIdentityBound(AscriptionEntity entity) {
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
                throw new IllegalArgumentException(
                        "Identity-bound field '" + field + "' cannot change across Ascriptions "
                                + "of the same Definition " + definitionId
                                + ": was '" + firstVal + "', got '" + newVal + "'");
            }
        }
    }

    // ======================================================================
    // Creation referee preconditions (private — called from create())
    // ======================================================================

    private void validateCreationPreconditions(AscriptionEntity entity) {
        List<RefereeReference> refs = getRefereeReferences(entity);
        if (refs.isEmpty()) {
            return;
        }

        for (RefereeReference ref : refs) {
            AscriptionStatusType refStatus = ref.reference().getStatus();
            if (!CREATION_REFEREE_ALLOWED.contains(refStatus)) {
                throw new IllegalArgumentException(
                        "Referee precondition failed for [*]->DRAFT: reference '"
                                + ref.label() + "' (id=" + ref.reference().getId()
                                + ") is in status " + refStatus
                                + ", must be one of " + CREATION_REFEREE_ALLOWED);
            }
        }
    }

    // ======================================================================
    // Statement validation (inlined from StatementValidator)
    // ======================================================================

    private void validateStatement(JsonNode statement, ArchetypeEntity archetype) {
        JsonNode archetypeStatement = archetype.getStatement();
        JsonNode schemaNode = archetypeStatement.get("schema");

        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        JsonSchema schema = schemaFactory.getSchema(schemaNode, config);
        Set<ValidationMessage> errors = schema.validate(statement);

        if (!errors.isEmpty()) {
            String details = errors.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; "));
            throw new IllegalArgumentException(
                    "Statement validation failed against archetype ["
                            + archetype.getDefinition().getId() + "]: " + details);
        }
    }

    // ======================================================================
    // $gsm:* annotation enforcement on Ascription (inlined from
    // GsmAnnotationValidator)
    // ======================================================================

    private static final Collection<AscriptionStatusType> GSM_IN_EFFECT = List.of(AscriptionStatusType.ACTIVE,
            AscriptionStatusType.DEPRECATED);

    void enforceGsmAnnotations(JsonNode statement, ArchetypeEntity archetype, UUID definitionId) {
        JsonNode archetypeSchema = archetype.getStatement().get("schema");
        if (archetypeSchema == null) {
            return;
        }

        JsonNode properties = archetypeSchema.get("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }

        List<String> warnings = new ArrayList<>();

        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String propName = entry.getKey();
            JsonNode propSchema = entry.getValue();

            if (!statement.has(propName)) {
                continue;
            }

            JsonNode value = statement.get(propName);

            if (propSchema.has("$gsm:referential")) {
                enforceReferential(propSchema.get("$gsm:referential"), propName, value);
            }

            if (hasGsmAnnotation(propSchema, "$gsm:unique")) {
                LOG.debug("$gsm:unique check for property '{}' value '{}' on archetype definition {}",
                        propName,
                        value.isTextual() ? value.asText() : value.toString(),
                        archetype.getDefinition().getId());
            }

            if (hasGsmAnnotation(propSchema, "$gsm:deprecated")) {
                warnings.add("Property '" + propName + "' is deprecated");
            }
        }

        for (String warning : warnings) {
            LOG.warn("$gsm:deprecated: {}", warning);
        }
    }

    private void enforceReferential(JsonNode annotation, String propName, JsonNode value) {
        if (!value.isTextual()) {
            return;
        }

        UUID refId;
        try {
            refId = UUID.fromString(value.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "$gsm:referential: property '" + propName + "' value '"
                            + value.asText() + "' is not a valid UUID");
        }

        DefinitionEntity refDef = definitionRepository.findById(refId).orElse(null);
        if (refDef == null) {
            throw new IllegalArgumentException(
                    "$gsm:referential: property '" + propName + "' references Definition "
                            + refId + " which does not exist");
        }

        if (annotation.has("subjectType")) {
            String expectedType = annotation.get("subjectType").asText();
            if (!expectedType.equals(refDef.getSubjectType().name())) {
                throw new IllegalArgumentException(
                        "$gsm:referential: property '" + propName + "' references Definition "
                                + refId + " with subjectType " + refDef.getSubjectType()
                                + " but expected " + expectedType);
            }
        }
    }

    private static boolean hasGsmAnnotation(JsonNode node, String annotation) {
        return node.has(annotation) && node.get(annotation).asBoolean(false);
    }
}
