package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.InterfaceEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

public interface InterfaceRepository extends JpaRepository<InterfaceEntity, UUID> {
    @EntityGraph("ascription-with-refs")
    Page<InterfaceEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    @EntityGraph("ascription-with-refs")
    Page<InterfaceEntity> findAllByStatusIn(Collection<AscriptionStatusType> statuses, Pageable pageable);

    @EntityGraph("ascription-with-refs")
    List<InterfaceEntity> findAllByDefinitionIdOrderByTimestampDesc(UUID definitionId);

    List<InterfaceEntity> findAllByDefinitionIdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    @EntityGraph("ascription-with-refs")
    Page<InterfaceEntity> findAllByStructureId(UUID structureId, Pageable pageable);

    List<InterfaceEntity> findAllByStructureId(UUID structureId);

    List<InterfaceEntity> findAllByEffectorsId(UUID effectorId);

    List<InterfaceEntity> findAllByReceptorsId(UUID receptorId);
}
