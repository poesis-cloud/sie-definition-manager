package com.sif.sie.definitionmanager.repository;

import com.sif.sie.definitionmanager.entity.ReceptorEntity;
import com.sif.sie.definitionmanager.enums.AscriptionStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceptorRepository extends JpaRepository<ReceptorEntity, UUID> {
    Page<ReceptorEntity> findAllByStatus(AscriptionStatus status, Pageable pageable);

    List<ReceptorEntity> findAllByIdOrderByRevisionTimestampDesc(UUID id);

    List<ReceptorEntity> findAllByIdAndStatusIn(UUID id, Collection<AscriptionStatus> statuses);

    Page<ReceptorEntity> findAllByMechanism_RevisionId(UUID mechanismRevisionId, Pageable pageable);
}
