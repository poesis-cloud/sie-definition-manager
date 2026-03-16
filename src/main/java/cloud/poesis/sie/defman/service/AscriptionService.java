package cloud.poesis.sie.defman.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.repository.AscriptionRepository;

/**
 * Service for the union ascription table (base {@link AscriptionEntity}).
 * Handles cross-subtype lookups (getById) where the caller does not know
 * the concrete entity type in advance.
 */
@Service
@Transactional("transactionManager")
public class AscriptionService {

    private final AscriptionRepository ascriptionRepository;

    public AscriptionService(AscriptionRepository ascriptionRepository) {
        this.ascriptionRepository = ascriptionRepository;
    }

    @Transactional(value = "transactionManager", readOnly = true)
    public AscriptionEntity getById(UUID ascriptionId) {
        return ascriptionRepository.findById(ascriptionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No ascription found for id: " + ascriptionId));
    }
}
