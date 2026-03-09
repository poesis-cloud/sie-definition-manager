package com.sif.sie.definitionmanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sif.sie.definitionmanager.client.SchemaRegistryClient;
import com.sif.sie.definitionmanager.controller.dto.AscriptionRequest;
import com.sif.sie.definitionmanager.controller.dto.AscriptionResponse;
import com.sif.sie.definitionmanager.controller.dto.TransitionRequest;
import com.sif.sie.definitionmanager.controller.dto.TransitionResponse;
import com.sif.sie.definitionmanager.entity.AbstractAscription;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
import com.sif.sie.definitionmanager.entity.AscriptionStatusTransitionEntity;
import com.sif.sie.definitionmanager.entity.DirectiveEntity;
import com.sif.sie.definitionmanager.entity.EffectorEntity;
import com.sif.sie.definitionmanager.entity.InteractionEntity;
import com.sif.sie.definitionmanager.entity.InterfaceEntity;
import com.sif.sie.definitionmanager.entity.MechanismEntity;
import com.sif.sie.definitionmanager.entity.NormEntity;
import com.sif.sie.definitionmanager.entity.ReceptorEntity;
import com.sif.sie.definitionmanager.entity.StructureEntity;
import com.sif.sie.definitionmanager.enums.AscriptionStatus;
import com.sif.sie.definitionmanager.enums.GsmType;
import com.sif.sie.definitionmanager.repository.ArchetypeRepository;
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

/**
  * Core service for unified ascription CRUD and lifecycle management.
  *
  * <p>Routes operations to the appropriate entity table based on gsmType, derived from the
  * archetype's schema URI. FK references are extracted from the {@code definition} JSON payload —
  * not from request-level fields. Manages lifecycle transitions with governance convergence
  * semantics (see gsm-ascription-lifecycle state machine).
  */
@Service
@Transactional("transactionManager")
public class AscriptionService {

    /** Maps schema URI suffix to GsmType for base archetypes. */
    private static final Map<String, GsmType> SCHEMA_URI_TO_GSM_TYPE =
            Map.of(
                    "Archetype.schema.json", GsmType.ARCHETYPE,
                    "Structure.schema.json", GsmType.STRUCTURE,
                    "Mechanism.schema.json", GsmType.MECHANISM,
                    "Interface.schema.json", GsmType.INTERFACE,
                    "Effector.schema.json", GsmType.EFFECTOR,
                    "Receptor.schema.json", GsmType.RECEPTOR,
                    "Interaction.schema.json", GsmType.INTERACTION,
                    "Directive.schema.json", GsmType.DIRECTIVE,
                    "Norm.schema.json", GsmType.NORM);

