package com.sif.sie.definitionmanager.service.subtype;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sif.sie.definitionmanager.client.SchemaRegistryClient;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
import com.sif.sie.definitionmanager.entity.AscriptionEntity;
import com.sif.sie.definitionmanager.entity.DefinitionEntity;
import com.sif.sie.definitionmanager.repository.ArchetypeRepository;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;
import com.sif.sie.definitionmanager.validator.AllOfChainValidator;
import com.sif.sie.definitionmanager.validator.GsmAnnotationValidator;

@Service
public class ArchetypeSubtypeService extends AbstractAscriptionSubtypeService {

    private final ArchetypeRepository archetypeRepo;
    private final SchemaRegistryClient schemaRegistryClient;
    private final AllOfChainValidator allOfChainValidator;
    private final GsmAnnotationValidator gsmAnnotationValidator;

    public ArchetypeSubtypeService(
            ArchetypeRepository archetypeRepo,
            SchemaRegistryClient schemaRegistryClient,
            AllOfChainValidator allOfChainValidator,
            GsmAnnotationValidator gsmAnnotationValidator) {
        this.archetypeRepo = archetypeRepo;
        this.schemaRegistryClient = schemaRegistryClient;
        this.allOfChainValidator = allOfChainValidator;
        this.gsmAnnotationValidator = gsmAnnotationValidator;
    }

    @Override
    public DefinitionSubjectType getSubjectType() {
        return DefinitionSubjectType.ARCHETYPE;
    }

    @Override
    public AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
        JsonNode schema = statement.get("schema");

        // GSM §5: allOf chain convergence + §8: $gsm:sealed enforcement
        allOfChainValidator.validate(schema);

        // GSM §8: $gsm:* annotation well-formedness
        gsmAnnotationValidator.validateArchetypeAnnotations(schema, definition.getId());

        String subject = "gsm_archetype_definition_" + definition.getId();
        schemaRegistryClient.registerSchema(subject, schema);
        return new ArchetypeEntity(definition, archetypeRef, statement);
    }

    @Override
    public AscriptionEntity save(AscriptionEntity entity) {
        return archetypeRepo.save((ArchetypeEntity) entity);
    }

    @Override
    public Page<? extends AscriptionEntity> findAll(Pageable pageable) {
        return archetypeRepo.findAll(pageable);
    }

    @Override
    public Page<? extends AscriptionEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable) {
        return archetypeRepo.findAllByStatus(status, pageable);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionId(UUID definitionId) {
        return archetypeRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return archetypeRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
    }

    // ---- Lifecycle descriptors ----
    // Archetype is NOT a referee (no FK references to other ascriptions).
    // Archetype receives no cascades.

    @Override
    public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
        JsonNode schema = entity.getStatement().get("schema");
        if (schema == null || !schema.has("title"))
            return Map.of();
        return Map.of("schema.title", schema.get("title").asText());
    }

    @Override
    public void validateActivationUniqueness(AscriptionEntity entity) {
        JsonNode schema = entity.getStatement().get("schema");
        if (schema == null || !schema.has("title")) {
            throw new IllegalArgumentException("Archetype schema.title must not be null or empty");
        }
        String title = schema.get("title").asText();
        if (title.isBlank()) {
            throw new IllegalArgumentException("Archetype schema.title must not be empty");
        }
        UUID thisDefId = entity.getDefinition().getId();
        List<ArchetypeEntity> inEffect = archetypeRepo.findAllByStatusIn(
                List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED));
        for (ArchetypeEntity a : inEffect) {
            if (a.getDefinition().getId().equals(thisDefId))
                continue;
            JsonNode aSchema = a.getStatement().get("schema");
            String aTitle = (aSchema != null && aSchema.has("title")) ? aSchema.get("title").asText() : null;
            if (title.equals(aTitle)) {
                throw new IllegalArgumentException(
                        "Archetype schema.title '" + title + "' duplicates in-effect Archetype "
                                + a.getId() + " (definition " + a.getDefinition().getId() + ")");
            }
        }
    }
}
