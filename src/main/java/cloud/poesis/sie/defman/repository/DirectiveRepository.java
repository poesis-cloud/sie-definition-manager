package cloud.poesis.sie.defman.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.DirectiveEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;

public interface DirectiveRepository extends JpaRepository<DirectiveEntity, UUID> {

    Page<DirectiveEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    List<DirectiveEntity> findAllByDefinitionIdOrderByTimestampDesc(UUID definitionId);

    List<DirectiveEntity> findAllByDefinitionIdAndStatusIn(
            UUID definitionId, Collection<AscriptionStatusType> statuses);

    Page<DirectiveEntity> findAllByStructureId(UUID structureId, Pageable pageable);

    List<DirectiveEntity> findAllByStructureId(UUID structureId);

    List<DirectiveEntity> findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
            UUID qualifierDefinitionId, UUID purposeDefinitionId,
            Collection<AscriptionStatusType> statuses);
}
