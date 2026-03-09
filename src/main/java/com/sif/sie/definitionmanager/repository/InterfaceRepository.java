package com.sif.sie.definitionmanager.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.sif.sie.definitionmanager.entity.InterfaceEntity;
import com.sif.sie.definitionmanager.enums.AscriptionStatus;

public interface InterfaceRepository extends JpaRepository<InterfaceEntity, UUID> {
    Page<InterfaceEntity> findAllByStatus(AscriptionStatus status, Pageable pageable);

    Page<InterfaceEntity> findAllByStatusIn(Collection<AscriptionStatus> statuses, Pageable pageable);

    List<InterfaceEntity> findAllByDefinition_IdOrderByTimestampDesc(UUID definitionId);

    List<InterfaceEntity> findAllByDefinition_IdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatus> statuses);

    Page<InterfaceEntity> findAllByStructure_Id(UUID structureId, Pageable pageable);
}
