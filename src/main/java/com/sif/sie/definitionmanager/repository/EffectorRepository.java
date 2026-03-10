package com.sif.sie.definitionmanager.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.sif.sie.definitionmanager.entity.EffectorEntity;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;

public interface EffectorRepository extends JpaRepository<EffectorEntity, UUID> {
    Page<EffectorEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    List<EffectorEntity> findAllByDefinition_IdOrderByTimestampDesc(UUID definitionId);

    List<EffectorEntity> findAllByDefinition_IdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    Page<EffectorEntity> findAllByMechanism_Id(UUID mechanismId, Pageable pageable);
}
