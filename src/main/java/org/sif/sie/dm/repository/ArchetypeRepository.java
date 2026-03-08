package org.sif.sie.dm.repository;

import org.sif.sie.dm.model.entity.ArchetypeEntity;
import org.sif.sie.dm.model.enums.AscriptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArchetypeRepository extends JpaRepository<ArchetypeEntity, UUID> {
    Page<ArchetypeEntity> findAllByStatus(AscriptionStatus status, Pageable pageable);
    Page<ArchetypeEntity> findAllByStatusIn(Collection<AscriptionStatus> statuses, Pageable pageable);
    List<ArchetypeEntity> findAllByIdOrderByRevisionTimestampDesc(UUID id);
    Optional<ArchetypeEntity> findBySchemaUri(String schemaUri);
    List<ArchetypeEntity> findAllByIdAndStatusIn(UUID id, Collection<AscriptionStatus> statuses);
}
