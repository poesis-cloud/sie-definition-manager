package com.sif.sie.definitionmanager.service;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.sif.sie.definitionmanager.client.SchemaRegistryClient;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
import com.sif.sie.definitionmanager.entity.AscriptionEntity;
import com.sif.sie.definitionmanager.entity.AscriptionStatusTransitionEntity;
import com.sif.sie.definitionmanager.entity.DefinitionEntity;
import com.sif.sie.definitionmanager.entity.DirectiveEntity;
import com.sif.sie.definitionmanager.entity.EffectorEntity;
import com.sif.sie.definitionmanager.entity.InteractionEntity;
import com.sif.sie.definitionmanager.entity.InterfaceEntity;
import com.sif.sie.definitionmanager.entity.MechanismEntity;
import com.sif.sie.definitionmanager.entity.NormEntity;
import com.sif.sie.definitionmanager.entity.ReceptorEntity;
import com.sif.sie.definitionmanager.entity.StructureEntity;
import com.sif.sie.definitionmanager.repository.ArchetypeRepository;
import com.sif.sie.definitionmanager.repository.AscriptionStatusTransitionRepository;
import com.sif.sie.definitionmanager.repository.DefinitionRepository;
import com.sif.sie.definitionmanager.repository.DirectiveRepository;
import com.sif.sie.definitionmanager.repository.EffectorRepository;
import com.sif.sie.definitionmanager.repository.InteractionRepository;
import com.sif.sie.definitionmanager.repository.InterfaceRepository;
import com.sif.sie.definitionmanager.repository.MechanismRepository;
import com.sif.sie.definitionmanager.repository.NormRepository;
import com.sif.sie.definitionmanager.repository.ReceptorRepository;
import com.sif.sie.definitionmanager.repository.StructureRepository;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;
import com.sif.sie.definitionmanager.util.StatementValidatorUtil;

import jakarta.persistence.EntityManager;

/**
 * Core service for unified ascription CRUD and lifecycle management.
 *
 * <p>
 * Routes operations to the appropriate entity table based on subject type,
 * derived from the
 * archetype's schema URI. FK references are extracted from the
 * {@code statement} JSON payload —
 * not from request-level fields. Manages lifecycle transitions with governance
 * convergence
 * semantics (see gsm-ascription-lifecycle state machine).
 */
@Service
@Transactional("transactionManager")
public class AscriptionService {

    /** Maps schema URI suffix to DefinitionSubjectType for base archetypes. */
    private static final Map<String, DefinitionSubjectType> SCHEMA_URI_TO_SUBJECT_TYPE = Map.of(
            "Archetype.schema.json", DefinitionSubjectType.ARCHETYPE,
            "Structure.schema.json", DefinitionSubjectType.STRUCTURE,
            "Mechanism.schema.json", DefinitionSubjectType.MECHANISM,
            "Interface.schema.json", DefinitionSubjectType.INTERFACE,
            "Effector.schema.json", DefinitionSubjectType.EFFECTOR,
            "Receptor.schema.json", DefinitionSubjectType.RECEPTOR,
            "Interaction.schema.json", DefinitionSubjectType.INTERACTION,
            "Directive.schema.json", DefinitionSubjectType.DIRECTIVE,
            "Norm.schema.json", DefinitionSubjectType.NORM);

    private final ArchetypeRepository archetypeRepo;
    private final StructureRepository structureRepo;
    private final MechanismRepository mechanismRepo;
    private final InterfaceRepository interfaceRepo;
    private final EffectorRepository effectorRepo;
    private final ReceptorRepository receptorRepo;
    private final InteractionRepository interactionRepo;
    private final DirectiveRepository directiveRepo;
    private final NormRepository normRepo;
    private final DefinitionRepository definitionRepo;
    private final AscriptionStatusTransitionRepository transitionRepo;
    private final SchemaRegistryClient schemaRegistryClient;
    private final StatementValidatorUtil definitionValidator;
    private final EntityManager entityManager;

    // Valid lifecycle transitions: current status -> set of allowed target statuses
    private static final Map<AscriptionStatusType, Set<AscriptionStatusType>> VALID_TRANSITIONS;

