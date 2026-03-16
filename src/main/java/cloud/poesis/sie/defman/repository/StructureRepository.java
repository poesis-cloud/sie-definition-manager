package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

public interface StructureRepository extends JpaRepository<StructureEntity, UUID> {
    @EntityGraph("ascription-with-refs")
    Page<StructureEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    @EntityGraph("ascription-with-refs")
    Page<StructureEntity> findAllByStatusIn(Collection<AscriptionStatusType> statuses, Pageable pageable);

    List<StructureEntity> findAllByStatusIn(Collection<AscriptionStatusType> statuses);

    @EntityGraph("ascription-with-refs")
    List<StructureEntity> findAllByDefinitionIdOrderByTimestampDesc(UUID definitionId);

    List<StructureEntity> findAllByDefinitionIdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);
}
