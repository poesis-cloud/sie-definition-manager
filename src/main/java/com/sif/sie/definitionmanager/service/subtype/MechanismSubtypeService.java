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
import com.sif.sie.definitionmanager.entity.MechanismEntity;
import com.sif.sie.definitionmanager.repository.MechanismRepository;
import com.sif.sie.definitionmanager.repository.StructureRepository;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;
import com.sif.sie.definitionmanager.type.CascadeType;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;
import com.sif.sie.definitionmanager.validator.MechanismModeValidator;
import com.sif.sie.definitionmanager.validator.StarlarkRuleValidator;

import java.util.Map;

@Service
public class MechanismSubtypeService extends AbstractAscriptionSubtypeService {

    private final MechanismRepository mechanismRepo;
    private final StructureRepository structureRepo;
    private final StatementReferenceResolver referenceResolver;
    private final MechanismModeValidator modeValidator;
    private final StarlarkRuleValidator starlarkValidator;

    public MechanismSubtypeService(
            MechanismRepository mechanismRepo,
            StructureRepository structureRepo,
            StatementReferenceResolver referenceResolver,
            MechanismModeValidator modeValidator,
            StarlarkRuleValidator starlarkValidator) {
        this.mechanismRepo = mechanismRepo;
        this.structureRepo = structureRepo;
        this.referenceResolver = referenceResolver;
        this.modeValidator = modeValidator;
        this.starlarkValidator = starlarkValidator;
    }

    @Override
    public DefinitionSubjectType getSubjectType() {
        return DefinitionSubjectType.MECHANISM;
    }

    @Override
    public AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
        MechanismEntity entity = new MechanismEntity(
                definition,
                archetypeRef,
                statement,
                referenceResolver.requireRef(structureRepo, statement, "structure", "structure"));

        // GSM: generative/declarative mutual exclusivity at creation time
        modeValidator.validateCreation(entity);

        // GSM: Starlark rule structural validation (generative mode)
        if (statement.has("rule") && !statement.get("rule").isNull()) {
            String rule = statement.get("rule").asText();
            if (!rule.isBlank()) {
                starlarkValidator.validate(rule);
            }
        }

        return entity;
    }

    @Override
    public AscriptionEntity save(AscriptionEntity entity) {
        return mechanismRepo.save((MechanismEntity) entity);
    }

    @Override
    public Page<? extends AscriptionEntity> findAll(Pageable pageable) {
        return mechanismRepo.findAll(pageable);
    }

    @Override
    public Page<? extends AscriptionEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable) {
        return mechanismRepo.findAllByStatus(status, pageable);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionId(UUID definitionId) {
        return mechanismRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return mechanismRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
    }

    // ---- Lifecycle descriptors ----

    @Override
    public List<RefereeReference> getRefereeReferences(AscriptionEntity entity) {
        var m = (MechanismEntity) entity;
        return List.of(new RefereeReference(m.getStructure(), "structure"));
    }

    @Override
    public Map<DefinitionSubjectType, CascadeType> getCascadeTargetRoles() {
        return Map.of(DefinitionSubjectType.STRUCTURE, CascadeType.GOVERNING);
    }

    @Override
    public List<? extends AscriptionEntity> findCascadeTargetsFrom(
            DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
        if (sourceType == DefinitionSubjectType.STRUCTURE) {
            return mechanismRepo.findAllByStructure_Id(sourceAscriptionId);
        }
        return List.of();
    }

    @Override
    public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
        var m = (MechanismEntity) entity;
        return Map.of(
                "structure", m.getStructure().getDefinition().getId(),
                "function", m.getStatement().get("function").asText());
    }

    @Override
    public void validateActivationUniqueness(AscriptionEntity entity) {
        var m = (MechanismEntity) entity;

        // GSM: declarative mode must have in-effect ports at activation
        modeValidator.validateActivation(m);

        String function = m.getStatement().has("function") ? m.getStatement().get("function").asText() : null;
        if (function == null || function.isBlank()) {
            throw new IllegalArgumentException("Mechanism function must not be empty");
        }
        UUID structureDefId = m.getStructure().getDefinition().getId();
        UUID thisDefId = m.getDefinition().getId();
        List<MechanismEntity> inEffect = mechanismRepo.findAllByStructure_Definition_IdAndStatusIn(
                structureDefId,
                List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED));
        for (MechanismEntity sibling : inEffect) {
            if (sibling.getDefinition().getId().equals(thisDefId))
                continue;
            String sibFunc = sibling.getStatement().has("function")
                    ? sibling.getStatement().get("function").asText()
                    : null;
            if (function.equals(sibFunc)) {
                throw new IllegalArgumentException(
                        "Mechanism function '" + function + "' duplicates in-effect Mechanism "
                                + sibling.getId() + " within Structure definition "
                                + structureDefId);
            }
        }
    }
}
