package io.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import io.poesis.sie.defman.entity.InteractionEntity;
import io.poesis.sie.defman.type.AscriptionStatusType;

public interface InteractionRepository extends JpaRepository<InteractionEntity, UUID> {
    Page<InteractionEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    List<InteractionEntity> findAllByDefinition_IdOrderByTimestampDesc(UUID definitionId);

    List<InteractionEntity> findAllByDefinition_IdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    List<InteractionEntity> findAllByEffector_Id(UUID effectorId);

    List<InteractionEntity> findAllByReceptor_Id(UUID receptorId);
}
