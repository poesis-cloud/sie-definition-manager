package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

/**
 * Spring Data JPA repository for {@link StructureEntity} (the {@code structure}
 * table).
 *
 * @author Clément Cazaud
 * @since 0.1.0
 */
public interface StructureRepository extends JpaRepository<StructureEntity, UUID> {

    /**
     * Returns a page of structures filtered by lifecycle status.
     *
     * @param status  the lifecycle status to match
    * @param pageable pagination parameters
     * @return a page of matching structure entities
     */
    Page<StructureEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    /**
     * Returns a page of structures filtered by a set of lifecycle statuses.
     *
     * @param statuses the lifecycle statuses to match
     * @param pageable pagination parameters
     * @return a page of matching structure entities
     */
    Page<StructureEntity> findAllByStatusIn(Collection<AscriptionStatusType> statuses, Pageable pageable);

    /**
     * Returns all structures filtered by a set of lifecycle statuses.
     *
     * @param statuses the lifecycle statuses to match
     * @return the matching structure entities
     */
    List<StructureEntity> findAllByStatusIn(Collection<AscriptionStatusType> statuses);

    /**
     * Returns all structure ascriptions for a given definition, ordered by
     * timestamp descending.
     *
     * @param definitionId the definition UUID
     * @return the matching structure entities ordered by recency
     */
    List<StructureEntity> findAllByDefinitionIdOrderByTimestampDesc(UUID definitionId);

    /**
     * Returns all structure ascriptions for a given definition filtered by
     * lifecycle statuses.
     *
     * @param definitionId the definition UUID
     * @param statuses     the lifecycle statuses to match
     * @return the matching structure entities
     */
    List<StructureEntity> findAllByDefinitionIdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);
}
