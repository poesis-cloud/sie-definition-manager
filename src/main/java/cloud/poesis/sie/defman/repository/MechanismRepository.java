package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

public interface MechanismRepository extends JpaRepository<MechanismEntity, UUID> {
    @EntityGraph("ascription-with-refs")
    Page<MechanismEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    @EntityGraph("ascription-with-refs")
    Page<MechanismEntity> findAllByStatusIn(Collection<AscriptionStatusType> statuses, Pageable pageable);

    @EntityGraph("ascription-with-refs")
    List<MechanismEntity> findAllByDefinitionIdOrderByTimestampDesc(UUID definitionId);

    List<MechanismEntity> findAllByDefinitionIdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    @EntityGraph("ascription-with-refs")
    Page<MechanismEntity> findAllByStructureId(UUID structureId, Pageable pageable);

    List<MechanismEntity> findAllByStructureId(UUID structureId);

    List<MechanismEntity> findAllByStructureDefinitionIdAndStatusIn(
            UUID structureDefinitionId, Collection<AscriptionStatusType> statuses);
}
