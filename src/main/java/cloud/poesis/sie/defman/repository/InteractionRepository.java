package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.InteractionEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

/**
 * Spring Data JPA repository for {@link InteractionEntity} (the
 * {@code interaction} table).
 *
 * @author Clément Cazaud
 * @since 0.1.0
 */
public interface InteractionRepository extends JpaRepository<InteractionEntity, UUID> {

    /**
     * Returns a page of interactions filtered by lifecycle status.
     *
     * @param status   the lifecycle status to match
     * @param pageable pagination parameters
     * @return a page of matching interaction entities
     */
    Page<InteractionEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    /**
     * Returns all interactions for a given definition ordered by recency.
     *
     * @param definitionId the definition UUID
     * @return the matching interaction entities ordered by timestamp descending
     */
    List<InteractionEntity> findAllByDefinitionIdOrderByTimestampDesc(UUID definitionId);

    /**
     * Returns all interactions for a given definition filtered by lifecycle
     * statuses.
     *
     * @param definitionId the definition UUID
     * @param statuses     the lifecycle statuses to match
     * @return the matching interaction entities
     */
    List<InteractionEntity> findAllByDefinitionIdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    /**
     * Returns all interactions that originate from a given effector.
     *
     * @param effectorId the effector UUID
     * @return the matching interaction entities
     */
    List<InteractionEntity> findAllByEffectorId(UUID effectorId);

    /**
     * Returns all interactions that terminate at a given receptor.
     *
     * @param receptorId the receptor UUID
     * @return the matching interaction entities
     */
    List<InteractionEntity> findAllByReceptorId(UUID receptorId);
}
