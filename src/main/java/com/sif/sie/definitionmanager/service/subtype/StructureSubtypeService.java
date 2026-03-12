package com.sif.sie.definitionmanager.service.subtype;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
import com.sif.sie.definitionmanager.entity.AscriptionEntity;
import com.sif.sie.definitionmanager.entity.DefinitionEntity;
import com.sif.sie.definitionmanager.entity.StructureEntity;
import com.sif.sie.definitionmanager.repository.StructureRepository;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;

import java.util.Map;;

@Service
public class StructureSubtypeService extends AbstractAscriptionSubtypeService {

    private final StructureRepository structureRepo;

    public StructureSubtypeService(StructureRepository structureRepo) {
        this.structureRepo = structureRepo;
    }

    @Override
    public DefinitionSubjectType getSubjectType() {
        return DefinitionSubjectType.STRUCTURE;
    }

    @Override
    public AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
        return new StructureEntity(definition, archetypeRef, statement);
    }

    @Override
    public AscriptionEntity save(AscriptionEntity entity) {
        return structureRepo.save((StructureEntity) entity);
    }

    @Override
    public Page<? extends AscriptionEntity> findAll(Pageable pageable) {
        return structureRepo.findAll(pageable);
    }

    @Override
    public Page<? extends AscriptionEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable) {
        return structureRepo.findAllByStatus(status, pageable);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionId(UUID definitionId) {
        return structureRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return structureRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
    }

    // ---- Lifecycle descriptors ----
    // Structure is NOT a referee (no references).
    // Structure is a cascade SOURCE (governing) — handled via cascade target roles
    // declared by Mechanism, Interface, Directive, Norm subtype services.
    // No identity-bound FK references (purpose is statement-based).

    @Override
    public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
        var stmt = entity.getStatement();
        var purpose = stmt.has("purpose") ? stmt.get("purpose").asText() : null;
        return purpose != null ? Map.of("purpose", purpose) : Map.of();
    }

    @Override
    public void validateActivationUniqueness(AscriptionEntity entity) {
        var stmt = entity.getStatement();
        String purpose = stmt.has("purpose") ? stmt.get("purpose").asText() : null;
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Structure purpose must not be empty");
        }
        UUID thisDefId = entity.getDefinition().getId();
        List<StructureEntity> inEffect = structureRepo.findAllByStatusIn(
                List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED));
        for (StructureEntity s : inEffect) {
            if (s.getDefinition().getId().equals(thisDefId))
                continue;
            String sPurpose = s.getStatement().has("purpose") ? s.getStatement().get("purpose").asText() : null;
            if (purpose.equals(sPurpose)) {
                throw new IllegalArgumentException(
                        "Structure purpose '" + purpose + "' duplicates in-effect Structure "
                                + s.getId() + " (definition " + s.getDefinition().getId() + ")");
            }
        }
    }
}
