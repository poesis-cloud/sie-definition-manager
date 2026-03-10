package com.sif.sie.definitionmanager.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.sif.sie.definitionmanager.entity.ReceptorEntity;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;

public interface ReceptorRepository extends JpaRepository<ReceptorEntity, UUID> {
    Page<ReceptorEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    List<ReceptorEntity> findAllByDefinition_IdOrderByTimestampDesc(UUID definitionId);

    List<ReceptorEntity> findAllByDefinition_IdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    Page<ReceptorEntity> findAllByMechanism_Id(UUID mechanismId, Pageable pageable);
}
