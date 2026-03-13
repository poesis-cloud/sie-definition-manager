package io.poesis.sie.defman.service;

import java.util.UUID;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.poesis.sie.defman.entity.DefinitionEntity;
import io.poesis.sie.defman.repository.DefinitionRepository;
import io.poesis.sie.defman.type.DefinitionSubjectType;

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
