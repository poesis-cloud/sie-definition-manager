package com.sif.sie.definitionmanager.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.sif.sie.definitionmanager.entity.InteractionEntity;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;

public interface InteractionRepository extends JpaRepository<InteractionEntity, UUID> {
    Page<InteractionEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    List<InteractionEntity> findAllByDefinition_IdOrderByTimestampDesc(UUID definitionId);

    List<InteractionEntity> findAllByDefinition_IdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);
}
