package io.poesis.sie.defman.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import io.poesis.sie.defman.entity.ArchetypeEntity;
import io.poesis.sie.defman.entity.AscriptionEntity;
import io.poesis.sie.defman.entity.DefinitionEntity;
import io.poesis.sie.defman.entity.DirectiveEntity;
import io.poesis.sie.defman.entity.StructureEntity;
import io.poesis.sie.defman.repository.DirectiveRepository;
import io.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import io.poesis.sie.defman.type.AscriptionStatusType;
import io.poesis.sie.defman.type.DefinitionSubjectType;

@Service
public class DirectiveService extends AbstractAscriptionService {

    private static final Collection<AscriptionStatusType> CONSISTENCY_IN_EFFECT = List.of(AscriptionStatusType.ACTIVE,
            AscriptionStatusType.DEPRECATED);

    private static final Set<Set<String>> CONTRADICTORY_VERB_PAIRS = Set.of(
            Set.of("ENSURE", "PREVENT"));

    private final DirectiveRepository directiveRepo;
    private final StructureService structureService;
    private final ArchetypeService archetypeService;

    public DirectiveService(
            DirectiveRepository directiveRepo,
            StructureService structureService,
            ArchetypeService archetypeService) {
        this.directiveRepo = directiveRepo;
        this.structureService = structureService;
        this.archetypeService = archetypeService;
    }

    @Override
    public DefinitionSubjectType getSubjectType() {
        return DefinitionSubjectType.DIRECTIVE;
    }

    @Override
    public AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
        UUID structureId = extractRequiredUuid(statement, "structure");
        StructureEntity structure = structureService.findEntityById(structureId);

        UUID qualifierId = extractRequiredUuid(statement, "qualifier");
        ArchetypeEntity qualifier = archetypeService.findEntityById(qualifierId);

        UUID purposeId = extractRequiredUuid(statement, "purpose");
        StructureEntity purpose = structureService.findEntityById(purposeId);

        return new DirectiveEntity(
                definition,
                archetypeRef,
                statement,
                structure,
                qualifier,
                purpose);
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
    public Map<DefinitionSubjectType, AscriptionStatusTransitionCascadeType> getCascadeTargetRoles() {
        return Map.of(DefinitionSubjectType.STRUCTURE, AscriptionStatusTransitionCascadeType.GOVERNING);
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
        validateDirectiveConsistency((DirectiveEntity) entity);
    }

    // ======================================================================
    // Directive consistency validation (inlined from DirectiveConsistencyValidator)
    // ======================================================================

    private void validateDirectiveConsistency(DirectiveEntity directive) {
        UUID qualifierDefId = directive.getQualifier().getDefinition().getId();
        UUID purposeDefId = directive.getPurpose().getDefinition().getId();
        UUID thisDefId = directive.getDefinition().getId();

        String verb = directive.getStatement().get("verb").asText();
        String modal = directive.getStatement().get("modal").asText();

        List<DirectiveEntity> siblings = directiveRepo
                .findAllByQualifier_Definition_IdAndPurpose_Definition_IdAndStatusIn(
                        qualifierDefId, purposeDefId, CONSISTENCY_IN_EFFECT);

        for (DirectiveEntity sibling : siblings) {
            if (sibling.getDefinition().getId().equals(thisDefId)) {
                continue;
            }

            String sibVerb = sibling.getStatement().get("verb").asText();
            String sibModal = sibling.getStatement().get("modal").asText();

            if (!verb.equals(sibVerb) && CONTRADICTORY_VERB_PAIRS.contains(Set.of(verb, sibVerb))) {
                throw new IllegalArgumentException(
                        "Directive contradiction: " + verb + " and " + sibVerb
                                + " on same qualifier (definition " + qualifierDefId
                                + ") and purpose (definition " + purposeDefId
                                + "). Conflicting directive: " + sibling.getId());
            }

            if (verb.equals(sibVerb) && areModalContradictions(modal, sibModal)) {
                throw new IllegalArgumentException(
                        "Directive modal contradiction: " + modal + " " + verb
                                + " vs " + sibModal + " " + sibVerb
                                + " on same qualifier (definition " + qualifierDefId
                                + ") and purpose (definition " + purposeDefId
                                + "). Conflicting directive: " + sibling.getId());
            }
        }
    }

    private static boolean areModalContradictions(String a, String b) {
        return (a.equals("MUST") && b.equals("MUST_NOT"))
                || (a.equals("MUST_NOT") && b.equals("MUST"))
                || (a.equals("SHOULD") && b.equals("SHOULD_NOT"))
                || (a.equals("SHOULD_NOT") && b.equals("SHOULD"));
    }
}
