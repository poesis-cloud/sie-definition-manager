package org.sif.sie.dm.service;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.sif.sie.dm.api.dto.AscriptionRequest;
import org.sif.sie.dm.api.dto.AscriptionResponse;
import org.sif.sie.dm.api.dto.TransitionRequest;
import org.sif.sie.dm.api.dto.TransitionResponse;
import org.sif.sie.dm.model.entity.AbstractAscription;
import org.sif.sie.dm.model.entity.ArchetypeEntity;
import org.sif.sie.dm.model.entity.AscriptionStatusTransitionEntity;
import org.sif.sie.dm.model.entity.DirectiveEntity;
import org.sif.sie.dm.model.entity.EffectorEntity;
import org.sif.sie.dm.model.entity.InteractionEntity;
import org.sif.sie.dm.model.entity.InterfaceEntity;
import org.sif.sie.dm.model.entity.MechanismEntity;
import org.sif.sie.dm.model.entity.NormEntity;
import org.sif.sie.dm.model.entity.ReceptorEntity;
import org.sif.sie.dm.model.entity.StructureEntity;
import org.sif.sie.dm.model.enums.AscriptionStatus;
import org.sif.sie.dm.model.enums.GsmType;
import org.sif.sie.dm.registry.SchemaRegistryClient;
import org.sif.sie.dm.repository.ArchetypeRepository;
import org.sif.sie.dm.repository.DirectiveRepository;
import org.sif.sie.dm.repository.EffectorRepository;
import org.sif.sie.dm.repository.InteractionRepository;
import org.sif.sie.dm.repository.InterfaceRepository;
import org.sif.sie.dm.repository.MechanismRepository;
import org.sif.sie.dm.repository.NormRepository;
import org.sif.sie.dm.repository.ReceptorRepository;
import org.sif.sie.dm.repository.StatusTransitionRepository;
import org.sif.sie.dm.repository.StructureRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

/**
 * Core service for unified ascription CRUD and lifecycle management.
 * <p>
 * Routes operations to the appropriate entity table based on gsmType.
 * Manages lifecycle transitions with governance convergence semantics
 * (see gsm-ascription-lifecycle state machine).
 */
@Service
@Transactional("transactionManager")
public class AscriptionService {

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
    private final SchemaRegistryClient schemaRegistry;
    private final EntityManager entityManager;

    // Valid lifecycle transitions: current status -> set of allowed target statuses
    private static final Map<AscriptionStatus, Set<AscriptionStatus>> VALID_TRANSITIONS;
    static {
        VALID_TRANSITIONS = new EnumMap<>(AscriptionStatus.class);
        VALID_TRANSITIONS.put(AscriptionStatus.DRAFT,
                EnumSet.of(AscriptionStatus.PROPOSED, AscriptionStatus.ABANDONED));
        VALID_TRANSITIONS.put(AscriptionStatus.PROPOSED,
                EnumSet.of(AscriptionStatus.APPROVED, AscriptionStatus.REJECTED));
        VALID_TRANSITIONS.put(AscriptionStatus.APPROVED,
                EnumSet.of(AscriptionStatus.ACTIVE));
        VALID_TRANSITIONS.put(AscriptionStatus.ACTIVE,
                EnumSet.of(AscriptionStatus.SUSPENDED, AscriptionStatus.DEPRECATED));
        VALID_TRANSITIONS.put(AscriptionStatus.SUSPENDED,
                EnumSet.of(AscriptionStatus.ACTIVE, AscriptionStatus.DEPRECATED));
        VALID_TRANSITIONS.put(AscriptionStatus.DEPRECATED,
                EnumSet.of(AscriptionStatus.RETIRED));
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
            SchemaRegistryClient schemaRegistry,
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
        this.schemaRegistry = schemaRegistry;
        this.entityManager = entityManager;
    }

    // ========================================================================
    // CREATE
    // ========================================================================

