package cloud.poesis.sie.defman.repository;

import java.util.List;
import java.util.UUID;

import cloud.poesis.sie.defman.entity.InteractionEntity;

/**
 * Spring Data JPA repository for {@link InteractionEntity} (the
 * {@code interaction} table).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public interface InteractionRepository extends AbstractAscriptionRepository<InteractionEntity> {

    /**
     * Returns all interactions that originate from a given effector.
     *
     * @param effectorId the effector UUID
     * @return the matching interaction entities
     */
    List<InteractionEntity> findAllByEffectorId(UUID effectorId);

    /**
     * Returns all interactions that terminate at a given receptor.
     *
     * @param receptorId the receptor UUID
     * @return the matching interaction entities
     */
    List<InteractionEntity> findAllByReceptorId(UUID receptorId);
}
