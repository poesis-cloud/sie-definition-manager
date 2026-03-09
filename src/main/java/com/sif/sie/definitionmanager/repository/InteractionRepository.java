package com.sif.sie.definitionmanager.repository;

import com.sif.sie.definitionmanager.entity.InteractionEntity;
import com.sif.sie.definitionmanager.enums.AscriptionStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InteractionRepository extends JpaRepository<InteractionEntity, UUID> {
    Page<InteractionEntity> findAllByStatus(AscriptionStatus status, Pageable pageable);

    List<InteractionEntity> findAllByIdOrderByRevisionTimestampDesc(UUID id);

    List<InteractionEntity> findAllByIdAndStatusIn(UUID id, Collection<AscriptionStatus> statuses);
}
