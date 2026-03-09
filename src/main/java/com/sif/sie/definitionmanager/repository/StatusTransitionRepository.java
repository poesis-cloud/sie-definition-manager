package com.sif.sie.definitionmanager.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.sif.sie.definitionmanager.entity.AscriptionStatusTransitionEntity;
import com.sif.sie.definitionmanager.enums.DefinitionSubjectType;

public interface StatusTransitionRepository
                extends JpaRepository<AscriptionStatusTransitionEntity, UUID> {
        List<AscriptionStatusTransitionEntity> findAllByAscriptionIdOrderByTimestampAsc(
                        UUID ascriptionId);

        @Query("SELECT t.subjectType FROM AscriptionStatusTransitionEntity t WHERE t.ascriptionId = :ascriptionId ORDER BY t.timestamp ASC LIMIT 1")
        Optional<DefinitionSubjectType> findSubjectTypeByAscriptionId(UUID ascriptionId);
}
