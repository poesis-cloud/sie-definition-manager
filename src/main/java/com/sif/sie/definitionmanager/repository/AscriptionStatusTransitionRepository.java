package com.sif.sie.definitionmanager.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sif.sie.definitionmanager.entity.AscriptionStatusTransitionEntity;

public interface AscriptionStatusTransitionRepository
                extends JpaRepository<AscriptionStatusTransitionEntity, UUID> {
        List<AscriptionStatusTransitionEntity> findAllByAscription_IdOrderByTimestampAsc(
                        UUID ascriptionId);
}
