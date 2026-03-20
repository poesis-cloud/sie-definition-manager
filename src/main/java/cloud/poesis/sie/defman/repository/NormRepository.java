package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.NormEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

/**
 * Spring Data JPA repository for {@link NormEntity} (the {@code norm} table).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public interface NormRepository extends JpaRepository<NormEntity, UUID> {

    /**
     * Returns a page of norms filtered by lifecycle status.
     *
     * @param status   the lifecycle status to match
     * @param pageable pagination parameters
     * @return a page of matching norm entities
     */
    Page<NormEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    /**
     * Returns all norms for a given definition ordered by recency.
     *
     * @param definitionId the definition UUID
     * @return the matching norm entities ordered by timestamp descending
     */
    List<NormEntity> findAllByDefinitionIdOrderByTimestampDesc(UUID definitionId);

    /**
     * Returns all norms for a given definition filtered by lifecycle statuses.
     *
     * @param definitionId the definition UUID
     * @param statuses     the lifecycle statuses to match
     * @return the matching norm entities
     */
    List<NormEntity> findAllByDefinitionIdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    /**
     * Returns a page of norms authored by a specific structure.
     *
     * @param structureId the structure UUID
     * @param pageable    pagination parameters
     * @return a page of matching norm entities
     */
    Page<NormEntity> findAllByStructureId(UUID structureId, Pageable pageable);

    /**
     * Returns all norms authored by a specific structure.
     *
     * @param structureId the structure UUID
     * @return the matching norm entities
     */
    List<NormEntity> findAllByStructureId(UUID structureId);
}
