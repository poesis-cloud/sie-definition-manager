package com.sif.sie.definitionmanager.repository;

import com.sif.sie.definitionmanager.entity.MechanismEntity;
import com.sif.sie.definitionmanager.enums.AscriptionStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MechanismRepository extends JpaRepository<MechanismEntity, UUID> {
    Page<MechanismEntity> findAllByStatus(AscriptionStatus status, Pageable pageable);

    Page<MechanismEntity> findAllByStatusIn(Collection<AscriptionStatus> statuses, Pageable pageable);

    List<MechanismEntity> findAllByIdOrderByRevisionTimestampDesc(UUID id);

    List<MechanismEntity> findAllByIdAndStatusIn(UUID id, Collection<AscriptionStatus> statuses);

    Page<MechanismEntity> findAllByStructure_RevisionId(UUID structureRevisionId, Pageable pageable);
}
