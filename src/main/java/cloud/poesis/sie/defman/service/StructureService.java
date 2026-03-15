package cloud.poesis.sie.defman.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.repository.StructureRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;

@Service
public class StructureService extends AbstractAscriptionService {

    private final StructureRepository structureRepo;

    public StructureService(StructureRepository structureRepo) {
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

    public StructureEntity findEntityById(UUID id) {
        return structureRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Structure not found: " + id));
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
        return structureRepo.findAllByDefinitionIdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return structureRepo.findAllByDefinitionIdAndStatusIn(definitionId, statuses);
    }

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
