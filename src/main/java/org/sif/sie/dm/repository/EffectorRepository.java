package org.sif.sie.dm.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.sif.sie.dm.model.entity.EffectorEntity;
import org.sif.sie.dm.model.enums.AscriptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EffectorRepository extends JpaRepository<EffectorEntity, UUID> {
    Page<EffectorEntity> findAllByStatus(AscriptionStatus status, Pageable pageable);

    List<EffectorEntity> findAllByIdOrderByRevisionTimestampDesc(UUID id);

    List<EffectorEntity> findAllByIdAndStatusIn(UUID id, Collection<AscriptionStatus> statuses);

    Page<EffectorEntity> findAllByMechanism_RevisionId(UUID mechanismRevisionId, Pageable pageable);
}
