package cloud.poesis.sie.defman.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.EffectorEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.repository.EffectorRepository;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import jakarta.persistence.EntityManager;

/**
 * GSM Effector ascription service.
 *
 * <p>
 * Manages lifecycle and persistence of {@link EffectorEntity} ascriptions
 * with constitutive cascade from owning Mechanism and dependent cascade
 * to downstream Interactions.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class EffectorService extends AbstractAscriptionService<EffectorEntity> {

    private final EffectorRepository effectorRepo;
    private final MechanismService mechanismService;
    private final ArchetypeService archetypeService;

    /**
     * Constructs the Effector service with its required dependencies.
     *
     * @param effectorRepo          the effector repository
     * @param mechanismService      the mechanism service for reference resolution
     * @param archetypeService      the archetype service for data archetype
     *                              resolution
     * @param definitionService     the definition service
     * @param transitionService     the status transition service
     * @param ascriptionRepository  the base ascription repository
     * @param entityManager         the JPA entity manager
     * @param dataProtectionService the data protection service
     */
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
    protected AbstractAscriptionRepository<EffectorEntity> getRepository() {
        return effectorRepo;
    }

    @Override
    public EffectorEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
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

    /**
     * Finds an Effector entity by its ascription id.
     *
     * @param id the ascription UUID
     * @return the effector entity
     * @throws ResourceNotFoundException if no effector exists with the given id
     */
    public EffectorEntity findEntityById(UUID id) {
        return effectorRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(PrimitiveType.EFFECTOR, id));
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
