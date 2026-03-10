package com.sif.sie.definitionmanager.service;

import java.util.UUID;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sif.sie.definitionmanager.entity.DefinitionEntity;
import com.sif.sie.definitionmanager.repository.DefinitionRepository;

/**
 * Service for GSM Definition (stable identity) operations.
 *
 * <p>
 * Definitions are the immutable identity anchors that persist across Ascription
 * versions. Ascription-level CRUD and lifecycle management live in
 * {@link AscriptionService}.
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
}
