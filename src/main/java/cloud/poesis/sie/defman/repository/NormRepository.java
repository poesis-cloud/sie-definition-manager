package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.NormEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

public interface NormRepository extends JpaRepository<NormEntity, UUID> {
    @EntityGraph("ascription-with-refs")
    Page<NormEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    @EntityGraph("ascription-with-refs")
    List<NormEntity> findAllByDefinitionIdOrderByTimestampDesc(UUID definitionId);

    List<NormEntity> findAllByDefinitionIdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    @EntityGraph("ascription-with-refs")
    Page<NormEntity> findAllByStructureId(UUID structureId, Pageable pageable);

    List<NormEntity> findAllByStructureId(UUID structureId);
}
