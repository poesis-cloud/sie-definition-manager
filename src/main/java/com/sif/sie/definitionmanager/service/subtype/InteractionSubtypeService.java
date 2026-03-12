package com.sif.sie.definitionmanager.service.subtype;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
import com.sif.sie.definitionmanager.entity.AscriptionEntity;
import com.sif.sie.definitionmanager.entity.DefinitionEntity;
import com.sif.sie.definitionmanager.entity.InteractionEntity;
import com.sif.sie.definitionmanager.repository.EffectorRepository;
import com.sif.sie.definitionmanager.repository.InteractionRepository;
import com.sif.sie.definitionmanager.repository.ReceptorRepository;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;
import com.sif.sie.definitionmanager.type.CascadeType;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;

import java.util.Map;

@Service
public class InteractionSubtypeService extends AbstractAscriptionSubtypeService {

    private final InteractionRepository interactionRepo;
    private final EffectorRepository effectorRepo;
    private final ReceptorRepository receptorRepo;
    private final StatementReferenceResolver referenceResolver;

    public InteractionSubtypeService(
            InteractionRepository interactionRepo,
            EffectorRepository effectorRepo,
            ReceptorRepository receptorRepo,
            StatementReferenceResolver referenceResolver) {
        this.interactionRepo = interactionRepo;
        this.effectorRepo = effectorRepo;
        this.receptorRepo = receptorRepo;
        this.referenceResolver = referenceResolver;
    }

    @Override
    public DefinitionSubjectType getSubjectType() {
        return DefinitionSubjectType.INTERACTION;
    }

    @Override
    public AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
        var effector = referenceResolver.requireRef(effectorRepo, statement, "effector", "effector");
        var receptor = referenceResolver.requireRef(receptorRepo, statement, "receptor", "receptor");

        // GSM Interaction validation: effector.archetype must be schema-compatible with
        // receptor.archetype
        UUID effArchDefId = effector.getOutputArchetype().getDefinition().getId();
        UUID recArchDefId = receptor.getInputArchetype().getDefinition().getId();
        if (!effArchDefId.equals(recArchDefId)) {
            throw new IllegalArgumentException(
                    "Interaction archetype mismatch: effector output archetype (definition "
                            + effArchDefId + ") is not compatible with receptor input archetype (definition "
                            + recArchDefId + ")");
        }

        return new InteractionEntity(
                definition,
                archetypeRef,
                statement,
                effector,
                receptor);
    }

    @Override
    public AscriptionEntity save(AscriptionEntity entity) {
        return interactionRepo.save((InteractionEntity) entity);
    }

    @Override
    public Page<? extends AscriptionEntity> findAll(Pageable pageable) {
        return interactionRepo.findAll(pageable);
    }

    @Override
    public Page<? extends AscriptionEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable) {
        return interactionRepo.findAllByStatus(status, pageable);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionId(UUID definitionId) {
        return interactionRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return interactionRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
    }

    // ---- Lifecycle descriptors ----

    @Override
    public List<RefereeReference> getRefereeReferences(AscriptionEntity entity) {
        var i = (InteractionEntity) entity;
        return List.of(
                new RefereeReference(i.getEffector(), "effector"),
                new RefereeReference(i.getReceptor(), "receptor"));
    }

    @Override
    public Map<DefinitionSubjectType, CascadeType> getCascadeTargetRoles() {
        return Map.of(
                DefinitionSubjectType.EFFECTOR, CascadeType.DEPENDENT,
                DefinitionSubjectType.RECEPTOR, CascadeType.DEPENDENT);
    }

    @Override
    public List<? extends AscriptionEntity> findCascadeTargetsFrom(
            DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
        return switch (sourceType) {
            case EFFECTOR -> interactionRepo.findAllByEffector_Id(sourceAscriptionId);
            case RECEPTOR -> interactionRepo.findAllByReceptor_Id(sourceAscriptionId);
            default -> List.of();
        };
    }

    @Override
    public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
        var i = (InteractionEntity) entity;
        return Map.of(
                "effector", i.getEffector().getDefinition().getId(),
                "receptor", i.getReceptor().getDefinition().getId());
    }
}
