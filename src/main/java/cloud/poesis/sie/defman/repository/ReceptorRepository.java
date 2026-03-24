package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import cloud.poesis.sie.defman.entity.ReceptorEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

/**
 * Spring Data JPA repository for {@link ReceptorEntity} (the {@code receptor}
 * table).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public interface ReceptorRepository extends AbstractAscriptionRepository<ReceptorEntity> {

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
