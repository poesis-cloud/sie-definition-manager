package com.sif.sie.definitionmanager.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.sif.sie.definitionmanager.entity.MechanismEntity;
import com.sif.sie.definitionmanager.enums.AscriptionStatus;

public interface MechanismRepository extends JpaRepository<MechanismEntity, UUID> {
    Page<MechanismEntity> findAllByStatus(AscriptionStatus status, Pageable pageable);

    Page<MechanismEntity> findAllByStatusIn(Collection<AscriptionStatus> statuses, Pageable pageable);

    List<MechanismEntity> findAllByDefinition_IdOrderByTimestampDesc(UUID definitionId);

    List<MechanismEntity> findAllByDefinition_IdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatus> statuses);

    Page<MechanismEntity> findAllByStructure_Id(UUID structureId, Pageable pageable);
}
