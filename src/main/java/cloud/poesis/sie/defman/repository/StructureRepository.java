package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

/**
 * Spring Data JPA repository for {@link StructureEntity} (the {@code structure}
 * table).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public interface StructureRepository extends AbstractAscriptionRepository<StructureEntity> {

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
}
