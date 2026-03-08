package org.sif.sie.dm.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.sif.sie.dm.model.entity.DirectiveEntity;
import org.sif.sie.dm.model.enums.AscriptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectiveRepository extends JpaRepository<DirectiveEntity, UUID> {
    Page<DirectiveEntity> findAllByStatus(AscriptionStatus status, Pageable pageable);

    List<DirectiveEntity> findAllByIdOrderByRevisionTimestampDesc(UUID id);

    List<DirectiveEntity> findAllByIdAndStatusIn(UUID id, Collection<AscriptionStatus> statuses);

    Page<DirectiveEntity> findAllByStructure_RevisionId(UUID structureRevisionId, Pageable pageable);
}
