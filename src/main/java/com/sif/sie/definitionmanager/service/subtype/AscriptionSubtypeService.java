package com.sif.sie.definitionmanager.service.subtype;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.fasterxml.jackson.databind.JsonNode;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
import com.sif.sie.definitionmanager.entity.AscriptionEntity;
import com.sif.sie.definitionmanager.entity.DefinitionEntity;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;

public interface AscriptionSubtypeService {

    DefinitionSubjectType getSubjectType();

    AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement);

    AscriptionEntity save(AscriptionEntity entity);

    Page<? extends AscriptionEntity> findAll(Pageable pageable);

    Page<? extends AscriptionEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable);

    List<? extends AscriptionEntity> findAllByDefinitionId(UUID definitionId);

    List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses);
}
