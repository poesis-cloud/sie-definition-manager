package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

public interface ArchetypeRepository extends JpaRepository<ArchetypeEntity, UUID> {
    @EntityGraph("ascription-with-refs")
    Page<ArchetypeEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    List<ArchetypeEntity> findAllByStatus(AscriptionStatusType status);

    @EntityGraph("ascription-with-refs")
    Page<ArchetypeEntity> findAllByStatusIn(Collection<AscriptionStatusType> statuses, Pageable pageable);

    List<ArchetypeEntity> findAllByStatusIn(Collection<AscriptionStatusType> statuses);

    @EntityGraph("ascription-with-refs")
    List<ArchetypeEntity> findAllByDefinitionIdOrderByTimestampDesc(UUID definitionId);

    List<ArchetypeEntity> findAllByDefinitionIdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);
}
