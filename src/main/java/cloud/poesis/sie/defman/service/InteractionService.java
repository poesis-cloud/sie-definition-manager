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
import cloud.poesis.sie.defman.entity.InteractionEntity;
import cloud.poesis.sie.defman.entity.ReceptorEntity;
import cloud.poesis.sie.defman.repository.InteractionRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;

@Service
public class InteractionService extends AbstractAscriptionService {

    private final InteractionRepository interactionRepo;
    private final EffectorService effectorService;
    private final ReceptorService receptorService;

    public InteractionService(
            InteractionRepository interactionRepo,
            EffectorService effectorService,
            ReceptorService receptorService) {
        this.interactionRepo = interactionRepo;
        this.effectorService = effectorService;
        this.receptorService = receptorService;
    }

    @Override
    public DefinitionSubjectType getSubjectType() {
        return DefinitionSubjectType.INTERACTION;
    }

    @Override
    public AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
        UUID effectorId = extractRequiredUuid(statement, "effector");
        EffectorEntity effector = effectorService.findEntityById(effectorId);

        UUID receptorId = extractRequiredUuid(statement, "receptor");
        ReceptorEntity receptor = receptorService.findEntityById(receptorId);

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
        return interactionRepo.findAllByDefinitionIdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return interactionRepo.findAllByDefinitionIdAndStatusIn(definitionId, statuses);
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
    public Map<DefinitionSubjectType, AscriptionStatusTransitionCascadeType> getCascadeTargetRoles() {
        return Map.of(
                DefinitionSubjectType.EFFECTOR, AscriptionStatusTransitionCascadeType.DEPENDENT,
                DefinitionSubjectType.RECEPTOR, AscriptionStatusTransitionCascadeType.DEPENDENT);
    }

    @Override
    public List<? extends AscriptionEntity> findCascadeTargetsFrom(
            DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
        return switch (sourceType) {
            case EFFECTOR -> interactionRepo.findAllByEffectorId(sourceAscriptionId);
            case RECEPTOR -> interactionRepo.findAllByReceptorId(sourceAscriptionId);
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
