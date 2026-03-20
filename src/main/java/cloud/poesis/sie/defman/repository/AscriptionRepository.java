package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

/**
 * Spring Data JPA repository for {@link AscriptionEntity} (cross-table base
 * queries).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public interface AscriptionRepository extends JpaRepository<AscriptionEntity, UUID> {

    /**
     * Returns all ascriptions for a given archetype and excludes one definition.
     *
     * @param archetypeId         the archetype definition UUID
     * @param statuses            the lifecycle statuses to match
     * @param excludeDefinitionId the definition UUID to exclude
     * @return the matching ascription entities
     */
    List<AscriptionEntity> findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
            UUID archetypeId, Collection<AscriptionStatusType> statuses, UUID excludeDefinitionId);
}
