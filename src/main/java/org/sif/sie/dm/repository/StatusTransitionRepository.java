package org.sif.sie.dm.repository;

import org.sif.sie.dm.model.entity.AscriptionStatusTransitionEntity;
import org.sif.sie.dm.model.enums.GsmType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatusTransitionRepository extends JpaRepository<AscriptionStatusTransitionEntity, UUID> {
    List<AscriptionStatusTransitionEntity> findAllByRevisionIdOrderByTimestampAsc(UUID revisionId);

    @Query("SELECT t.gsmType FROM AscriptionStatusTransitionEntity t WHERE t.revisionId = :revisionId ORDER BY t.timestamp ASC LIMIT 1")
    Optional<GsmType> findGsmTypeByRevisionId(UUID revisionId);
}
