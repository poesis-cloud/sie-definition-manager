package cloud.poesis.sie.defman.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
@Transactional("transactionManager")
public class AscriptionStatusTransitionService {

    private final AscriptionStatusTransitionRepository transitionRepo;
    private final EntityManager entityManager;

    /**
     * Constructs the service with its required dependencies.
     *
     * @param transitionRepo the status transition repository
     * @param entityManager  the JPA entity manager
     */
    public AscriptionStatusTransitionService(
            AscriptionStatusTransitionRepository transitionRepo,
            EntityManager entityManager) {
        this.transitionRepo = transitionRepo;
        this.entityManager = entityManager;
    }

    /**
     * Persists a transition record, flushes, and returns the refreshed entity.
     *
     * @param entity the ascription being transitioned
     * @param from   the pre-transition status
     * @param to     the post-transition status
     * @return the persisted and refreshed transition entity
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

    /**
     * Returns all status transitions for an ascription, ordered by timestamp
     * ascending.
     *
     * @param ascriptionId the ascription UUID
     * @return ordered list of status transition entities
     */
    @Transactional(value = "transactionManager", readOnly = true)
    public List<AscriptionStatusTransitionEntity> findByAscriptionId(UUID ascriptionId) {
        return transitionRepo.findAllByAscriptionIdOrderByTimestampAsc(ascriptionId);
    }

    /**
     * Returns a single transition by its ID, scoped to the given ascription.
     *
     * @param transitionId the transition UUID
     * @param ascriptionId the owning ascription UUID
     * @return the matching transition entity
     */
    @Transactional(value = "transactionManager", readOnly = true)
    public Optional<AscriptionStatusTransitionEntity> findByIdAndAscriptionId(
            UUID transitionId, UUID ascriptionId) {
        return transitionRepo.findByIdAndAscriptionId(transitionId, ascriptionId);
    }
}
