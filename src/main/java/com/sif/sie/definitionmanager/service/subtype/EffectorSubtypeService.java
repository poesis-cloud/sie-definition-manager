package com.sif.sie.definitionmanager.service.subtype;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
import com.sif.sie.definitionmanager.entity.AscriptionEntity;
import com.sif.sie.definitionmanager.entity.DefinitionEntity;
import com.sif.sie.definitionmanager.entity.EffectorEntity;
import com.sif.sie.definitionmanager.repository.ArchetypeRepository;
import com.sif.sie.definitionmanager.repository.EffectorRepository;
import com.sif.sie.definitionmanager.repository.MechanismRepository;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;
import com.sif.sie.definitionmanager.type.CascadeType;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;

@Service
public class EffectorSubtypeService extends AbstractAscriptionSubtypeService {

    private final EffectorRepository effectorRepo;
    private final MechanismRepository mechanismRepo;
    private final ArchetypeRepository archetypeRepo;
    private final StatementReferenceResolver referenceResolver;

    public EffectorSubtypeService(
            EffectorRepository effectorRepo,
            MechanismRepository mechanismRepo,
            ArchetypeRepository archetypeRepo,
            StatementReferenceResolver referenceResolver) {
        this.effectorRepo = effectorRepo;
        this.mechanismRepo = mechanismRepo;
        this.archetypeRepo = archetypeRepo;
        this.referenceResolver = referenceResolver;
    }

    @Override
    public DefinitionSubjectType getSubjectType() {
        return DefinitionSubjectType.EFFECTOR;
    }

    @Override
    public AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
        return new EffectorEntity(
                definition,
                archetypeRef,
                statement,
                referenceResolver.requireRef(mechanismRepo, statement, "mechanism", "mechanism"),
                referenceResolver.requireRef(archetypeRepo, statement, "archetype", "archetype"));
    }

    @Override
    public AscriptionEntity save(AscriptionEntity entity) {
        return effectorRepo.save((EffectorEntity) entity);
    }

    @Override
    public Page<? extends AscriptionEntity> findAll(Pageable pageable) {
        return effectorRepo.findAll(pageable);
    }

    @Override
    public Page<? extends AscriptionEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable) {
        return effectorRepo.findAllByStatus(status, pageable);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionId(UUID definitionId) {
        return effectorRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return effectorRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
    }

    // ---- Lifecycle descriptors ----

    @Override
    public List<RefereeReference> getRefereeReferences(AscriptionEntity entity) {
        var e = (EffectorEntity) entity;
        return List.of(new RefereeReference(e.getOutputArchetype(), "archetype"));
    }

    @Override
    public Map<DefinitionSubjectType, CascadeType> getCascadeTargetRoles() {
        return Map.of(DefinitionSubjectType.MECHANISM, CascadeType.CONSTITUTIVE);
    }

    @Override
    public List<? extends AscriptionEntity> findCascadeTargetsFrom(
            DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
        if (sourceType == DefinitionSubjectType.MECHANISM) {
            return effectorRepo.findAllByMechanism_Id(sourceAscriptionId);
        }
        return List.of();
    }

    @Override
    public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
        var e = (EffectorEntity) entity;
        return Map.of(
                "mechanism", e.getMechanism().getDefinition().getId(),
                "archetype", e.getOutputArchetype().getDefinition().getId());
    }
}
