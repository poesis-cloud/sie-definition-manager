package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

/**
 * Spring Data JPA repository for {@link ArchetypeEntity} (the {@code archetype}
 * table).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public interface ArchetypeRepository extends AbstractAscriptionRepository<ArchetypeEntity> {

    /**
     * Returns all archetypes filtered by lifecycle status.
     *
     * @param status the lifecycle status to match
     * @return the matching archetype entities
     */
    List<ArchetypeEntity> findAllByStatus(AscriptionStatusType status);

    /**
     * Returns a page of archetypes filtered by a set of lifecycle statuses.
     *
     * @param statuses the lifecycle statuses to match
     * @param pageable pagination parameters
     * @return a page of matching archetype entities
     */
    Page<ArchetypeEntity> findAllByStatusIn(Collection<AscriptionStatusType> statuses, Pageable pageable);

    /**
     * Returns all archetypes filtered by a set of lifecycle statuses.
     *
     * @param statuses the lifecycle statuses to match
     * @return the matching archetype entities
     */
    List<ArchetypeEntity> findAllByStatusIn(Collection<AscriptionStatusType> statuses);

    /**
     * Returns the in-effect archetype with the given schema title.
     *
     * @param title the archetype schema title
     * @return the matching archetype, if any
     */
    @Query(value = "SELECT * FROM archetype WHERE statement->>'title' = ?1"
            + " AND status IN ('ACTIVE', 'DEPRECATED') LIMIT 1", nativeQuery = true)
    Optional<ArchetypeEntity> findInEffectByTitle(String title);
}
