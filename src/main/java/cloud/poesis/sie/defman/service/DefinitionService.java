package cloud.poesis.sie.defman.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.repository.DefinitionRepository;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;

/**
 * Service for GSM Definition (stable identity) operations.
 * Owns {@link DefinitionRepository}.
 */
@Service
@Transactional("transactionManager")
public class DefinitionService {

    private final DefinitionRepository definitionRepository;

    public DefinitionService(DefinitionRepository definitionRepository) {
        this.definitionRepository = definitionRepository;
    }

    @Transactional(value = "transactionManager", readOnly = true)
    public DefinitionEntity getById(@NonNull UUID id) {
        return definitionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No definition found for id: " + id));
    }

    /**
     * Batch-fetches Definitions by their IDs.
     *
     * <p>Part of the explicit-fetch design (see README.md
     * § "Batch fetch pattern").
     *
     * @param ids collection of Definition IDs to retrieve
     * @return map of ID → DefinitionEntity; IDs not found in the database
     *         are silently absent from the returned map
     */
    @Transactional(value = "transactionManager", readOnly = true)
    public Map<UUID, DefinitionEntity> getByIds(Collection<UUID> ids) {
        return definitionRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(DefinitionEntity::getId, Function.identity()));
    }

    public DefinitionEntity create(DefinitionSubjectType type) {
        return definitionRepository.save(new DefinitionEntity(type));
    }

    /**
     * Resolves an existing Definition by id, or creates a new one if
     * definitionId is null.
     */
    public DefinitionEntity resolveOrCreate(UUID definitionId, DefinitionSubjectType type) {
        if (definitionId != null) {
            return getById(definitionId);
        }
        return create(type);
    }
}
