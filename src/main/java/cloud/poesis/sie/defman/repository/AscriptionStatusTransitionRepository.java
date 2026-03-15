package cloud.poesis.sie.defman.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;

public interface AscriptionStatusTransitionRepository
        extends JpaRepository<AscriptionStatusTransitionEntity, UUID> {
    List<AscriptionStatusTransitionEntity> findAllByAscriptionIdOrderByTimestampAsc(
            UUID ascriptionId);
}
