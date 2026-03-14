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
import io.poesis.sie.defman.entity.EffectorEntity;
import io.poesis.sie.defman.entity.MechanismEntity;
import io.poesis.sie.defman.repository.EffectorRepository;
import io.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import io.poesis.sie.defman.type.AscriptionStatusType;
import io.poesis.sie.defman.type.DefinitionSubjectType;

@Service
public class EffectorService extends AbstractAscriptionService {

    private final EffectorRepository effectorRepo;
    private final MechanismService mechanismService;
    private final ArchetypeService archetypeService;

    public EffectorService(
            EffectorRepository effectorRepo,
            MechanismService mechanismService,
            ArchetypeService archetypeService) {
        this.effectorRepo = effectorRepo;
        this.mechanismService = mechanismService;
        this.archetypeService = archetypeService;
    }

    @Override
    public DefinitionSubjectType getSubjectType() {
        return DefinitionSubjectType.EFFECTOR;
    }

    @Override
    public AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
        UUID mechanismId = extractRequiredUuid(statement, "mechanism");
        MechanismEntity mechanism = mechanismService.findEntityById(mechanismId);

        UUID dataArchetypeId = extractRequiredUuid(statement, "archetype");
        ArchetypeEntity dataArchetype = archetypeService.findEntityById(dataArchetypeId);

        return new EffectorEntity(
                definition,
                archetypeRef,
                statement,
                mechanism,
                dataArchetype);
    }

    @Override
    public AscriptionEntity save(AscriptionEntity entity) {
        return effectorRepo.save((EffectorEntity) entity);
    }

    public EffectorEntity findEntityById(UUID id) {
        return effectorRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Effector not found: " + id));
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
        return effectorRepo.findAllByDefinitionIdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return effectorRepo.findAllByDefinitionIdAndStatusIn(definitionId, statuses);
    }

    // ---- Lifecycle descriptors ----

    @Override
    public List<RefereeReference> getRefereeReferences(AscriptionEntity entity) {
        var e = (EffectorEntity) entity;
        return List.of(new RefereeReference(e.getOutputArchetype(), "archetype"));
    }

    @Override
    public Map<DefinitionSubjectType, AscriptionStatusTransitionCascadeType> getCascadeTargetRoles() {
        return Map.of(DefinitionSubjectType.MECHANISM, AscriptionStatusTransitionCascadeType.CONSTITUTIVE);
    }

    @Override
    public List<? extends AscriptionEntity> findCascadeTargetsFrom(
            DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
        if (sourceType == DefinitionSubjectType.MECHANISM) {
            return effectorRepo.findAllByMechanismId(sourceAscriptionId);
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
