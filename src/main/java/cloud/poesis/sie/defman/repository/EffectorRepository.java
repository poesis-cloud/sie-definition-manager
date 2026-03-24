package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import cloud.poesis.sie.defman.entity.EffectorEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

/**
 * Spring Data JPA repository for {@link EffectorEntity} (the {@code effector}
 * table).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public interface EffectorRepository extends AbstractAscriptionRepository<EffectorEntity> {

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