    private final ArchetypeRepository archetypeRepo;
    private final StructureRepository structureRepo;
    private final MechanismRepository mechanismRepo;
    private final InterfaceRepository interfaceRepo;
    private final EffectorRepository effectorRepo;
    private final ReceptorRepository receptorRepo;
    private final InteractionRepository interactionRepo;
    private final DirectiveRepository directiveRepo;
    private final NormRepository normRepo;
    private final StatusTransitionRepository transitionRepo;
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
        this.transitionRepo = transitionRepo;
        this.definitionValidator = definitionValidator;
        this.entityManager = entityManager;
    }

    // ========================================================================
    // CREATE
    // ========================================================================

    public AscriptionResponse create(AscriptionRequest req) {
        UUID archetypeId = requireUuid(req.archetypeId(), "archetypeId");
        ArchetypeEntity archetypeRef =
                archetypeRepo
                        .findById(archetypeId)
                        .orElseThrow(() -> new IllegalArgumentException("Archetype not found: " + archetypeId));

        GsmType type = resolveGsmType(archetypeRef);

        // Validate definition against archetype schema
        definitionValidator.validate(req.definition(), archetypeRef);

        AbstractAscription entity = buildEntity(type, req.definition(), archetypeRef);

        // For new ascriptions, id is generated; for new revisions of existing, reuse id
        if (req.id() != null) {
            entity.setId(req.id());
        }

        AbstractAscription saved = saveEntity(type, entity);

        // Record initial DRAFT transition
        AscriptionStatusTransitionEntity transition = new AscriptionStatusTransitionEntity();
        transition.setGsmType(type);
        transition.setRevisionId(saved.getRevisionId());
        transition.setPreStatus(null);
        transition.setPostStatus(AscriptionStatus.DRAFT);
        transitionRepo.save(transition);

        // Flush + refresh to load DB-generated values (status from trigger,
        // revision_timestamp)
        entityManager.flush();
        entityManager.refresh(saved);

        return toResponse(type, saved);
    }

    // ========================================================================
    // READ
    // ========================================================================

    @Transactional(value = "transactionManager", readOnly = true)
    public AscriptionResponse getByRevisionId(UUID revisionId) {
        GsmType type =
                transitionRepo
                        .findGsmTypeByRevisionId(revisionId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "No ascription found for revisionId: " + revisionId));

        AbstractAscription entity = findByRevisionId(type, revisionId);
        return toResponse(type, entity);
    }

    @Transactional(value = "transactionManager", readOnly = true)
    public Page<AscriptionResponse> list(
            String gsmTypeStr, AscriptionStatus status, Pageable pageable) {
        GsmType type = GsmType.fromValue(gsmTypeStr);
        Page<? extends AbstractAscription> page =
                (status != null) ? findAllByStatus(type, status, pageable) : findAll(type, pageable);
        return page.map(e -> toResponse(type, e));
    }

    @Transactional(value = "transactionManager", readOnly = true)
    public List<AscriptionResponse> getRevisionHistory(UUID id, String gsmTypeStr) {
        GsmType type = GsmType.fromValue(gsmTypeStr);
        return findAllRevisionsById(type, id).stream().map(e -> toResponse(type, e)).toList();
    }

    @Transactional(value = "transactionManager", readOnly = true)
    public List<TransitionResponse> getTransitions(UUID revisionId) {
        return transitionRepo.findAllByRevisionIdOrderByTimestampAsc(revisionId).stream()
                .map(
                        t ->
                                new TransitionResponse(
                                        t.getId(),
                                        t.getRevisionId(),
                                        t.getPreStatus() != null ? t.getPreStatus().name() : null,
                                        t.getPostStatus().name(),
                                        t.getTimestamp()))
                .toList();
    }

    // ========================================================================
    // LIFECYCLE TRANSITIONS
    // ========================================================================

    public TransitionResponse transition(UUID revisionId, TransitionRequest req) {
        AscriptionStatus targetStatus = AscriptionStatus.valueOf(req.targetStatus());

        GsmType type =
                transitionRepo
                        .findGsmTypeByRevisionId(revisionId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "No ascription found for revisionId: " + revisionId));

        AbstractAscription entity = findByRevisionId(type, revisionId);
        AscriptionStatus currentStatus = entity.getStatus();

        // Validate transition
        Set<AscriptionStatus> allowed =
                VALID_TRANSITIONS.getOrDefault(currentStatus, EnumSet.noneOf(AscriptionStatus.class));
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
        AscriptionStatusTransitionEntity transition = new AscriptionStatusTransitionEntity();
        transition.setGsmType(type);
        transition.setRevisionId(revisionId);
        transition.setPreStatus(currentStatus);
        transition.setPostStatus(targetStatus);
        AscriptionStatusTransitionEntity saved = transitionRepo.save(transition);

        // Flush + detach + re-read to load DB-generated timestamp
        // (refresh() not supported for @Immutable entities in Hibernate)
        entityManager.flush();
        entityManager.detach(saved);
        UUID transitionId = requireUuid(saved.getId(), "transition.id");
        saved = transitionRepo.findById(transitionId).orElseThrow();

        return new TransitionResponse(
                saved.getId(),
                saved.getRevisionId(),
                currentStatus.name(),
                targetStatus.name(),
                saved.getTimestamp());
    }

    // ========================================================================
    // GOVERNANCE CONVERGENCE
    // ========================================================================

    /**
      * Approval convergence: assign version, auto-terminate sibling revisions.
      *
      * <p>Per GSM: approving one revision auto-rejects all other non-terminal sibling revisions for
      * that id (DRAFT siblings -> ABANDONED, PROPOSED siblings -> REJECTED).
      */
    private void handleApproval(GsmType type, AbstractAscription approved) {
        UUID id = approved.getId();

        // Assign next version number
        List<? extends AbstractAscription> allRevisions = findAllRevisionsById(type, id);
        int maxVersion =
                allRevisions.stream()
                        .map(AbstractAscription::getVersion)
                        .filter(Objects::nonNull)
                        .mapToInt(Integer::intValue)
                        .max()
                        .orElse(0);
        approved.setVersion(maxVersion + 1);
        saveEntity(type, approved);

        // Auto-terminate siblings
        for (AbstractAscription sibling : allRevisions) {
            if (sibling.getRevisionId().equals(approved.getRevisionId())) continue;
            AscriptionStatus siblingStatus = sibling.getStatus();
            AscriptionStatus terminalStatus;
            if (siblingStatus == AscriptionStatus.DRAFT) {
                terminalStatus = AscriptionStatus.ABANDONED;
            } else if (siblingStatus == AscriptionStatus.PROPOSED) {
                terminalStatus = AscriptionStatus.REJECTED;
            } else {
                continue; // already terminal or in-effect — skip
            }
            AscriptionStatusTransitionEntity t = new AscriptionStatusTransitionEntity();
            t.setGsmType(type);
            t.setRevisionId(sibling.getRevisionId());
            t.setPreStatus(siblingStatus);
            t.setPostStatus(terminalStatus);
            transitionRepo.save(t);
        }
    }

    /** Activation: cascade previous ACTIVE revision to DEPRECATED. */
    private void handleActivation(GsmType type, AbstractAscription activating) {
        UUID id = activating.getId();
        List<? extends AbstractAscription> activeRevisions =
                findAllRevisionsByIdAndStatus(type, id, List.of(AscriptionStatus.ACTIVE));
        for (AbstractAscription prev : activeRevisions) {
            if (prev.getRevisionId().equals(activating.getRevisionId())) continue;
            AscriptionStatusTransitionEntity t = new AscriptionStatusTransitionEntity();
            t.setGsmType(type);
            t.setRevisionId(prev.getRevisionId());
            t.setPreStatus(AscriptionStatus.ACTIVE);
            t.setPostStatus(AscriptionStatus.DEPRECATED);
            transitionRepo.save(t);
        }
    }

    // ========================================================================
    // ENTITY BUILDING & MAPPING
    // ========================================================================

    private AbstractAscription buildEntity(
            GsmType type, JsonNode definition, ArchetypeEntity archetypeRef) {
        switch (type) {
            case ARCHETYPE -> {
                ArchetypeEntity e = new ArchetypeEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(definition);
                return e;
            }
            case STRUCTURE -> {
                StructureEntity e = new StructureEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(definition);
                return e;
            }
            case MECHANISM -> {
                MechanismEntity e = new MechanismEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(definition);
                e.setStructure(
                        requireRefFromDefinition(structureRepo, definition, "structureId", "structureId"));
                return e;
            }
            case INTERFACE -> {
                InterfaceEntity e = new InterfaceEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(definition);
                e.setStructure(
                        requireRefFromDefinition(structureRepo, definition, "structureId", "structureId"));
                return e;
            }
            case EFFECTOR -> {
                EffectorEntity e = new EffectorEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(definition);
                e.setMechanism(
                        requireRefFromDefinition(mechanismRepo, definition, "mechanismId", "mechanismId"));
                e.setPortArchetype(
                        requireRefFromDefinition(
                                archetypeRepo, definition, "portArchetypeId", "portArchetypeId"));
                InterfaceEntity iface = optionalRefFromDefinition(interfaceRepo, definition, "interfaceId");
                if (iface != null) {
                    e.setExposedBy(iface);
                }
                return e;
            }
            case RECEPTOR -> {
                ReceptorEntity e = new ReceptorEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(definition);
                e.setMechanism(
                        requireRefFromDefinition(mechanismRepo, definition, "mechanismId", "mechanismId"));
                e.setPortArchetype(
                        requireRefFromDefinition(
                                archetypeRepo, definition, "portArchetypeId", "portArchetypeId"));
                InterfaceEntity iface = optionalRefFromDefinition(interfaceRepo, definition, "interfaceId");
                if (iface != null) {
                    e.setExposedBy(iface);
                }
                return e;
            }
            case INTERACTION -> {
                InteractionEntity e = new InteractionEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(definition);
                e.setEffector(
                        requireRefFromDefinition(effectorRepo, definition, "effectorId", "effectorId"));
                e.setReceptor(
                        requireRefFromDefinition(receptorRepo, definition, "receptorId", "receptorId"));
                return e;
            }
            case DIRECTIVE -> {
                DirectiveEntity e = new DirectiveEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(definition);
                e.setStructure(
                        requireRefFromDefinition(structureRepo, definition, "structureId", "structureId"));
                e.setQualifier(
                        requireRefFromDefinition(archetypeRepo, definition, "qualifierId", "qualifierId"));
                StructureEntity purpose = optionalRefFromDefinition(structureRepo, definition, "purposeId");
                if (purpose != null) {
                    e.setPurpose(purpose);
                }
                return e;
            }
            case NORM -> {
                NormEntity e = new NormEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(definition);
                e.setStructure(
                        requireRefFromDefinition(structureRepo, definition, "structureId", "structureId"));
                return e;
            }
            default -> throw new IllegalArgumentException("Unknown gsmType: " + type);
        }
    }

    private AscriptionResponse toResponse(GsmType type, AbstractAscription entity) {
        String schemaUri = null;
        if (type == GsmType.ARCHETYPE) {
            schemaUri = ((ArchetypeEntity) entity).getSchemaUri();
        }

        return new AscriptionResponse(
                type.getValue(),
                entity.getId(),
                entity.getRevisionId(),
                entity.getRevisionTimestamp(),
                entity.getArchetype().getRevisionId(),
                entity.getDefinition(),
                entity.getVersion(),
                entity.getStatus() != null ? entity.getStatus().name() : "DRAFT",
                schemaUri);
    }

    // ========================================================================
    // REPOSITORY ROUTING
    // ========================================================================

    @SuppressWarnings("unchecked")
    private <T extends AbstractAscription> T findByRevisionId(GsmType type, UUID revisionId) {
        JpaRepository<? extends AbstractAscription, UUID> repo = repoFor(type);
        return (T)
                repo.findById(requireUuid(revisionId, "revisionId"))
                        .orElseThrow(
                                () -> new IllegalArgumentException(type.getValue() + " not found: " + revisionId));
    }

    private Page<? extends AbstractAscription> findAllByStatus(
            GsmType type, AscriptionStatus status, Pageable pageable) {
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

    private Page<? extends AbstractAscription> findAll(GsmType type, Pageable pageable) {
        return repoFor(type).findAll(Objects.requireNonNull(pageable, "pageable must not be null"));
    }

    private List<? extends AbstractAscription> findAllRevisionsById(GsmType type, UUID id) {
        return switch (type) {
            case ARCHETYPE -> archetypeRepo.findAllByIdOrderByRevisionTimestampDesc(id);
            case STRUCTURE -> structureRepo.findAllByIdOrderByRevisionTimestampDesc(id);
            case MECHANISM -> mechanismRepo.findAllByIdOrderByRevisionTimestampDesc(id);
            case INTERFACE -> interfaceRepo.findAllByIdOrderByRevisionTimestampDesc(id);
            case EFFECTOR -> effectorRepo.findAllByIdOrderByRevisionTimestampDesc(id);
            case RECEPTOR -> receptorRepo.findAllByIdOrderByRevisionTimestampDesc(id);
            case INTERACTION -> interactionRepo.findAllByIdOrderByRevisionTimestampDesc(id);
            case DIRECTIVE -> directiveRepo.findAllByIdOrderByRevisionTimestampDesc(id);
            case NORM -> normRepo.findAllByIdOrderByRevisionTimestampDesc(id);
        };
    }

    private List<? extends AbstractAscription> findAllRevisionsByIdAndStatus(
            GsmType type, UUID id, Collection<AscriptionStatus> statuses) {
        return switch (type) {
            case ARCHETYPE -> archetypeRepo.findAllByIdAndStatusIn(id, statuses);
            case STRUCTURE -> structureRepo.findAllByIdAndStatusIn(id, statuses);
            case MECHANISM -> mechanismRepo.findAllByIdAndStatusIn(id, statuses);
            case INTERFACE -> interfaceRepo.findAllByIdAndStatusIn(id, statuses);
            case EFFECTOR -> effectorRepo.findAllByIdAndStatusIn(id, statuses);
            case RECEPTOR -> receptorRepo.findAllByIdAndStatusIn(id, statuses);
            case INTERACTION -> interactionRepo.findAllByIdAndStatusIn(id, statuses);
            case DIRECTIVE -> directiveRepo.findAllByIdAndStatusIn(id, statuses);
            case NORM -> normRepo.findAllByIdAndStatusIn(id, statuses);
        };
    }

    private AbstractAscription saveEntity(GsmType type, AbstractAscription entity) {
        return switch (type) {
            case ARCHETYPE -> archetypeRepo.save(Objects.requireNonNull((ArchetypeEntity) entity));
            case STRUCTURE -> structureRepo.save(Objects.requireNonNull((StructureEntity) entity));
            case MECHANISM -> mechanismRepo.save(Objects.requireNonNull((MechanismEntity) entity));
            case INTERFACE -> interfaceRepo.save(Objects.requireNonNull((InterfaceEntity) entity));
            case EFFECTOR -> effectorRepo.save(Objects.requireNonNull((EffectorEntity) entity));
            case RECEPTOR -> receptorRepo.save(Objects.requireNonNull((ReceptorEntity) entity));
            case INTERACTION -> interactionRepo.save(Objects.requireNonNull((InteractionEntity) entity));
            case DIRECTIVE -> directiveRepo.save(Objects.requireNonNull((DirectiveEntity) entity));
            case NORM -> normRepo.save(Objects.requireNonNull((NormEntity) entity));
        };
    }

    private JpaRepository<? extends AbstractAscription, UUID> repoFor(GsmType type) {
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

    /** Extracts a required UUID FK reference from the definition JSON payload. */
    private <T> T requireRefFromDefinition(
            JpaRepository<T, UUID> repo, JsonNode definition, String jsonField, String displayName) {
        JsonNode node = definition.get(jsonField);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "Required field '" + jsonField + "' missing in definition payload");
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

    /** Extracts an optional UUID FK reference from the definition JSON payload. */
    private <T> T optionalRefFromDefinition(
            JpaRepository<T, UUID> repo, JsonNode definition, String jsonField) {
        JsonNode node = definition.get(jsonField);
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
      * Derives GsmType from the archetype's schema URI.
      *
      * <p>Base archetypes have URIs like {@code urn:sie:gsm:v1:Structure.schema.json}. The suffix
      * after the last ':' is matched against the lookup map.
      */
    private GsmType resolveGsmType(ArchetypeEntity archetype) {
        String schemaUri = archetype.getSchemaUri();
        if (schemaUri == null) {
            JsonNode def = archetype.getDefinition();
            if (def != null && def.has("schemaUri")) {
                schemaUri = def.get("schemaUri").asText();
            }
        }
        if (schemaUri == null) {
            throw new IllegalArgumentException(
                    "Cannot derive GSM type: archetype has no schema URI: " + archetype.getRevisionId());
        }
        int lastColon = schemaUri.lastIndexOf(':');
        String suffix = (lastColon >= 0) ? schemaUri.substring(lastColon + 1) : schemaUri;
        GsmType type = SCHEMA_URI_TO_GSM_TYPE.get(suffix);
        if (type == null) {
            throw new IllegalArgumentException("Cannot derive GSM type from schema URI: " + schemaUri);
        }
        return type;
    }
}
