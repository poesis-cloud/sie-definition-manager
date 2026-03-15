package cloud.poesis.sie.defman.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import cloud.poesis.sie.defman.repository.AscriptionStatusTransitionRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import jakarta.persistence.EntityManager;

/**
 * Service for ascription status transitions. Owns
 * {@link AscriptionStatusTransitionRepository}.
 */
@Service
@Transactional("transactionManager")
public class AscriptionStatusTransitionService {

    private final AscriptionStatusTransitionRepository transitionRepo;
    private final EntityManager entityManager;

    public AscriptionStatusTransitionService(
            AscriptionStatusTransitionRepository transitionRepo,
            EntityManager entityManager) {
        this.transitionRepo = transitionRepo;
        this.entityManager = entityManager;
    }

    /**
     * Persists a transition record, flushes, and returns the refreshed entity.
     */
    public AscriptionStatusTransitionEntity recordTransition(
            AscriptionEntity entity, AscriptionStatusType from, AscriptionStatusType to) {
        AscriptionStatusTransitionEntity transition = transitionRepo.save(
                new AscriptionStatusTransitionEntity(entity, from, to));
        entityManager.flush();
        entityManager.detach(transition);
        UUID transitionId = Objects.requireNonNull(transition.getId(), "transition.id");
        return transitionRepo.findById(transitionId).orElseThrow();
    }

    @Transactional(value = "transactionManager", readOnly = true)
    public List<AscriptionStatusTransitionEntity> findByAscriptionId(UUID ascriptionId) {
        return transitionRepo.findAllByAscriptionIdOrderByTimestampAsc(ascriptionId);
    }
}
