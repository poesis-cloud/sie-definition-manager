package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.DirectiveEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

/**
 * Spring Data JPA repository for {@link DirectiveEntity} (the {@code directive}
 * table).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public interface DirectiveRepository extends JpaRepository<DirectiveEntity, UUID> {

    /**
     * Returns a page of directives filtered by lifecycle status.
     *
     * @param status   the lifecycle status to match
     * @param pageable pagination parameters
     * @return a page of matching directive entities
     */
    Page<DirectiveEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    /**
     * Returns all directives for a given definition ordered by recency.
     *
     * @param definitionId the definition UUID
     * @return the matching directive entities ordered by timestamp descending
     */
    List<DirectiveEntity> findAllByDefinitionIdOrderByTimestampDesc(UUID definitionId);

    /**
     * Returns all directives for a given definition filtered by lifecycle
     * statuses.
     *
     * @param definitionId the definition UUID
     * @param statuses     the lifecycle statuses to match
     * @return the matching directive entities
     */
    List<DirectiveEntity> findAllByDefinitionIdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    /**
     * Returns a page of directives authored by a specific structure.
     *
     * @param structureId the structure UUID
     * @param pageable    pagination parameters
     * @return a page of matching directive entities
     */
    Page<DirectiveEntity> findAllByStructureId(UUID structureId, Pageable pageable);

    /**
     * Returns all directives authored by a specific structure.
     *
     * @param structureId the structure UUID
     * @return the matching directive entities
     */
    List<DirectiveEntity> findAllByStructureId(UUID structureId);

    /**
     * Returns directives targeting the given qualifier and purpose pair.
     *
     * @param qualifierDefinitionId the qualifier archetype definition UUID
     * @param purposeDefinitionId   the purpose structure definition UUID
     * @param statuses              the lifecycle statuses to match
     * @return the matching directive entities
     */
    List<DirectiveEntity> findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
            UUID qualifierDefinitionId, UUID purposeDefinitionId,
            Collection<AscriptionStatusType> statuses);
}
