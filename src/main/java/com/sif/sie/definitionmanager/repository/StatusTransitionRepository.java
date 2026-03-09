package com.sif.sie.definitionmanager.repository;

import com.sif.sie.definitionmanager.entity.AscriptionStatusTransitionEntity;
import com.sif.sie.definitionmanager.enums.GsmType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StatusTransitionRepository
        extends JpaRepository<AscriptionStatusTransitionEntity, UUID> {
    List<AscriptionStatusTransitionEntity> findAllByRevisionIdOrderByTimestampAsc(UUID revisionId);

    @Query(
            "SELECT t.gsmType FROM AscriptionStatusTransitionEntity t WHERE t.revisionId = :revisionId ORDER BY t.timestamp ASC LIMIT 1")
    Optional<GsmType> findGsmTypeByRevisionId(UUID revisionId);
}
