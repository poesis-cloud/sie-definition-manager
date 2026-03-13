package io.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import io.poesis.sie.defman.entity.NormEntity;
import io.poesis.sie.defman.type.AscriptionStatusType;

public interface NormRepository extends JpaRepository<NormEntity, UUID> {
    Page<NormEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    List<NormEntity> findAllByDefinition_IdOrderByTimestampDesc(UUID definitionId);

    List<NormEntity> findAllByDefinition_IdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    Page<NormEntity> findAllByStructure_Id(UUID structureId, Pageable pageable);

    List<NormEntity> findAllByStructure_Id(UUID structureId);
}
