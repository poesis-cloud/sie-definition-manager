package com.sif.sie.definitionmanager.repository;

import com.sif.sie.definitionmanager.entity.NormEntity;
import com.sif.sie.definitionmanager.enums.AscriptionStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NormRepository extends JpaRepository<NormEntity, UUID> {
    Page<NormEntity> findAllByStatus(AscriptionStatus status, Pageable pageable);

    List<NormEntity> findAllByIdOrderByRevisionTimestampDesc(UUID id);

    List<NormEntity> findAllByIdAndStatusIn(UUID id, Collection<AscriptionStatus> statuses);

    Page<NormEntity> findAllByStructure_RevisionId(UUID structureRevisionId, Pageable pageable);
}
