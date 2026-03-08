package org.sif.sie.dm.repository;

import org.sif.sie.dm.model.entity.StructureEntity;
import org.sif.sie.dm.model.enums.AscriptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StructureRepository extends JpaRepository<StructureEntity, UUID> {
    Page<StructureEntity> findAllByStatus(AscriptionStatus status, Pageable pageable);
    Page<StructureEntity> findAllByStatusIn(Collection<AscriptionStatus> statuses, Pageable pageable);
    List<StructureEntity> findAllByIdOrderByRevisionTimestampDesc(UUID id);
    List<StructureEntity> findAllByIdAndStatusIn(UUID id, Collection<AscriptionStatus> statuses);
}
