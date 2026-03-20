package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.EffectorEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

/**
 * Spring Data JPA repository for {@link EffectorEntity} (the {@code effector}
 * table).
 *
 * @author Clément Cazaud
 * @since 0.1.0
 */
public interface EffectorRepository extends JpaRepository<EffectorEntity, UUID> {

    /**
     * Returns a page of effectors filtered by lifecycle status.
     *
     * @param status   the lifecycle status to match
     * @param pageable pagination parameters
     * @return a page of matching effector entities
     */
    Page<EffectorEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    /**
     * Returns all effectors for a given definition ordered by recency.
     *
     * @param definitionId the definition UUID
     * @return the matching effector entities ordered by timestamp descending
     */
    List<EffectorEntity> findAllByDefinitionIdOrderByTimestampDesc(UUID definitionId);

    /**
     * Returns all effectors for a given definition filtered by lifecycle
     * statuses.
     *
     * @param definitionId the definition UUID
     * @param statuses     the lifecycle statuses to match
     * @return the matching effector entities
     */
    List<EffectorEntity> findAllByDefinitionIdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    /**
     * Returns a page of effectors belonging to a specific mechanism.
     *
     * @param mechanismId the mechanism UUID
     * @param pageable    pagination parameters
     * @return a page of matching effector entities
     */
    Page<EffectorEntity> findAllByMechanismId(UUID mechanismId, Pageable pageable);

    /**
     * Returns all effectors belonging to a specific mechanism.
     *
     * @param mechanismId the mechanism UUID
     * @return the matching effector entities
     */
    List<EffectorEntity> findAllByMechanismId(UUID mechanismId);

    /**
     * Returns all effectors belonging to a mechanism definition.
     *
     * @param mechanismDefinitionId the mechanism definition UUID
     * @return the matching effector entities
     */
    List<EffectorEntity> findAllByMechanismDefinitionId(UUID mechanismDefinitionId);

    /**
     * Returns effectors belonging to a mechanism definition filtered by
     * lifecycle statuses.
     *
     * @param mechanismDefinitionId the mechanism definition UUID
     * @param statuses              the lifecycle statuses to match
     * @return the matching effector entities
     */
    List<EffectorEntity> findAllByMechanismDefinitionIdAndStatusIn(
            UUID mechanismDefinitionId, Collection<AscriptionStatusType> statuses);
}
