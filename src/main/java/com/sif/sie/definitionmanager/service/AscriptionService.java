package com.sif.sie.definitionmanager.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
import com.sif.sie.definitionmanager.entity.AscriptionEntity;
import com.sif.sie.definitionmanager.entity.AscriptionStatusTransitionEntity;
import com.sif.sie.definitionmanager.entity.DefinitionEntity;
import com.sif.sie.definitionmanager.repository.ArchetypeRepository;
import com.sif.sie.definitionmanager.repository.AscriptionStatusTransitionRepository;
import com.sif.sie.definitionmanager.repository.DefinitionRepository;
import com.sif.sie.definitionmanager.service.subtype.AscriptionSubtypeService;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;
import com.sif.sie.definitionmanager.util.StatementValidatorUtil;
import com.sif.sie.definitionmanager.validator.GsmAnnotationValidator;

import org.hibernate.Hibernate;

import jakarta.persistence.EntityManager;

/**
 * Core service for unified ascription CRUD and lifecycle management.
 *
 * <p>
 * Routes operations to the appropriate entity table based on subject type,
 * derived from the archetype's schema title. FK references are extracted from
 * the {@code statement} JSON payload — not from request-level fields. Manages
 * lifecycle transitions with governance convergence semantics (see
 * gsm-ascription-lifecycle state machine).
 */
@Service
@Transactional("transactionManager")
public class AscriptionService {

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

    private final ArchetypeRepository archetypeRepo;
    private final DefinitionRepository definitionRepo;
    private final AscriptionStatusTransitionRepository transitionRepo;
    private final StatementValidatorUtil statementValidator;
    private final GsmAnnotationValidator gsmAnnotationValidator;
    private final Map<DefinitionSubjectType, AscriptionSubtypeService> subtypeServiceByType;
    private final EntityManager entityManager;
    private final AscriptionLifecycleService lifecycleService;

    public AscriptionService(
            ArchetypeRepository archetypeRepo,
            DefinitionRepository definitionRepo,
            AscriptionStatusTransitionRepository transitionRepo,
            StatementValidatorUtil statementValidator,
            GsmAnnotationValidator gsmAnnotationValidator,
            List<AscriptionSubtypeService> subtypeServices,
            EntityManager entityManager,
            AscriptionLifecycleService lifecycleService) {
        this.archetypeRepo = archetypeRepo;
        this.definitionRepo = definitionRepo;
        this.transitionRepo = transitionRepo;
        this.statementValidator = statementValidator;
        this.gsmAnnotationValidator = gsmAnnotationValidator;
        this.subtypeServiceByType = buildSubtypeServiceMap(subtypeServices);
        this.entityManager = entityManager;
        this.lifecycleService = lifecycleService;
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
        statementValidator.validate(statement, archetypeRef);

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

        // GSM §8: enforce $gsm:* annotations on Ascription statement
        gsmAnnotationValidator.enforceOnAscription(statement, archetypeRef, definition.getId());

        AscriptionSubtypeService subtypeService = requireSubtypeService(type);
        AscriptionEntity entity = subtypeService.buildEntity(definition, archetypeRef, statement);

        // Lifecycle governance: identity-bound invariant + creation referee
        // preconditions
        lifecycleService.validateIdentityBound(entity);
        lifecycleService.validateCreationPreconditions(entity);

        AscriptionEntity saved = subtypeService.save(entity);

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
        Hibernate.initialize(entity.getDefinition());
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
                .map(e -> {
                    Hibernate.initialize(e.getDefinition());
                    return (AscriptionEntity) e;
                })
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
        return lifecycleService.transition(ascriptionId, targetStatus);
    }

    // ========================================================================
    // REPOSITORY ROUTING
    // ========================================================================

    private Page<? extends AscriptionEntity> findAllByStatus(
            DefinitionSubjectType type, AscriptionStatusType status, Pageable pageable) {
        return requireSubtypeService(type).findAllByStatus(status, pageable);
    }

    private Page<? extends AscriptionEntity> findAll(
            DefinitionSubjectType type, Pageable pageable) {
        return requireSubtypeService(type).findAll(Objects.requireNonNull(pageable, "pageable must not be null"));
    }

    private List<? extends AscriptionEntity> findAllByDefinitionId(
            DefinitionSubjectType type, UUID definitionId) {
        return requireSubtypeService(type).findAllByDefinitionId(definitionId);
    }

    private List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            DefinitionSubjectType type, UUID definitionId, Collection<AscriptionStatusType> statuses) {
        return requireSubtypeService(type).findAllByDefinitionIdAndStatus(definitionId, statuses);
    }

    private Map<DefinitionSubjectType, AscriptionSubtypeService> buildSubtypeServiceMap(
            List<AscriptionSubtypeService> subtypeServices) {
        Map<DefinitionSubjectType, AscriptionSubtypeService> byType = new HashMap<>();
        for (AscriptionSubtypeService subtypeService : subtypeServices) {
            AscriptionSubtypeService previous = byType.put(subtypeService.getSubjectType(), subtypeService);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate subtype service for " + subtypeService.getSubjectType());
            }
        }
        for (DefinitionSubjectType type : DefinitionSubjectType.values()) {
            if (!byType.containsKey(type)) {
                throw new IllegalStateException("Missing subtype service for " + type);
            }
        }
        return Map.copyOf(byType);
    }

    private AscriptionSubtypeService requireSubtypeService(DefinitionSubjectType type) {
        AscriptionSubtypeService subtypeService = subtypeServiceByType.get(type);
        if (subtypeService == null) {
            throw new IllegalStateException("No subtype service registered for " + type);
        }
        return subtypeService;
    }

    private @NonNull UUID requireUuid(UUID value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " must not be null");
    }

    /**
     * Derives DefinitionSubjectType from the archetype's
     * {@code statement.schema.title}.
     */
    private DefinitionSubjectType resolveSubjectType(ArchetypeEntity archetype) {
        JsonNode stmt = archetype.getStatement();
        if (stmt == null || !stmt.has("schema")) {
            throw new IllegalArgumentException(
                    "Cannot derive subject type: archetype has no schema: " + archetype.getId());
        }
        JsonNode schema = stmt.get("schema");
        if (!schema.has("title")) {
            throw new IllegalArgumentException(
                    "Cannot derive subject type: archetype schema has no title: " + archetype.getId());
        }
        String title = schema.get("title").asText();
        DefinitionSubjectType type = SCHEMA_TITLE_TO_SUBJECT_TYPE.get(title);
        if (type == null) {
            throw new IllegalArgumentException(
                    "Cannot derive subject type from schema title: " + title);
        }
        return type;
    }
}
