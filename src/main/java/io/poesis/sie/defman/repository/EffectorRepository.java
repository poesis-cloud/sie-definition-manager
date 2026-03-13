package io.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import io.poesis.sie.defman.entity.EffectorEntity;
import io.poesis.sie.defman.type.AscriptionStatusType;

public interface EffectorRepository extends JpaRepository<EffectorEntity, UUID> {
    Page<EffectorEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    List<EffectorEntity> findAllByDefinition_IdOrderByTimestampDesc(UUID definitionId);

    List<EffectorEntity> findAllByDefinition_IdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    Page<EffectorEntity> findAllByMechanism_Id(UUID mechanismId, Pageable pageable);

    List<EffectorEntity> findAllByMechanism_Id(UUID mechanismId);

    List<EffectorEntity> findAllByMechanism_Definition_Id(UUID mechanismDefinitionId);

    List<EffectorEntity> findAllByMechanism_Definition_IdAndStatusIn(
            UUID mechanismDefinitionId, Collection<AscriptionStatusType> statuses);
}
