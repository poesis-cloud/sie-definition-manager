package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

/**
 * Spring Data JPA repository for {@link MechanismEntity} (the {@code mechanism}
 * table).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public interface MechanismRepository extends JpaRepository<MechanismEntity, UUID> {

    /**
     * Returns a page of mechanisms filtered by lifecycle status.
     *
     * @param status   the lifecycle status to match
     * @param pageable pagination parameters
     * @return a page of matching mechanism entities
     */
    Page<MechanismEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    /**
     * Returns a page of mechanisms filtered by a set of lifecycle statuses.
     *
     * @param statuses the lifecycle statuses to match
     * @param pageable pagination parameters
     * @return a page of matching mechanism entities
     */
    Page<MechanismEntity> findAllByStatusIn(Collection<AscriptionStatusType> statuses, Pageable pageable);

    /**
     * Returns all mechanisms for a given definition ordered by recency.
     *
     * @param definitionId the definition UUID
     * @return the matching mechanism entities ordered by timestamp descending
     */
    List<MechanismEntity> findAllByDefinitionIdOrderByTimestampDesc(UUID definitionId);

    /**
     * Returns all mechanisms for a given definition filtered by lifecycle
     * statuses.
     *
     * @param definitionId the definition UUID
     * @param statuses     the lifecycle statuses to match
     * @return the matching mechanism entities
     */
    List<MechanismEntity> findAllByDefinitionIdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    /**
     * Returns a page of mechanisms belonging to a specific structure.
     *
     * @param structureId the structure UUID
     * @param pageable    pagination parameters
     * @return a page of matching mechanism entities
     */
    Page<MechanismEntity> findAllByStructureId(UUID structureId, Pageable pageable);

    /**
     * Returns all mechanisms belonging to a specific structure.
     *
     * @param structureId the structure UUID
     * @return the matching mechanism entities
     */
    List<MechanismEntity> findAllByStructureId(UUID structureId);

    /**
     * Returns mechanisms belonging to a structure definition filtered by
     * lifecycle statuses.
     *
     * @param structureDefinitionId the structure definition UUID
     * @param statuses              the lifecycle statuses to match
     * @return the matching mechanism entities
     */
    List<MechanismEntity> findAllByStructureDefinitionIdAndStatusIn(
            UUID structureDefinitionId, Collection<AscriptionStatusType> statuses);
}
