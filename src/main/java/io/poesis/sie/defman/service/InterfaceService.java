package io.poesis.sie.defman.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import io.poesis.sie.defman.entity.ArchetypeEntity;
import io.poesis.sie.defman.entity.AscriptionEntity;
import io.poesis.sie.defman.entity.DefinitionEntity;
import io.poesis.sie.defman.entity.EffectorEntity;
import io.poesis.sie.defman.entity.InterfaceEntity;
import io.poesis.sie.defman.entity.ReceptorEntity;
import io.poesis.sie.defman.entity.StructureEntity;
import io.poesis.sie.defman.repository.InterfaceRepository;
import io.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import io.poesis.sie.defman.type.AscriptionStatusType;
import io.poesis.sie.defman.type.DefinitionSubjectType;

@Service
public class InterfaceService extends AbstractAscriptionService {

    private final InterfaceRepository interfaceRepo;
    private final StructureService structureService;
    private final EffectorService effectorService;
    private final ReceptorService receptorService;

    public InterfaceService(
            InterfaceRepository interfaceRepo,
            StructureService structureService,
            EffectorService effectorService,
            ReceptorService receptorService) {
        this.interfaceRepo = interfaceRepo;
        this.structureService = structureService;
        this.effectorService = effectorService;
        this.receptorService = receptorService;
    }

    @Override
    public DefinitionSubjectType getSubjectType() {
        return DefinitionSubjectType.INTERFACE;
    }

    @Override
    public AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
        UUID structureId = extractRequiredUuid(statement, "structure");
        StructureEntity structure = structureService.findEntityById(structureId);

        List<UUID> effectorIds = extractUuidList(statement, "effectorIds");
        List<EffectorEntity> effectors = effectorIds.stream()
                .map(effectorService::findEntityById)
                .toList();

        List<UUID> receptorIds = extractUuidList(statement, "receptorIds");
        List<ReceptorEntity> receptors = receptorIds.stream()
                .map(receptorService::findEntityById)
                .toList();

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
        return interfaceRepo.findAllByDefinitionIdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return interfaceRepo.findAllByDefinitionIdAndStatusIn(definitionId, statuses);
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
    public Map<DefinitionSubjectType, AscriptionStatusTransitionCascadeType> getCascadeTargetRoles() {
        return Map.of(
                DefinitionSubjectType.STRUCTURE, AscriptionStatusTransitionCascadeType.GOVERNING,
                DefinitionSubjectType.EFFECTOR, AscriptionStatusTransitionCascadeType.DEPENDENT,
                DefinitionSubjectType.RECEPTOR, AscriptionStatusTransitionCascadeType.DEPENDENT);
    }

    @Override
    public List<? extends AscriptionEntity> findCascadeTargetsFrom(
            DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
        return switch (sourceType) {
            case STRUCTURE -> interfaceRepo.findAllByStructureId(sourceAscriptionId);
            case EFFECTOR -> interfaceRepo.findAllByEffectorsId(sourceAscriptionId);
            case RECEPTOR -> interfaceRepo.findAllByReceptorsId(sourceAscriptionId);
            default -> List.of();
        };
    }

    @Override
    public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
        var iface = (InterfaceEntity) entity;
        return Map.of("structure", iface.getStructure().getDefinition().getId());
    }
}
