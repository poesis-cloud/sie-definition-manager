package com.sif.sie.definitionmanager.repository;

import com.sif.sie.definitionmanager.entity.InterfaceEntity;
import com.sif.sie.definitionmanager.enums.AscriptionStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterfaceRepository extends JpaRepository<InterfaceEntity, UUID> {
    Page<InterfaceEntity> findAllByStatus(AscriptionStatus status, Pageable pageable);

    Page<InterfaceEntity> findAllByStatusIn(Collection<AscriptionStatus> statuses, Pageable pageable);

    List<InterfaceEntity> findAllByIdOrderByRevisionTimestampDesc(UUID id);

    List<InterfaceEntity> findAllByIdAndStatusIn(UUID id, Collection<AscriptionStatus> statuses);

    Page<InterfaceEntity> findAllByStructure_RevisionId(UUID structureRevisionId, Pageable pageable);
}
