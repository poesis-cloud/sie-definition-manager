package cloud.poesis.sie.defman.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;

/**
 * Spring Data JPA repository for {@link AscriptionStatusTransitionEntity}
 * (the {@code ascription_status_transition} table).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public interface AscriptionStatusTransitionRepository
        extends JpaRepository<AscriptionStatusTransitionEntity, UUID> {

    /**
     * Returns all status transitions for a given ascription ordered by time.
     *
     * @param ascriptionId the ascription UUID
     * @return the matching transition records in chronological order
     */
    List<AscriptionStatusTransitionEntity> findAllByAscriptionIdOrderByTimestampAsc(
            UUID ascriptionId);
}
