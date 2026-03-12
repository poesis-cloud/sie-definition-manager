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
import com.sif.sie.definitionmanager.entity.DirectiveEntity;
import com.sif.sie.definitionmanager.repository.ArchetypeRepository;
import com.sif.sie.definitionmanager.repository.DirectiveRepository;
import com.sif.sie.definitionmanager.repository.StructureRepository;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;
import com.sif.sie.definitionmanager.type.CascadeType;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;
import com.sif.sie.definitionmanager.validator.DirectiveConsistencyValidator;

import java.util.Map;

@Service
public class DirectiveSubtypeService extends AbstractAscriptionSubtypeService {

    private final DirectiveRepository directiveRepo;
    private final StructureRepository structureRepo;
    private final ArchetypeRepository archetypeRepo;
    private final StatementReferenceResolver referenceResolver;
    private final DirectiveConsistencyValidator consistencyValidator;

    public DirectiveSubtypeService(
            DirectiveRepository directiveRepo,
            StructureRepository structureRepo,
            ArchetypeRepository archetypeRepo,
            StatementReferenceResolver referenceResolver,
            DirectiveConsistencyValidator consistencyValidator) {
        this.directiveRepo = directiveRepo;
        this.structureRepo = structureRepo;
        this.archetypeRepo = archetypeRepo;
        this.referenceResolver = referenceResolver;
        this.consistencyValidator = consistencyValidator;
    }

    @Override
    public DefinitionSubjectType getSubjectType() {
        return DefinitionSubjectType.DIRECTIVE;
    }

    @Override
    public AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
        return new DirectiveEntity(
                definition,
                archetypeRef,
                statement,
                referenceResolver.requireRef(structureRepo, statement, "structure", "structure"),
                referenceResolver.requireRef(archetypeRepo, statement, "qualifier", "qualifier"),
                referenceResolver.requireRef(structureRepo, statement, "purpose", "purpose"));
    }

    @Override
    public AscriptionEntity save(AscriptionEntity entity) {
        return directiveRepo.save((DirectiveEntity) entity);
    }

    @Override
    public Page<? extends AscriptionEntity> findAll(Pageable pageable) {
        return directiveRepo.findAll(pageable);
    }

    @Override
    public Page<? extends AscriptionEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable) {
        return directiveRepo.findAllByStatus(status, pageable);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionId(UUID definitionId) {
        return directiveRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return directiveRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
    }

    // ---- Lifecycle descriptors ----

    @Override
    public List<RefereeReference> getRefereeReferences(AscriptionEntity entity) {
        var d = (DirectiveEntity) entity;
        return List.of(
                new RefereeReference(d.getStructure(), "structure"),
                new RefereeReference(d.getQualifier(), "qualifier"),
                new RefereeReference(d.getPurpose(), "purpose"));
    }

    @Override
    public Map<DefinitionSubjectType, CascadeType> getCascadeTargetRoles() {
        return Map.of(DefinitionSubjectType.STRUCTURE, CascadeType.GOVERNING);
    }

    @Override
    public List<? extends AscriptionEntity> findCascadeTargetsFrom(
            DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
        if (sourceType == DefinitionSubjectType.STRUCTURE) {
            return directiveRepo.findAllByStructure_Id(sourceAscriptionId);
        }
        return List.of();
    }

    @Override
    public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
        var d = (DirectiveEntity) entity;
        return Map.of(
                "structure", d.getStructure().getDefinition().getId(),
                "qualifier", d.getQualifier().getDefinition().getId(),
                "purpose", d.getPurpose().getDefinition().getId());
    }

    @Override
    public void validateActivationUniqueness(AscriptionEntity entity) {
        consistencyValidator.validate((DirectiveEntity) entity);
    }
}
