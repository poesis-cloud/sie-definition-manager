package com.sif.sie.definitionmanager.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;

public interface ArchetypeRepository extends JpaRepository<ArchetypeEntity, UUID> {
    Page<ArchetypeEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    List<ArchetypeEntity> findAllByStatus(AscriptionStatusType status);

    Page<ArchetypeEntity> findAllByStatusIn(Collection<AscriptionStatusType> statuses, Pageable pageable);

    List<ArchetypeEntity> findAllByDefinition_IdOrderByTimestampDesc(UUID definitionId);

    List<ArchetypeEntity> findAllByDefinition_IdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);
}
