package io.poesis.sie.defman.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import io.poesis.sie.defman.entity.ArchetypeEntity;
import io.poesis.sie.defman.entity.AscriptionEntity;
import io.poesis.sie.defman.entity.DefinitionEntity;
import io.poesis.sie.defman.entity.MechanismEntity;
import io.poesis.sie.defman.entity.ReceptorEntity;
import io.poesis.sie.defman.repository.ReceptorRepository;
import io.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import io.poesis.sie.defman.type.AscriptionStatusType;
import io.poesis.sie.defman.type.DefinitionSubjectType;

@Service
public class ReceptorService extends AbstractAscriptionService {

    private final ReceptorRepository receptorRepo;
    private final MechanismService mechanismService;
    private final ArchetypeService archetypeService;

    public ReceptorService(
            ReceptorRepository receptorRepo,
            MechanismService mechanismService,
            ArchetypeService archetypeService) {
        this.receptorRepo = receptorRepo;
        this.mechanismService = mechanismService;
        this.archetypeService = archetypeService;
    }

    @Override
    public DefinitionSubjectType getSubjectType() {
        return DefinitionSubjectType.RECEPTOR;
    }

    @Override
    public AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
        UUID mechanismId = extractRequiredUuid(statement, "mechanism");
        MechanismEntity mechanism = mechanismService.findEntityById(mechanismId);

        UUID dataArchetypeId = extractRequiredUuid(statement, "archetype");
        ArchetypeEntity dataArchetype = archetypeService.findEntityById(dataArchetypeId);

        return new ReceptorEntity(
                definition,
                archetypeRef,
                statement,
                mechanism,
                dataArchetype);
    }

    @Override
    public AscriptionEntity save(AscriptionEntity entity) {
        return receptorRepo.save((ReceptorEntity) entity);
    }

    public ReceptorEntity findEntityById(UUID id) {
        return receptorRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Receptor not found: " + id));
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
        return receptorRepo.findAllByDefinitionIdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return receptorRepo.findAllByDefinitionIdAndStatusIn(definitionId, statuses);
    }

    // ---- Lifecycle descriptors ----

    @Override
    public List<RefereeReference> getRefereeReferences(AscriptionEntity entity) {
        var r = (ReceptorEntity) entity;
        return List.of(new RefereeReference(r.getInputArchetype(), "archetype"));
    }

    @Override
    public Map<DefinitionSubjectType, AscriptionStatusTransitionCascadeType> getCascadeTargetRoles() {
        return Map.of(DefinitionSubjectType.MECHANISM, AscriptionStatusTransitionCascadeType.CONSTITUTIVE);
    }

    @Override
    public List<? extends AscriptionEntity> findCascadeTargetsFrom(
            DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
        if (sourceType == DefinitionSubjectType.MECHANISM) {
            return receptorRepo.findAllByMechanismId(sourceAscriptionId);
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
