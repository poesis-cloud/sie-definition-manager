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
import com.sif.sie.definitionmanager.entity.ReceptorEntity;
import com.sif.sie.definitionmanager.repository.ArchetypeRepository;
import com.sif.sie.definitionmanager.repository.MechanismRepository;
import com.sif.sie.definitionmanager.repository.ReceptorRepository;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;
import com.sif.sie.definitionmanager.type.CascadeType;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;

import java.util.Map;

@Service
public class ReceptorSubtypeService extends AbstractAscriptionSubtypeService {

    private final ReceptorRepository receptorRepo;
    private final MechanismRepository mechanismRepo;
    private final ArchetypeRepository archetypeRepo;
    private final StatementReferenceResolver referenceResolver;

    public ReceptorSubtypeService(
            ReceptorRepository receptorRepo,
            MechanismRepository mechanismRepo,
            ArchetypeRepository archetypeRepo,
            StatementReferenceResolver referenceResolver) {
        this.receptorRepo = receptorRepo;
        this.mechanismRepo = mechanismRepo;
        this.archetypeRepo = archetypeRepo;
        this.referenceResolver = referenceResolver;
    }

    @Override
    public DefinitionSubjectType getSubjectType() {
        return DefinitionSubjectType.RECEPTOR;
    }

    @Override
    public AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
        return new ReceptorEntity(
                definition,
                archetypeRef,
                statement,
                referenceResolver.requireRef(mechanismRepo, statement, "mechanism", "mechanism"),
                referenceResolver.requireRef(archetypeRepo, statement, "archetype", "archetype"));
    }

    @Override
    public AscriptionEntity save(AscriptionEntity entity) {
        return receptorRepo.save((ReceptorEntity) entity);
    }

    @Override
    public Page<? extends AscriptionEntity> findAll(Pageable pageable) {
        return receptorRepo.findAll(pageable);
    }

    @Override
    public Page<? extends AscriptionEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable) {
        return receptorRepo.findAllByStatus(status, pageable);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionId(UUID definitionId) {
        return receptorRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return receptorRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
    }

    // ---- Lifecycle descriptors ----

    @Override
    public List<RefereeReference> getRefereeReferences(AscriptionEntity entity) {
        var r = (ReceptorEntity) entity;
        return List.of(new RefereeReference(r.getInputArchetype(), "archetype"));
    }

    @Override
    public Map<DefinitionSubjectType, CascadeType> getCascadeTargetRoles() {
        return Map.of(DefinitionSubjectType.MECHANISM, CascadeType.CONSTITUTIVE);
    }

    @Override
    public List<? extends AscriptionEntity> findCascadeTargetsFrom(
            DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
        if (sourceType == DefinitionSubjectType.MECHANISM) {
            return receptorRepo.findAllByMechanism_Id(sourceAscriptionId);
        }
        return List.of();
    }

    @Override
    public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
        var r = (ReceptorEntity) entity;
        return Map.of(
                "mechanism", r.getMechanism().getDefinition().getId(),
                "archetype", r.getInputArchetype().getDefinition().getId());
    }
}
