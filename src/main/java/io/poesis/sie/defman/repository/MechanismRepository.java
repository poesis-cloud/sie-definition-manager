package io.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import io.poesis.sie.defman.entity.MechanismEntity;
import io.poesis.sie.defman.type.AscriptionStatusType;

public interface MechanismRepository extends JpaRepository<MechanismEntity, UUID> {
    Page<MechanismEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    Page<MechanismEntity> findAllByStatusIn(Collection<AscriptionStatusType> statuses, Pageable pageable);

    List<MechanismEntity> findAllByDefinitionIdOrderByTimestampDesc(UUID definitionId);

    List<MechanismEntity> findAllByDefinitionIdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    Page<MechanismEntity> findAllByStructureId(UUID structureId, Pageable pageable);

    List<MechanismEntity> findAllByStructureId(UUID structureId);

    List<MechanismEntity> findAllByStructureDefinitionIdAndStatusIn(
            UUID structureDefinitionId, Collection<AscriptionStatusType> statuses);
}
