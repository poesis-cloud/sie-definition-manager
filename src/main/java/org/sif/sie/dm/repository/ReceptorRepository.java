package org.sif.sie.dm.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.sif.sie.dm.model.entity.ReceptorEntity;
import org.sif.sie.dm.model.enums.AscriptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceptorRepository extends JpaRepository<ReceptorEntity, UUID> {
    Page<ReceptorEntity> findAllByStatus(AscriptionStatus status, Pageable pageable);

    List<ReceptorEntity> findAllByIdOrderByRevisionTimestampDesc(UUID id);

    List<ReceptorEntity> findAllByIdAndStatusIn(UUID id, Collection<AscriptionStatus> statuses);

    Page<ReceptorEntity> findAllByMechanism_RevisionId(UUID mechanismRevisionId, Pageable pageable);
}
