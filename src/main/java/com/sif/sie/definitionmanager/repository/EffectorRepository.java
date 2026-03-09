package com.sif.sie.definitionmanager.repository;

import com.sif.sie.definitionmanager.entity.EffectorEntity;
import com.sif.sie.definitionmanager.enums.AscriptionStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EffectorRepository extends JpaRepository<EffectorEntity, UUID> {
    Page<EffectorEntity> findAllByStatus(AscriptionStatus status, Pageable pageable);

    List<EffectorEntity> findAllByIdOrderByRevisionTimestampDesc(UUID id);

    List<EffectorEntity> findAllByIdAndStatusIn(UUID id, Collection<AscriptionStatus> statuses);

    Page<EffectorEntity> findAllByMechanism_RevisionId(UUID mechanismRevisionId, Pageable pageable);
}