    public AscriptionResponse create(AscriptionRequest req) {
        GsmType type = GsmType.fromValue(req.gsmType());
        ArchetypeEntity archetypeRef = archetypeRepo.findById(req.archetypeId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Archetype not found: " + req.archetypeId()));

        AbstractAscription entity = buildEntity(type, req, archetypeRef);

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
        GsmType type = transitionRepo.findGsmTypeByRevisionId(revisionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No ascription found for revisionId: " + revisionId));

        AbstractAscription entity = findByRevisionId(type, revisionId);
        return toResponse(type, entity);
    }

    @Transactional(value = "transactionManager", readOnly = true)
    public Page<AscriptionResponse> list(String gsmTypeStr, AscriptionStatus status, Pageable pageable) {
        GsmType type = GsmType.fromValue(gsmTypeStr);
        Page<? extends AbstractAscription> page = (status != null)
                ? findAllByStatus(type, status, pageable)
                : findAll(type, pageable);
        return page.map(e -> toResponse(type, e));
    }

    @Transactional(value = "transactionManager", readOnly = true)
    public List<AscriptionResponse> getRevisionHistory(UUID revisionId) {
        GsmType type = transitionRepo.findGsmTypeByRevisionId(revisionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No ascription found for revisionId: " + revisionId));

        AbstractAscription entity = findByRevisionId(type, revisionId);
        UUID id = entity.getId();

        return findAllRevisionsById(type, id).stream()
                .map(e -> toResponse(type, e))
                .toList();
    }

    @Transactional(value = "transactionManager", readOnly = true)
    public List<TransitionResponse> getTransitions(UUID revisionId) {
        return transitionRepo.findAllByRevisionIdOrderByTimestampAsc(revisionId)
                .stream()
                .map(t -> new TransitionResponse(
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

        GsmType type = transitionRepo.findGsmTypeByRevisionId(revisionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No ascription found for revisionId: " + revisionId));

        AbstractAscription entity = findByRevisionId(type, revisionId);
        AscriptionStatus currentStatus = entity.getStatus();

        // Validate transition
        Set<AscriptionStatus> allowed = VALID_TRANSITIONS.getOrDefault(
                currentStatus, EnumSet.noneOf(AscriptionStatus.class));
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
        saved = transitionRepo.findById(saved.getId()).orElseThrow();

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
     * <p>
     * Per GSM: approving one revision auto-rejects all other non-terminal
     * sibling revisions for that id (DRAFT siblings -> ABANDONED,
     * PROPOSED siblings -> REJECTED).
     */
    private void handleApproval(GsmType type, AbstractAscription approved) {
        UUID id = approved.getId();

        // Assign next version number
        List<? extends AbstractAscription> allRevisions = findAllRevisionsById(type, id);
        int maxVersion = allRevisions.stream()
                .map(AbstractAscription::getVersion)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        approved.setVersion(maxVersion + 1);
        saveEntity(type, approved);

        // Auto-terminate siblings
        for (AbstractAscription sibling : allRevisions) {
            if (sibling.getRevisionId().equals(approved.getRevisionId()))
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
            AscriptionStatusTransitionEntity t = new AscriptionStatusTransitionEntity();
            t.setGsmType(type);
            t.setRevisionId(sibling.getRevisionId());
            t.setPreStatus(siblingStatus);
            t.setPostStatus(terminalStatus);
            transitionRepo.save(t);
        }
    }

    /**
     * Activation: cascade previous ACTIVE revision to DEPRECATED.
     */
    private void handleActivation(GsmType type, AbstractAscription activating) {
        UUID id = activating.getId();
        List<? extends AbstractAscription> activeRevisions = findAllRevisionsByIdAndStatus(
                type, id, List.of(AscriptionStatus.ACTIVE));
        for (AbstractAscription prev : activeRevisions) {
            if (prev.getRevisionId().equals(activating.getRevisionId()))
                continue;
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

    private AbstractAscription buildEntity(GsmType type, AscriptionRequest req, ArchetypeEntity archetypeRef) {
        switch (type) {
            case ARCHETYPE -> {
                ArchetypeEntity e = new ArchetypeEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(req.definition());
                return e;
            }
            case STRUCTURE -> {
                StructureEntity e = new StructureEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(req.definition());
                return e;
            }
            case MECHANISM -> {
                MechanismEntity e = new MechanismEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(req.definition());
                e.setStructure(requireRef(structureRepo, req.structureId(), "structureId"));
                return e;
            }
            case INTERFACE -> {
                InterfaceEntity e = new InterfaceEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(req.definition());
                e.setStructure(requireRef(structureRepo, req.structureId(), "structureId"));
                return e;
            }
            case EFFECTOR -> {
                EffectorEntity e = new EffectorEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(req.definition());
                e.setMechanism(requireRef(mechanismRepo, req.mechanismId(), "mechanismId"));
                e.setPortArchetype(requireRef(archetypeRepo, req.portArchetypeId(), "portArchetypeId"));
                if (req.interfaceId() != null) {
                    e.setExposedBy(requireRef(interfaceRepo, req.interfaceId(), "interfaceId"));
                }
                return e;
            }
            case RECEPTOR -> {
                ReceptorEntity e = new ReceptorEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(req.definition());
                e.setMechanism(requireRef(mechanismRepo, req.mechanismId(), "mechanismId"));
                e.setPortArchetype(requireRef(archetypeRepo, req.portArchetypeId(), "portArchetypeId"));
                if (req.interfaceId() != null) {
                    e.setExposedBy(requireRef(interfaceRepo, req.interfaceId(), "interfaceId"));
                }
                return e;
            }
            case INTERACTION -> {
                InteractionEntity e = new InteractionEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(req.definition());
                e.setEffector(requireRef(effectorRepo, req.effectorId(), "effectorId"));
                e.setReceptor(requireRef(receptorRepo, req.receptorId(), "receptorId"));
                return e;
            }
            case DIRECTIVE -> {
                DirectiveEntity e = new DirectiveEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(req.definition());
                e.setStructure(requireRef(structureRepo, req.structureId(), "structureId"));
                e.setQualifier(requireRef(archetypeRepo, req.qualifierId(), "qualifierId"));
                if (req.purposeId() != null) {
                    e.setPurpose(requireRef(structureRepo, req.purposeId(), "purposeId"));
                }
                return e;
            }
            case NORM -> {
                NormEntity e = new NormEntity();
                e.setArchetype(archetypeRef);
                e.setDefinition(req.definition());
                e.setStructure(requireRef(structureRepo, req.structureId(), "structureId"));
                return e;
            }
            default -> throw new IllegalArgumentException("Unknown gsmType: " + type);
        }
    }

    @SuppressWarnings("unchecked")
    private AscriptionResponse toResponse(GsmType type, AbstractAscription entity) {
        UUID structureId = null, mechanismId = null, portArchetypeId = null,
                interfaceId = null, qualifierId = null, purposeId = null,
                effectorId = null, receptorId = null;
        String schemaUri = null;

        switch (type) {
            case ARCHETYPE -> schemaUri = ((ArchetypeEntity) entity).getSchemaUri();
            case MECHANISM -> structureId = ((MechanismEntity) entity).getStructure().getRevisionId();
            case INTERFACE -> structureId = ((InterfaceEntity) entity).getStructure().getRevisionId();
            case EFFECTOR -> {
                EffectorEntity eff = (EffectorEntity) entity;
                mechanismId = eff.getMechanism().getRevisionId();
                portArchetypeId = eff.getPortArchetype().getRevisionId();
                if (eff.getExposedBy() != null)
                    interfaceId = eff.getExposedBy().getRevisionId();
            }
            case RECEPTOR -> {
                ReceptorEntity rec = (ReceptorEntity) entity;
                mechanismId = rec.getMechanism().getRevisionId();
                portArchetypeId = rec.getPortArchetype().getRevisionId();
                if (rec.getExposedBy() != null)
                    interfaceId = rec.getExposedBy().getRevisionId();
            }
            case INTERACTION -> {
                InteractionEntity ix = (InteractionEntity) entity;
                effectorId = ix.getEffector().getRevisionId();
                receptorId = ix.getReceptor().getRevisionId();
            }
            case DIRECTIVE -> {
                DirectiveEntity dir = (DirectiveEntity) entity;
                structureId = dir.getStructure().getRevisionId();
                qualifierId = dir.getQualifier().getRevisionId();
                if (dir.getPurpose() != null)
                    purposeId = dir.getPurpose().getRevisionId();
            }
            case NORM -> structureId = ((NormEntity) entity).getStructure().getRevisionId();
            default -> {
            }
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
                schemaUri,
                structureId, mechanismId, portArchetypeId, interfaceId,
                qualifierId, purposeId, effectorId, receptorId);
    }

    // ========================================================================
    // REPOSITORY ROUTING
    // ========================================================================

    @SuppressWarnings("unchecked")
    private <T extends AbstractAscription> T findByRevisionId(GsmType type, UUID revisionId) {
        JpaRepository<? extends AbstractAscription, UUID> repo = repoFor(type);
        return (T) repo.findById(revisionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        type.getValue() + " not found: " + revisionId));
    }

    private Page<? extends AbstractAscription> findAllByStatus(GsmType type, AscriptionStatus status,
            Pageable pageable) {
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
        return repoFor(type).findAll(pageable);
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

    @SuppressWarnings("unchecked")
    private AbstractAscription saveEntity(GsmType type, AbstractAscription entity) {
        return switch (type) {
            case ARCHETYPE -> archetypeRepo.save((ArchetypeEntity) entity);
            case STRUCTURE -> structureRepo.save((StructureEntity) entity);
            case MECHANISM -> mechanismRepo.save((MechanismEntity) entity);
            case INTERFACE -> interfaceRepo.save((InterfaceEntity) entity);
            case EFFECTOR -> effectorRepo.save((EffectorEntity) entity);
            case RECEPTOR -> receptorRepo.save((ReceptorEntity) entity);
            case INTERACTION -> interactionRepo.save((InteractionEntity) entity);
            case DIRECTIVE -> directiveRepo.save((DirectiveEntity) entity);
            case NORM -> normRepo.save((NormEntity) entity);
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

    private <T> T requireRef(JpaRepository<T, UUID> repo, UUID refId, String fieldName) {
        if (refId == null) {
            throw new IllegalArgumentException("Required FK reference missing: " + fieldName);
        }
        return repo.findById(refId)
                .orElseThrow(() -> new IllegalArgumentException(
                        fieldName + " not found: " + refId));
    }
}
