package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.ReceptorEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

public interface ReceptorRepository extends JpaRepository<ReceptorEntity, UUID> {
    Page<ReceptorEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    List<ReceptorEntity> findAllByDefinitionIdOrderByTimestampDesc(UUID definitionId);

    List<ReceptorEntity> findAllByDefinitionIdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    Page<ReceptorEntity> findAllByMechanismId(UUID mechanismId, Pageable pageable);

    List<ReceptorEntity> findAllByMechanismId(UUID mechanismId);

    List<ReceptorEntity> findAllByMechanismDefinitionId(UUID mechanismDefinitionId);

    List<ReceptorEntity> findAllByMechanismDefinitionIdAndStatusIn(
            UUID mechanismDefinitionId, Collection<AscriptionStatusType> statuses);
}
