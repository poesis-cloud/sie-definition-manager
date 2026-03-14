package io.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.poesis.sie.defman.entity.AscriptionEntity;
import io.poesis.sie.defman.type.AscriptionStatusType;

public interface AscriptionRepository extends JpaRepository<AscriptionEntity, UUID> {

    List<AscriptionEntity> findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
            UUID archetypeId, Collection<AscriptionStatusType> statuses, UUID excludeDefinitionId);
}
