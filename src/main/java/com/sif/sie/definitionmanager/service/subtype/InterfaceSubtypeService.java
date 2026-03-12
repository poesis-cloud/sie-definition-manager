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
import com.sif.sie.definitionmanager.entity.InterfaceEntity;
import com.sif.sie.definitionmanager.repository.EffectorRepository;
import com.sif.sie.definitionmanager.repository.InterfaceRepository;
import com.sif.sie.definitionmanager.repository.ReceptorRepository;
import com.sif.sie.definitionmanager.repository.StructureRepository;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;
import com.sif.sie.definitionmanager.type.CascadeType;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;

import java.util.ArrayList;
import java.util.Map;

@Service
public class InterfaceSubtypeService extends AbstractAscriptionSubtypeService {

    private final InterfaceRepository interfaceRepo;
    private final StructureRepository structureRepo;
    private final EffectorRepository effectorRepo;
    private final ReceptorRepository receptorRepo;
    private final StatementReferenceResolver referenceResolver;

    public InterfaceSubtypeService(
            InterfaceRepository interfaceRepo,
            StructureRepository structureRepo,
            EffectorRepository effectorRepo,
            ReceptorRepository receptorRepo,
            StatementReferenceResolver referenceResolver) {
        this.interfaceRepo = interfaceRepo;
        this.structureRepo = structureRepo;
        this.effectorRepo = effectorRepo;
        this.receptorRepo = receptorRepo;
        this.referenceResolver = referenceResolver;
    }

    @Override
    public DefinitionSubjectType getSubjectType() {
        return DefinitionSubjectType.INTERFACE;
    }

    @Override
    public AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
        var structure = referenceResolver.requireRef(structureRepo, statement, "structure", "structure");
        var effectors = referenceResolver.resolveRefList(effectorRepo, statement, "effectorIds");
        var receptors = referenceResolver.resolveRefList(receptorRepo, statement, "receptorIds");

        // GSM Interface validation: effectors/receptors must belong to Mechanisms
        // within the same Structure as the Interface's Structure
        UUID structureDefId = structure.getDefinition().getId();
        for (var eff : effectors) {
            UUID effStructDefId = eff.getMechanism().getStructure().getDefinition().getId();
            if (!structureDefId.equals(effStructDefId)) {
                throw new IllegalArgumentException(
                        "Interface boundary violation: effector " + eff.getId()
                                + " belongs to Structure definition " + effStructDefId
                                + ", not to Interface's Structure definition " + structureDefId);
            }
        }
        for (var rec : receptors) {
            UUID recStructDefId = rec.getMechanism().getStructure().getDefinition().getId();
            if (!structureDefId.equals(recStructDefId)) {
                throw new IllegalArgumentException(
                        "Interface boundary violation: receptor " + rec.getId()
                                + " belongs to Structure definition " + recStructDefId
                                + ", not to Interface's Structure definition " + structureDefId);
            }
        }

        return new InterfaceEntity(
                definition,
                archetypeRef,
                statement,
                structure,
                effectors,
                receptors);
    }

    @Override
    public AscriptionEntity save(AscriptionEntity entity) {
        return interfaceRepo.save((InterfaceEntity) entity);
    }

    @Override
    public Page<? extends AscriptionEntity> findAll(Pageable pageable) {
        return interfaceRepo.findAll(pageable);
    }

    @Override
    public Page<? extends AscriptionEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable) {
        return interfaceRepo.findAllByStatus(status, pageable);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionId(UUID definitionId) {
        return interfaceRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return interfaceRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
    }

    // ---- Lifecycle descriptors ----

    @Override
    public List<RefereeReference> getRefereeReferences(AscriptionEntity entity) {
        var iface = (InterfaceEntity) entity;
        var refs = new ArrayList<RefereeReference>();
        refs.add(new RefereeReference(iface.getStructure(), "structure"));
        for (var eff : iface.getEffectors()) {
            refs.add(new RefereeReference(eff, "effector"));
        }
        for (var rec : iface.getReceptors()) {
            refs.add(new RefereeReference(rec, "receptor"));
        }
        return refs;
    }

    @Override
    public Map<DefinitionSubjectType, CascadeType> getCascadeTargetRoles() {
        return Map.of(
                DefinitionSubjectType.STRUCTURE, CascadeType.GOVERNING,
                DefinitionSubjectType.EFFECTOR, CascadeType.DEPENDENT,
                DefinitionSubjectType.RECEPTOR, CascadeType.DEPENDENT);
    }

    @Override
    public List<? extends AscriptionEntity> findCascadeTargetsFrom(
            DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
        return switch (sourceType) {
            case STRUCTURE -> interfaceRepo.findAllByStructure_Id(sourceAscriptionId);
            case EFFECTOR -> interfaceRepo.findAllByEffectors_Id(sourceAscriptionId);
            case RECEPTOR -> interfaceRepo.findAllByReceptors_Id(sourceAscriptionId);
            default -> List.of();
        };
    }

    @Override
    public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
        var iface = (InterfaceEntity) entity;
        return Map.of("structure", iface.getStructure().getDefinition().getId());
    }
}
