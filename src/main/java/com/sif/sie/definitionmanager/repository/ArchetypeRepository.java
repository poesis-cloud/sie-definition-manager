package com.sif.sie.definitionmanager.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
import com.sif.sie.definitionmanager.enums.AscriptionStatus;

public interface ArchetypeRepository extends JpaRepository<ArchetypeEntity, UUID> {
    Page<ArchetypeEntity> findAllByStatus(AscriptionStatus status, Pageable pageable);

    List<ArchetypeEntity> findAllByStatus(AscriptionStatus status);

    Page<ArchetypeEntity> findAllByStatusIn(Collection<AscriptionStatus> statuses, Pageable pageable);

    List<ArchetypeEntity> findAllByDefinition_IdOrderByTimestampDesc(UUID definitionId);

    Optional<ArchetypeEntity> findBySchemaUri(String schemaUri);

    List<ArchetypeEntity> findAllByDefinition_IdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatus> statuses);
}