    static {
        VALID_TRANSITIONS = new EnumMap<>(AscriptionStatusType.class);
        VALID_TRANSITIONS.put(
                AscriptionStatusType.DRAFT, EnumSet.of(AscriptionStatusType.PROPOSED, AscriptionStatusType.ABANDONED));
        VALID_TRANSITIONS.put(
                AscriptionStatusType.PROPOSED,
                EnumSet.of(AscriptionStatusType.APPROVED, AscriptionStatusType.REJECTED));
        VALID_TRANSITIONS.put(AscriptionStatusType.APPROVED, EnumSet.of(AscriptionStatusType.ACTIVE));
        VALID_TRANSITIONS.put(
                AscriptionStatusType.ACTIVE,
                EnumSet.of(AscriptionStatusType.SUSPENDED, AscriptionStatusType.DEPRECATED));
        VALID_TRANSITIONS.put(
                AscriptionStatusType.SUSPENDED,
                EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED));
        VALID_TRANSITIONS.put(AscriptionStatusType.DEPRECATED, EnumSet.of(AscriptionStatusType.RETIRED));
        // RETIRED, ABANDONED, REJECTED are terminal — no outgoing transitions
        VALID_TRANSITIONS.put(AscriptionStatusType.RETIRED, EnumSet.noneOf(AscriptionStatusType.class));
        VALID_TRANSITIONS.put(AscriptionStatusType.ABANDONED, EnumSet.noneOf(AscriptionStatusType.class));
        VALID_TRANSITIONS.put(AscriptionStatusType.REJECTED, EnumSet.noneOf(AscriptionStatusType.class));
    }

    public AscriptionService(
            ArchetypeRepository archetypeRepo,
            StructureRepository structureRepo,
            MechanismRepository mechanismRepo,
            InterfaceRepository interfaceRepo,
            EffectorRepository effectorRepo,
            ReceptorRepository receptorRepo,
            InteractionRepository interactionRepo,
            DirectiveRepository directiveRepo,
            NormRepository normRepo,
            DefinitionRepository definitionRepo,
            AscriptionStatusTransitionRepository transitionRepo,
            SchemaRegistryClient schemaRegistryClient,
            StatementValidatorUtil definitionValidator,
            EntityManager entityManager) {
        this.archetypeRepo = archetypeRepo;
        this.structureRepo = structureRepo;
        this.mechanismRepo = mechanismRepo;
        this.interfaceRepo = interfaceRepo;
        this.effectorRepo = effectorRepo;
        this.receptorRepo = receptorRepo;
        this.interactionRepo = interactionRepo;
        this.directiveRepo = directiveRepo;
        this.normRepo = normRepo;
        this.definitionRepo = definitionRepo;
        this.transitionRepo = transitionRepo;
        this.schemaRegistryClient = schemaRegistryClient;
        this.definitionValidator = definitionValidator;
        this.entityManager = entityManager;
    }

    // ========================================================================
    // CREATE
    // ========================================================================

    /**
     * Creates a new ascription from raw fields.
     *
     * @param archetypeId  the archetype UUID that types this ascription
     * @param statement    the JSON statement payload
     * @param definitionId optional: attach to an existing Definition; null = create
     *                     new
     */
    public AscriptionEntity create(UUID archetypeId, JsonNode statement, UUID definitionId) {
        UUID resolvedArchetypeId = requireUuid(archetypeId, "archetypeId");
        ArchetypeEntity archetypeRef = archetypeRepo
                .findById(resolvedArchetypeId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Archetype not found: " + resolvedArchetypeId));

        DefinitionSubjectType type = resolveSubjectType(archetypeRef);

        // Validate statement against archetype schema
        definitionValidator.validate(statement, archetypeRef);

        // Resolve or create the Definition (stable identity)
        DefinitionEntity definition;
        if (definitionId != null) {
            definition = definitionRepo
                    .findById(definitionId)
                    .orElseThrow(
                            () -> new IllegalArgumentException(
                                    "Definition not found: " + definitionId));
        } else {
            definition = definitionRepo.save(new DefinitionEntity(type));
        }

        AscriptionEntity entity = buildEntity(type, definition, statement, archetypeRef);
        AscriptionEntity saved = saveEntity(type, entity);

        // Record initial DRAFT transition
        transitionRepo.save(
                new AscriptionStatusTransitionEntity(saved, null, AscriptionStatusType.DRAFT));

        // Flush + refresh to load DB-generated values (status from trigger, timestamp)
        entityManager.flush();
        entityManager.refresh(saved);

        return saved;
    }

    // ========================================================================
    // READ
    // ========================================================================

    @Transactional(value = "transactionManager", readOnly = true)
    public AscriptionEntity getById(UUID ascriptionId) {
        AscriptionEntity entity = entityManager.find(AscriptionEntity.class, ascriptionId);
        if (entity == null) {
            throw new IllegalArgumentException("No ascription found for id: " + ascriptionId);
        }
        return entity;
    }

    @Transactional(value = "transactionManager", readOnly = true)
    public Page<AscriptionEntity> list(
            String subjectTypeStr, AscriptionStatusType status, Pageable pageable) {
        DefinitionSubjectType type = DefinitionSubjectType.fromValue(subjectTypeStr);
        Page<? extends AscriptionEntity> page = (status != null) ? findAllByStatus(type, status, pageable)
                : findAll(type, pageable);
        return page.map(e -> e);
    }

    @Transactional(value = "transactionManager", readOnly = true)
    public List<AscriptionEntity> getAscriptionHistory(UUID definitionId, String subjectTypeStr) {
        DefinitionSubjectType type = DefinitionSubjectType.fromValue(subjectTypeStr);
        return findAllByDefinitionId(type, definitionId).stream()
                .map(e -> (AscriptionEntity) e)
                .toList();
    }

    @Transactional(value = "transactionManager", readOnly = true)
    public List<AscriptionStatusTransitionEntity> getTransitions(UUID ascriptionId) {
        return transitionRepo.findAllByAscription_IdOrderByTimestampAsc(ascriptionId);
    }

    // ========================================================================
    // LIFECYCLE TRANSITIONS
    // ========================================================================

    /**
     * Executes a lifecycle transition and returns the persisted transition entity.
     *
     * @param ascriptionId the ascription to transition
     * @param targetStatus the requested target status (name string, e.g.
     *                     "PROPOSED")
     */
    public AscriptionStatusTransitionEntity transition(UUID ascriptionId, String targetStatus) {
        AscriptionStatusType targetStatusType = AscriptionStatusType.valueOf(targetStatus);

        AscriptionEntity entity = entityManager.find(AscriptionEntity.class, ascriptionId);
        if (entity == null) {
            throw new IllegalArgumentException("No ascription found for id: " + ascriptionId);
        }
        DefinitionSubjectType type = entity.getDefinition().getSubjectType();
        AscriptionStatusType currentStatus = entity.getStatus();

        // Validate transition
        Set<AscriptionStatusType> allowed = VALID_TRANSITIONS.getOrDefault(currentStatus,
                EnumSet.noneOf(AscriptionStatusType.class));
        if (!allowed.contains(targetStatusType)) {
            throw new IllegalArgumentException(
                    "Invalid transition: " + currentStatus + " -> " + targetStatusType);
        }

        // Governance convergence: APPROVED triggers version assignment + sibling
        // termination
        if (targetStatusType == AscriptionStatusType.APPROVED) {
            handleApproval(type, entity);
        }

        // ACTIVE triggers cascade of previous ACTIVE to DEPRECATED (if any)
        if (targetStatusType == AscriptionStatusType.ACTIVE) {
            handleActivation(type, entity);
        }

        // Record transition (DB trigger will update entity's status column)
        AscriptionStatusTransitionEntity saved = transitionRepo.save(
                new AscriptionStatusTransitionEntity(
                        entity, currentStatus, targetStatusType));

        // Flush + detach + re-read to load DB-generated timestamp
        // (refresh() not supported for @Immutable entities in Hibernate)
        entityManager.flush();
        entityManager.detach(saved);
        UUID transitionId = requireUuid(saved.getId(), "transition.id");
        return transitionRepo.findById(transitionId).orElseThrow();
    }

    // ========================================================================
    // GOVERNANCE CONVERGENCE
    // ========================================================================

    /**
     * Approval convergence: auto-terminate sibling ascriptions.
     *
     * <p>
     * Per GSM: approving one ascription auto-rejects all other non-terminal sibling
     * ascriptions
     * for that definition (DRAFT siblings -> ABANDONED, PROPOSED siblings ->
     * REJECTED).
     * Version assignment is handled atomically by the DB trigger on the APPROVED
     * transition INSERT.
     */
    private void handleApproval(DefinitionSubjectType type, AscriptionEntity approved) {
        UUID definitionId = approved.getDefinition().getId();

        // Auto-terminate siblings
        List<? extends AscriptionEntity> allAscriptions = findAllByDefinitionId(type, definitionId);
        for (AscriptionEntity sibling : allAscriptions) {
            if (sibling.getId().equals(approved.getId()))
                continue;
            AscriptionStatusType siblingStatus = sibling.getStatus();
            AscriptionStatusType terminalStatus;
            if (siblingStatus == AscriptionStatusType.DRAFT) {
                terminalStatus = AscriptionStatusType.ABANDONED;
            } else if (siblingStatus == AscriptionStatusType.PROPOSED) {
                terminalStatus = AscriptionStatusType.REJECTED;
            } else {
                continue; // already terminal or in-effect — skip
            }
            transitionRepo.save(
                    new AscriptionStatusTransitionEntity(
                            sibling, siblingStatus, terminalStatus));
        }
    }

    /** Activation: cascade previous ACTIVE ascription to DEPRECATED. */
    private void handleActivation(DefinitionSubjectType type, AscriptionEntity activating) {
        UUID definitionId = activating.getDefinition().getId();
        List<? extends AscriptionEntity> activeAscriptions = findAllByDefinitionIdAndStatus(type, definitionId,
                List.of(AscriptionStatusType.ACTIVE));
        for (AscriptionEntity prev : activeAscriptions) {
            if (prev.getId().equals(activating.getId()))
                continue;
            transitionRepo.save(
                    new AscriptionStatusTransitionEntity(
                            prev, AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED));
        }
    }

    // ========================================================================
    // ENTITY BUILDING & MAPPING
    // ========================================================================

    private AscriptionEntity buildEntity(
            DefinitionSubjectType type,
            DefinitionEntity definition,
            JsonNode statement,
            ArchetypeEntity archetypeRef) {
        return switch (type) {
            case ARCHETYPE -> {
                JsonNode schema = statement.get("schema");
                String subject = "gsm_archetype_definition_" + definition.getId();
                int schemaId = schemaRegistryClient.registerSchema(subject, schema);
                String schemaUri = schemaRegistryClient.buildSchemaUri(schemaId);
                yield new ArchetypeEntity(definition, archetypeRef, statement, schemaUri);
            }
            case STRUCTURE -> new StructureEntity(definition, archetypeRef, statement);
            case MECHANISM ->
                new MechanismEntity(
                        definition,
                        archetypeRef,
                        statement,
                        requireRefFromStatement(
                                structureRepo, statement, "structureId", "structureId"));
            case INTERFACE ->
                new InterfaceEntity(
                        definition,
                        archetypeRef,
                        statement,
                        requireRefFromStatement(
                                structureRepo, statement, "structureId", "structureId"),
                        resolveRefListFromStatement(
                                effectorRepo, statement, "effectorIds"),
                        resolveRefListFromStatement(
                                receptorRepo, statement, "receptorIds"));
            case EFFECTOR ->
                new EffectorEntity(
                        definition,
                        archetypeRef,
                        statement,
                        requireRefFromStatement(
                                mechanismRepo, statement, "mechanismId", "mechanismId"),
                        requireRefFromStatement(
                                archetypeRepo, statement, "outputArchetypeId", "outputArchetypeId"));
            case RECEPTOR ->
                new ReceptorEntity(
                        definition,
                        archetypeRef,
                        statement,
                        requireRefFromStatement(
                                mechanismRepo, statement, "mechanismId", "mechanismId"),
                        requireRefFromStatement(
                                archetypeRepo, statement, "inputArchetypeId", "inputArchetypeId"));
            case INTERACTION ->
                new InteractionEntity(
                        definition,
                        archetypeRef,
                        statement,
                        requireRefFromStatement(
                                effectorRepo, statement, "effectorId", "effectorId"),
                        requireRefFromStatement(
                                receptorRepo, statement, "receptorId", "receptorId"));
            case DIRECTIVE ->
                new DirectiveEntity(
                        definition,
                        archetypeRef,
                        statement,
                        requireRefFromStatement(
                                structureRepo, statement, "structureId", "structureId"),
                        requireRefFromStatement(
                                archetypeRepo, statement, "qualifierId", "qualifierId"),
                        requireRefFromStatement(
                                structureRepo, statement, "purposeId", "purposeId"));
            case NORM ->
                new NormEntity(
                        definition,
                        archetypeRef,
                        statement,
                        requireRefFromStatement(
                                structureRepo, statement, "structureId", "structureId"));
        };
    }

    // ========================================================================
    // REPOSITORY ROUTING
    // ========================================================================

    @SuppressWarnings("unchecked")
    private <T extends AscriptionEntity> T findByAscriptionId(
            DefinitionSubjectType type, UUID ascriptionId) {
        JpaRepository<? extends AscriptionEntity, UUID> repo = repoFor(type);
        return (T) repo.findById(requireUuid(ascriptionId, "ascriptionId"))
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                type.getValue() + " not found: " + ascriptionId));
    }

    private Page<? extends AscriptionEntity> findAllByStatus(
            DefinitionSubjectType type, AscriptionStatusType status, Pageable pageable) {
        return switch (type) {
            case ARCHETYPE -> archetypeRepo.findAllByStatus(status, pageable);
            case STRUCTURE -> structureRepo.findAllByStatus(status, pageable);
            case MECHANISM -> mechanismRepo.findAllByStatus(status, pageable);
            case INTERFACE -> interfaceRepo.findAllByStatus(status, pageable);
            case EFFECTOR -> effectorRepo.findAllByStatus(status, pageable);
            case RECEPTOR -> receptorRepo.findAllByStatus(status, pageable);
            case INTERACTION -> interactionRepo.findAllByStatus(status, pageable);
            case DIRECTIVE -> directiveRepo.findAllByStatus(status, pageable);
            case NORM -> normRepo.findAllByStatus(status, pageable);
        };
    }

    private Page<? extends AscriptionEntity> findAll(
            DefinitionSubjectType type, Pageable pageable) {
        return repoFor(type).findAll(Objects.requireNonNull(pageable, "pageable must not be null"));
    }

    private List<? extends AscriptionEntity> findAllByDefinitionId(
            DefinitionSubjectType type, UUID definitionId) {
        return switch (type) {
            case ARCHETYPE -> archetypeRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
            case STRUCTURE -> structureRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
            case MECHANISM -> mechanismRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
            case INTERFACE -> interfaceRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
            case EFFECTOR -> effectorRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
            case RECEPTOR -> receptorRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
            case INTERACTION ->
                interactionRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
            case DIRECTIVE -> directiveRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
            case NORM -> normRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
        };
    }

    private List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            DefinitionSubjectType type, UUID definitionId, Collection<AscriptionStatusType> statuses) {
        return switch (type) {
            case ARCHETYPE -> archetypeRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
            case STRUCTURE -> structureRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
            case MECHANISM -> mechanismRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
            case INTERFACE -> interfaceRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
            case EFFECTOR -> effectorRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
            case RECEPTOR -> receptorRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
            case INTERACTION ->
                interactionRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
            case DIRECTIVE -> directiveRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
            case NORM -> normRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
        };
    }

    private AscriptionEntity saveEntity(DefinitionSubjectType type, AscriptionEntity entity) {
        return switch (type) {
            case ARCHETYPE -> archetypeRepo.save(Objects.requireNonNull((ArchetypeEntity) entity));
            case STRUCTURE -> structureRepo.save(Objects.requireNonNull((StructureEntity) entity));
            case MECHANISM -> mechanismRepo.save(Objects.requireNonNull((MechanismEntity) entity));
            case INTERFACE -> interfaceRepo.save(Objects.requireNonNull((InterfaceEntity) entity));
            case EFFECTOR -> effectorRepo.save(Objects.requireNonNull((EffectorEntity) entity));
            case RECEPTOR -> receptorRepo.save(Objects.requireNonNull((ReceptorEntity) entity));
            case INTERACTION ->
                interactionRepo.save(Objects.requireNonNull((InteractionEntity) entity));
            case DIRECTIVE -> directiveRepo.save(Objects.requireNonNull((DirectiveEntity) entity));
            case NORM -> normRepo.save(Objects.requireNonNull((NormEntity) entity));
        };
    }

    private JpaRepository<? extends AscriptionEntity, UUID> repoFor(DefinitionSubjectType type) {
        return switch (type) {
            case ARCHETYPE -> archetypeRepo;
            case STRUCTURE -> structureRepo;
            case MECHANISM -> mechanismRepo;
            case INTERFACE -> interfaceRepo;
            case EFFECTOR -> effectorRepo;
            case RECEPTOR -> receptorRepo;
            case INTERACTION -> interactionRepo;
            case DIRECTIVE -> directiveRepo;
            case NORM -> normRepo;
        };
    }

    /** Extracts a required UUID FK reference from the statement JSON payload. */
    private <T> T requireRefFromStatement(
            JpaRepository<T, UUID> repo, JsonNode statement, String jsonField, String displayName) {
        JsonNode node = statement.get(jsonField);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "Required field '" + jsonField + "' missing in statement payload");
        }
        UUID refId;
        try {
            refId = UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid UUID for field '" + jsonField + "': " + node.asText());
        }
        return repo.findById(requireUuid(refId, jsonField))
                .orElseThrow(() -> new IllegalArgumentException(displayName + " not found: " + refId));
    }

    /** Resolves an optional JSON array of UUID references to a list of entities. */
    private <T> List<T> resolveRefListFromStatement(
            JpaRepository<T, UUID> repo, JsonNode statement, String jsonField) {
        JsonNode node = statement.get(jsonField);
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        List<T> result = new java.util.ArrayList<>(node.size());
        for (JsonNode element : node) {
            UUID refId;
            try {
                refId = UUID.fromString(element.asText());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid UUID in '" + jsonField + "': " + element.asText());
            }
            result.add(repo.findById(requireUuid(refId, jsonField))
                    .orElseThrow(() -> new IllegalArgumentException(
                            jsonField + " element not found: " + refId)));
        }
        return result;
    }

    private @NonNull UUID requireUuid(UUID value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " must not be null");
    }

    /**
     * Derives DefinitionSubjectType from the archetype's schema URI.
     *
     * <p>
     * Base archetypes have URIs like {@code urn:sie:gsm:v1:Structure.schema.json}.
     * The suffix
     * after the last ':' is matched against the lookup map.
     */
    private DefinitionSubjectType resolveSubjectType(ArchetypeEntity archetype) {
        String schemaUri = archetype.getSchemaUri();
        if (schemaUri == null) {
            JsonNode stmt = archetype.getStatement();
            if (stmt != null && stmt.has("schemaUri")) {
                schemaUri = stmt.get("schemaUri").asText();
            }
        }
        if (schemaUri == null) {
            throw new IllegalArgumentException(
                    "Cannot derive subject type: archetype has no schema URI: " + archetype.getId());
        }
        int lastColon = schemaUri.lastIndexOf(':');
        String suffix = (lastColon >= 0) ? schemaUri.substring(lastColon + 1) : schemaUri;
        DefinitionSubjectType type = SCHEMA_URI_TO_SUBJECT_TYPE.get(suffix);
        if (type == null) {
            throw new IllegalArgumentException(
                    "Cannot derive subject type from schema URI: " + schemaUri);
        }
        return type;
    }
}
