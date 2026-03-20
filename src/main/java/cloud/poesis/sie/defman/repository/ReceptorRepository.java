package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.ReceptorEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

/**
 * Spring Data JPA repository for {@link ReceptorEntity} (the {@code receptor}
 * table).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public interface ReceptorRepository extends JpaRepository<ReceptorEntity, UUID> {

    /**
     * Returns a page of receptors filtered by lifecycle status.
     *
     * @param status   the lifecycle status to match
     * @param pageable pagination parameters
     * @return a page of matching receptor entities
     */
    Page<ReceptorEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    /**
     * Returns all receptors for a given definition ordered by recency.
     *
     * @param definitionId the definition UUID
     * @return the matching receptor entities ordered by timestamp descending
     */
    List<ReceptorEntity> findAllByDefinitionIdOrderByTimestampDesc(UUID definitionId);

    /**
     * Returns all receptors for a given definition filtered by lifecycle
     * statuses.
     *
     * @param definitionId the definition UUID
     * @param statuses     the lifecycle statuses to match
     * @return the matching receptor entities
     */
    List<ReceptorEntity> findAllByDefinitionIdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    /**
     * Returns a page of receptors belonging to a specific mechanism.
     *
     * @param mechanismId the mechanism UUID
     * @param pageable    pagination parameters
     * @return a page of matching receptor entities
     */
    Page<ReceptorEntity> findAllByMechanismId(UUID mechanismId, Pageable pageable);

    /**
     * Returns all receptors belonging to a specific mechanism.
     *
     * @param mechanismId the mechanism UUID
     * @return the matching receptor entities
     */
    List<ReceptorEntity> findAllByMechanismId(UUID mechanismId);

    /**
     * Returns all receptors belonging to a mechanism definition.
     *
     * @param mechanismDefinitionId the mechanism definition UUID
     * @return the matching receptor entities
     */
    List<ReceptorEntity> findAllByMechanismDefinitionId(UUID mechanismDefinitionId);

    /**
     * Returns receptors belonging to a mechanism definition filtered by
     * lifecycle statuses.
     *
     * @param mechanismDefinitionId the mechanism definition UUID
     * @param statuses              the lifecycle statuses to match
     * @return the matching receptor entities
     */
    List<ReceptorEntity> findAllByMechanismDefinitionIdAndStatusIn(
            UUID mechanismDefinitionId, Collection<AscriptionStatusType> statuses);
}
