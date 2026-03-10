package com.sif.sie.definitionmanager.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.sif.sie.definitionmanager.entity.StructureEntity;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;

public interface StructureRepository extends JpaRepository<StructureEntity, UUID> {
    Page<StructureEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    Page<StructureEntity> findAllByStatusIn(Collection<AscriptionStatusType> statuses, Pageable pageable);

    List<StructureEntity> findAllByDefinition_IdOrderByTimestampDesc(UUID definitionId);

    List<StructureEntity> findAllByDefinition_IdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);
}
