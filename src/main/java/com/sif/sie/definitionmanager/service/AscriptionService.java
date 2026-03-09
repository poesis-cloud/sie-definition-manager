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
import com.sif.sie.definitionmanager.controller.dto.AscriptionRequest;
import com.sif.sie.definitionmanager.controller.dto.AscriptionResponse;
import com.sif.sie.definitionmanager.controller.dto.TransitionRequest;
import com.sif.sie.definitionmanager.controller.dto.TransitionResponse;
import com.sif.sie.definitionmanager.entity.AbstractAscription;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
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
import com.sif.sie.definitionmanager.enums.AscriptionStatus;
import com.sif.sie.definitionmanager.enums.DefinitionSubjectType;
import com.sif.sie.definitionmanager.repository.ArchetypeRepository;
import com.sif.sie.definitionmanager.repository.DefinitionRepository;
import com.sif.sie.definitionmanager.repository.DirectiveRepository;
import com.sif.sie.definitionmanager.repository.EffectorRepository;
import com.sif.sie.definitionmanager.repository.InteractionRepository;
import com.sif.sie.definitionmanager.repository.InterfaceRepository;
import com.sif.sie.definitionmanager.repository.MechanismRepository;
import com.sif.sie.definitionmanager.repository.NormRepository;
import com.sif.sie.definitionmanager.repository.ReceptorRepository;
import com.sif.sie.definitionmanager.repository.StatusTransitionRepository;
import com.sif.sie.definitionmanager.repository.StructureRepository;
import com.sif.sie.definitionmanager.util.DefinitionValidator;

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
    private final StatusTransitionRepository transitionRepo;
    private final SchemaRegistryClient schemaRegistryClient;
    private final DefinitionValidator definitionValidator;
    private final EntityManager entityManager;

    // Valid lifecycle transitions: current status -> set of allowed target statuses
    private static final Map<AscriptionStatus, Set<AscriptionStatus>> VALID_TRANSITIONS;

    static {
        VALID_TRANSITIONS = new EnumMap<>(AscriptionStatus.class);
        VALID_TRANSITIONS.put(
                AscriptionStatus.DRAFT, EnumSet.of(AscriptionStatus.PROPOSED, AscriptionStatus.ABANDONED));
        VALID_TRANSITIONS.put(
                AscriptionStatus.PROPOSED,
                EnumSet.of(AscriptionStatus.APPROVED, AscriptionStatus.REJECTED));
        VALID_TRANSITIONS.put(AscriptionStatus.APPROVED, EnumSet.of(AscriptionStatus.ACTIVE));
        VALID_TRANSITIONS.put(
                AscriptionStatus.ACTIVE,
                EnumSet.of(AscriptionStatus.SUSPENDED, AscriptionStatus.DEPRECATED));
        VALID_TRANSITIONS.put(
                AscriptionStatus.SUSPENDED,
                EnumSet.of(AscriptionStatus.ACTIVE, AscriptionStatus.DEPRECATED));
        VALID_TRANSITIONS.put(AscriptionStatus.DEPRECATED, EnumSet.of(AscriptionStatus.RETIRED));
        // RETIRED, ABANDONED, REJECTED are terminal — no outgoing transitions
        VALID_TRANSITIONS.put(AscriptionStatus.RETIRED, EnumSet.noneOf(AscriptionStatus.class));
        VALID_TRANSITIONS.put(AscriptionStatus.ABANDONED, EnumSet.noneOf(AscriptionStatus.class));
        VALID_TRANSITIONS.put(AscriptionStatus.REJECTED, EnumSet.noneOf(AscriptionStatus.class));
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
            StatusTransitionRepository transitionRepo,
            SchemaRegistryClient schemaRegistryClient,
            DefinitionValidator definitionValidator,
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

    public AscriptionResponse create(AscriptionRequest req) {
        UUID archetypeId = requireUuid(req.archetypeId(), "archetypeId");
        ArchetypeEntity archetypeRef = archetypeRepo
                .findById(archetypeId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Archetype not found: " + archetypeId));

        DefinitionSubjectType type = resolveSubjectType(archetypeRef);

        // Validate statement against archetype schema
        definitionValidator.validate(req.statement(), archetypeRef);

        // Resolve or create the Definition (stable identity)
        DefinitionEntity definition;
        if (req.definitionId() != null) {
            definition = definitionRepo
                    .findById(req.definitionId())
                    .orElseThrow(
                            () -> new IllegalArgumentException(
                                    "Definition not found: " + req.definitionId()));
        } else {
            definition = definitionRepo.save(new DefinitionEntity(type));
        }

        AbstractAscription entity = buildEntity(type, definition, req.statement(), archetypeRef);
        AbstractAscription saved = saveEntity(type, entity);

        // Record initial DRAFT transition
        transitionRepo.save(
                new AscriptionStatusTransitionEntity(type, saved.getId(), null, AscriptionStatus.DRAFT));

        // Flush + refresh to load DB-generated values (status from trigger, timestamp)
        entityManager.flush();
        entityManager.refresh(saved);

        return toResponse(type, saved);
    }

    // ========================================================================
    // READ
    // ========================================================================

    @Transactional(value = "transactionManager", readOnly = true)
    public AscriptionResponse getById(UUID ascriptionId) {
        DefinitionSubjectType type = transitionRepo
                .findSubjectTypeByAscriptionId(ascriptionId)
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                "No ascription found for id: " + ascriptionId));

        AbstractAscription entity = findByAscriptionId(type, ascriptionId);
        return toResponse(type, entity);
    }

    @Transactional(value = "transactionManager", readOnly = true)
    public Page<AscriptionResponse> list(
            String subjectTypeStr, AscriptionStatus status, Pageable pageable) {
        DefinitionSubjectType type = DefinitionSubjectType.fromValue(subjectTypeStr);
        Page<? extends AbstractAscription> page = (status != null) ? findAllByStatus(type, status, pageable)
                : findAll(type, pageable);
        return page.map(e -> toResponse(type, e));
    }

    @Transactional(value = "transactionManager", readOnly = true)
    public List<AscriptionResponse> getAscriptionHistory(UUID definitionId, String subjectTypeStr) {
        DefinitionSubjectType type = DefinitionSubjectType.fromValue(subjectTypeStr);
        return findAllByDefinitionId(type, definitionId).stream()
                .map(e -> toResponse(type, e))
                .toList();
    }

    @Transactional(value = "transactionManager", readOnly = true)
    public List<TransitionResponse> getTransitions(UUID ascriptionId) {
        return transitionRepo.findAllByAscriptionIdOrderByTimestampAsc(ascriptionId).stream()
                .map(
                        t -> new TransitionResponse(
                                t.getId(),
                                t.getAscriptionId(),
                                t.getPreStatus() != null ? t.getPreStatus().name() : null,
                                t.getPostStatus().name(),
                                t.getTimestamp()))
                .toList();
    }

    // ========================================================================
    // LIFECYCLE TRANSITIONS
    // ========================================================================

    public TransitionResponse transition(UUID ascriptionId, TransitionRequest req) {
        AscriptionStatus targetStatus = AscriptionStatus.valueOf(req.targetStatus());

        DefinitionSubjectType type = transitionRepo
                .findSubjectTypeByAscriptionId(ascriptionId)
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                "No ascription found for id: " + ascriptionId));

        AbstractAscription entity = findByAscriptionId(type, ascriptionId);
        AscriptionStatus currentStatus = entity.getStatus();

        // Validate transition
        Set<AscriptionStatus> allowed = VALID_TRANSITIONS.getOrDefault(currentStatus,
                EnumSet.noneOf(AscriptionStatus.class));
        if (!allowed.contains(targetStatus)) {
            throw new IllegalArgumentException(
                    "Invalid transition: " + currentStatus + " -> " + targetStatus);
        }

        // Governance convergence: APPROVED triggers version assignment + sibling
        // termination
        if (targetStatus == AscriptionStatus.APPROVED) {
            handleApproval(type, entity);
        }

        // ACTIVE triggers cascade of previous ACTIVE to DEPRECATED (if any)
        if (targetStatus == AscriptionStatus.ACTIVE) {
            handleActivation(type, entity);
        }

        // Record transition (DB trigger will update entity's status column)
        AscriptionStatusTransitionEntity saved = transitionRepo.save(
                new AscriptionStatusTransitionEntity(
                        type, ascriptionId, currentStatus, targetStatus));

        // Flush + detach + re-read to load DB-generated timestamp
        // (refresh() not supported for @Immutable entities in Hibernate)
        entityManager.flush();
        entityManager.detach(saved);
        UUID transitionId = requireUuid(saved.getId(), "transition.id");
        saved = transitionRepo.findById(transitionId).orElseThrow();

        return new TransitionResponse(
                saved.getId(),
                saved.getAscriptionId(),
                currentStatus.name(),
                targetStatus.name(),
                saved.getTimestamp());
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
    private void handleApproval(DefinitionSubjectType type, AbstractAscription approved) {
        UUID definitionId = approved.getDefinition().getId();

        // Auto-terminate siblings
        List<? extends AbstractAscription> allAscriptions = findAllByDefinitionId(type, definitionId);
        for (AbstractAscription sibling : allAscriptions) {
            if (sibling.getId().equals(approved.getId()))
                continue;
            AscriptionStatus siblingStatus = sibling.getStatus();
            AscriptionStatus terminalStatus;
            if (siblingStatus == AscriptionStatus.DRAFT) {
                terminalStatus = AscriptionStatus.ABANDONED;
            } else if (siblingStatus == AscriptionStatus.PROPOSED) {
                terminalStatus = AscriptionStatus.REJECTED;
            } else {
                continue; // already terminal or in-effect — skip
            }
            transitionRepo.save(
                    new AscriptionStatusTransitionEntity(
                            type, sibling.getId(), siblingStatus, terminalStatus));
        }
    }

    /** Activation: cascade previous ACTIVE ascription to DEPRECATED. */
    private void handleActivation(DefinitionSubjectType type, AbstractAscription activating) {
        UUID definitionId = activating.getDefinition().getId();
        List<? extends AbstractAscription> activeAscriptions = findAllByDefinitionIdAndStatus(type, definitionId,
                List.of(AscriptionStatus.ACTIVE));
        for (AbstractAscription prev : activeAscriptions) {
            if (prev.getId().equals(activating.getId()))
                continue;
            transitionRepo.save(
                    new AscriptionStatusTransitionEntity(
                            type, prev.getId(), AscriptionStatus.ACTIVE, AscriptionStatus.DEPRECATED));
        }
    }

    // ========================================================================
    // ENTITY BUILDING & MAPPING
    // ========================================================================

    private AbstractAscription buildEntity(
            DefinitionSubjectType type,
            DefinitionEntity definition,
            JsonNode statement,
            ArchetypeEntity archetypeRef) {
        return switch (type) {
            case ARCHETYPE -> {
                JsonNode schema = statement.get("schema");
                int schemaId = schemaRegistryClient.registerSchema(
                        SchemaRegistryClient.subjectFor(definition.getId()), schema);
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
                                structureRepo, statement, "structureId", "structureId"));
            case EFFECTOR ->
                new EffectorEntity(
                        definition,
                        archetypeRef,
                        statement,
                        requireRefFromStatement(
                                mechanismRepo, statement, "mechanismId", "mechanismId"),
                        requireRefFromStatement(
                                archetypeRepo, statement, "portArchetypeId", "portArchetypeId"),
                        optionalRefFromStatement(interfaceRepo, statement, "interfaceId"));
            case RECEPTOR ->
                new ReceptorEntity(
                        definition,
                        archetypeRef,
                        statement,
                        requireRefFromStatement(
                                mechanismRepo, statement, "mechanismId", "mechanismId"),
                        requireRefFromStatement(
                                archetypeRepo, statement, "portArchetypeId", "portArchetypeId"),
                        optionalRefFromStatement(interfaceRepo, statement, "interfaceId"));
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
                        optionalRefFromStatement(structureRepo, statement, "purposeId"));
            case NORM ->
                new NormEntity(
                        definition,
                        archetypeRef,
                        statement,
                        requireRefFromStatement(
                                structureRepo, statement, "structureId", "structureId"));
        };
    }

    private AscriptionResponse toResponse(DefinitionSubjectType type, AbstractAscription entity) {
        String schemaUri = null;
        if (type == DefinitionSubjectType.ARCHETYPE) {
            schemaUri = ((ArchetypeEntity) entity).getSchemaUri();
        }

        return new AscriptionResponse(
                type.getValue(),
                entity.getDefinition().getId(),
                entity.getId(),
                entity.getTimestamp(),
                entity.getArchetype().getId(),
                entity.getStatement(),
                entity.getVersion(),
                entity.getStatus() != null ? entity.getStatus().name() : "DRAFT",
                schemaUri);
    }

    // ========================================================================
    // REPOSITORY ROUTING
    // ========================================================================

    @SuppressWarnings("unchecked")
    private <T extends AbstractAscription> T findByAscriptionId(
            DefinitionSubjectType type, UUID ascriptionId) {
        JpaRepository<? extends AbstractAscription, UUID> repo = repoFor(type);
        return (T) repo.findById(requireUuid(ascriptionId, "ascriptionId"))
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                type.getValue() + " not found: " + ascriptionId));
    }

    private Page<? extends AbstractAscription> findAllByStatus(
            DefinitionSubjectType type, AscriptionStatus status, Pageable pageable) {
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

    private Page<? extends AbstractAscription> findAll(
            DefinitionSubjectType type, Pageable pageable) {
        return repoFor(type).findAll(Objects.requireNonNull(pageable, "pageable must not be null"));
    }

    private List<? extends AbstractAscription> findAllByDefinitionId(
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

    private List<? extends AbstractAscription> findAllByDefinitionIdAndStatus(
            DefinitionSubjectType type, UUID definitionId, Collection<AscriptionStatus> statuses) {
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

    private AbstractAscription saveEntity(DefinitionSubjectType type, AbstractAscription entity) {
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

    private JpaRepository<? extends AbstractAscription, UUID> repoFor(DefinitionSubjectType type) {
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

    /** Extracts an optional UUID FK reference from the statement JSON payload. */
    private <T> T optionalRefFromStatement(
            JpaRepository<T, UUID> repo, JsonNode statement, String jsonField) {
        JsonNode node = statement.get(jsonField);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        UUID refId;
        try {
            refId = UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid UUID for field '" + jsonField + "': " + node.asText());
        }
        return repo.findById(requireUuid(refId, jsonField))
                .orElseThrow(() -> new IllegalArgumentException(jsonField + " not found: " + refId));
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
