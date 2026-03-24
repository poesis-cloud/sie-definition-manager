package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import cloud.poesis.sie.defman.entity.NormEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

/**
 * Spring Data JPA repository for {@link NormEntity} (the {@code norm} table).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public interface NormRepository extends AbstractAscriptionRepository<NormEntity> {

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
