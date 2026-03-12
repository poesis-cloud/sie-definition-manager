package com.sif.sie.definitionmanager.service.subtype;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
import com.sif.sie.definitionmanager.entity.AscriptionEntity;
import com.sif.sie.definitionmanager.entity.DefinitionEntity;
import com.sif.sie.definitionmanager.entity.NormEntity;
import com.sif.sie.definitionmanager.repository.ArchetypeRepository;
import com.sif.sie.definitionmanager.repository.NormRepository;
import com.sif.sie.definitionmanager.repository.StructureRepository;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;
import com.sif.sie.definitionmanager.type.CascadeType;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;
import com.sif.sie.definitionmanager.validator.CelProfileValidator;

@Service
public class NormSubtypeService extends AbstractAscriptionSubtypeService {

    private final NormRepository normRepo;
    private final StructureRepository structureRepo;
    private final ArchetypeRepository archetypeRepo;
    private final StatementReferenceResolver referenceResolver;
    private final CelProfileValidator celProfileValidator;

    public NormSubtypeService(
            NormRepository normRepo,
            StructureRepository structureRepo,
            ArchetypeRepository archetypeRepo,
            StatementReferenceResolver referenceResolver,
            CelProfileValidator celProfileValidator) {
        this.normRepo = normRepo;
        this.structureRepo = structureRepo;
        this.archetypeRepo = archetypeRepo;
        this.referenceResolver = referenceResolver;
        this.celProfileValidator = celProfileValidator;
    }

    @Override
    public DefinitionSubjectType getSubjectType() {
        return DefinitionSubjectType.NORM;
    }

    @Override
    public AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
        // GSM: validate CEL profiles before building entity
        if (statement.has("guard")) {
            celProfileValidator.validateGuard(statement.get("guard").asText());
        }
        if (statement.has("predicate")) {
            celProfileValidator.validatePredicate(statement.get("predicate").asText());
        }

        return new NormEntity(
                definition,
                archetypeRef,
                statement,
                referenceResolver.requireRef(structureRepo, statement, "structure", "structure"),
                referenceResolver.requireRef(archetypeRepo, statement, "qualifier", "qualifier"));
    }

    @Override
    public AscriptionEntity save(AscriptionEntity entity) {
        return normRepo.save((NormEntity) entity);
    }

    @Override
    public Page<? extends AscriptionEntity> findAll(Pageable pageable) {
        return normRepo.findAll(pageable);
    }

    @Override
    public Page<? extends AscriptionEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable) {
        return normRepo.findAllByStatus(status, pageable);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionId(UUID definitionId) {
        return normRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return normRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
    }

    // ---- Lifecycle descriptors ----

    @Override
    public List<RefereeReference> getRefereeReferences(AscriptionEntity entity) {
        var n = (NormEntity) entity;
        return List.of(
                new RefereeReference(n.getStructure(), "structure"),
                new RefereeReference(n.getQualifier(), "qualifier"));
    }

    @Override
    public Map<DefinitionSubjectType, CascadeType> getCascadeTargetRoles() {
        return Map.of(DefinitionSubjectType.STRUCTURE, CascadeType.GOVERNING);
    }

    @Override
    public List<? extends AscriptionEntity> findCascadeTargetsFrom(
            DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
        if (sourceType == DefinitionSubjectType.STRUCTURE) {
            return normRepo.findAllByStructure_Id(sourceAscriptionId);
        }
        return List.of();
    }

    @Override
    public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
        var n = (NormEntity) entity;
        var values = new LinkedHashMap<String, Object>();
        values.put("structure", n.getStructure().getDefinition().getId());
        values.put("qualifier", n.getQualifier().getDefinition().getId());
        var stmt = n.getStatement();
        if (stmt.has("predicate")) {
            values.put("predicate", stmt.get("predicate").asText());
        }
        return values;
    }
}
