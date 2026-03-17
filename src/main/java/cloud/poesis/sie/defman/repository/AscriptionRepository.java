package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

public interface AscriptionRepository extends JpaRepository<AscriptionEntity, UUID> {

    List<AscriptionEntity> findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
            UUID archetypeId, Collection<AscriptionStatusType> statuses, UUID excludeDefinitionId);
}
