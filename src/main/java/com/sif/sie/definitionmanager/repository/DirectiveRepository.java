package com.sif.sie.definitionmanager.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.sif.sie.definitionmanager.entity.DirectiveEntity;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;

public interface DirectiveRepository extends JpaRepository<DirectiveEntity, UUID> {
    Page<DirectiveEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    List<DirectiveEntity> findAllByDefinition_IdOrderByTimestampDesc(UUID definitionId);

    List<DirectiveEntity> findAllByDefinition_IdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    Page<DirectiveEntity> findAllByStructure_Id(UUID structureId, Pageable pageable);
}
