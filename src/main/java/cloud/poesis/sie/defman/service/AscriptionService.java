package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.type.PrimitiveType;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for the union ascription table (base {@link AscriptionEntity}). Handles cross-subtype
 * lookups (getById) where the caller does not know the concrete entity type in advance.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
@Transactional("transactionManager")
public class AscriptionService {

  private final AscriptionRepository ascriptionRepository;

  /**
   * Constructs the service with its required repository.
   *
   * @param ascriptionRepository the union ascription repository
   */
  public AscriptionService(AscriptionRepository ascriptionRepository) {
    this.ascriptionRepository = ascriptionRepository;
  }

  /**
   * Retrieves an ascription by its unique identifier.
   *
   * @param ascriptionId the ascription UUID
   * @return the ascription entity
   * @throws ResourceNotFoundException if no ascription exists with the given id
   */
  @Transactional(value = "transactionManager", readOnly = true)
  public AscriptionEntity getById(UUID ascriptionId) {
    return ascriptionRepository
        .findById(ascriptionId)
        .orElseThrow(() -> new ResourceNotFoundException(PrimitiveType.ASCRIPTION, ascriptionId));
  }
}
