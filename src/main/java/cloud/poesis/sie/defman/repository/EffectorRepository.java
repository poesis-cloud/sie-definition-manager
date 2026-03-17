package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.EffectorEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

public interface EffectorRepository extends JpaRepository<EffectorEntity, UUID> {

    Page<EffectorEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    List<EffectorEntity> findAllByDefinitionIdOrderByTimestampDesc(UUID definitionId);

    List<EffectorEntity> findAllByDefinitionIdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    Page<EffectorEntity> findAllByMechanismId(UUID mechanismId, Pageable pageable);

    List<EffectorEntity> findAllByMechanismId(UUID mechanismId);

    List<EffectorEntity> findAllByMechanismDefinitionId(UUID mechanismDefinitionId);

    List<EffectorEntity> findAllByMechanismDefinitionIdAndStatusIn(
            UUID mechanismDefinitionId, Collection<AscriptionStatusType> statuses);
}
