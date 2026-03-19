package cloud.poesis.sie.defman.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.EffectorEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.exception.GsmResourceNotFoundException;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.repository.EffectorRepository;
import cloud.poesis.sie.defman.type.AscriptionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import jakarta.persistence.EntityManager;

@Service
public class EffectorService extends AbstractAscriptionService {

    private final EffectorRepository effectorRepo;
    private final MechanismService mechanismService;
    private final ArchetypeService archetypeService;

    public EffectorService(
            EffectorRepository effectorRepo,
            MechanismService mechanismService,
            ArchetypeService archetypeService,
            DefinitionService definitionService,
            AscriptionStatusTransitionService transitionService,
            AscriptionRepository ascriptionRepository,
            EntityManager entityManager,
            DataProtectionService dataProtectionService) {
        super(definitionService, transitionService, ascriptionRepository, entityManager, dataProtectionService);
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
                .orElseThrow(() -> new GsmResourceNotFoundException("Effector", id));
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
    public Map<DefinitionSubjectType, AscriptionCascadeType> getCascadeTargetRoles() {
        return Map.of(DefinitionSubjectType.MECHANISM, AscriptionCascadeType.CONSTITUTIVE);
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
