package cloud.poesis.sie.defman.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.DirectiveEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.repository.DirectiveRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.RuleType;
import jakarta.persistence.EntityManager;

/**
 * GSM Directive ascription service.
 *
 * <p>
 * Manages lifecycle and persistence of {@link DirectiveEntity} ascriptions
 * including Directive consistency validation (verb/modal contradiction
 * detection)
 * and governing cascade from owning Structure.
 *
 * @author Clément Cazaud
 * @since 0.1.0
 */
@Service
public class DirectiveService extends AbstractAscriptionService {

    private static final Logger LOG = LoggerFactory.getLogger(DirectiveService.class);

    private static final Collection<AscriptionStatusType> CONSISTENCY_IN_EFFECT = List.of(AscriptionStatusType.ACTIVE,
            AscriptionStatusType.DEPRECATED);

    private static final Set<Set<String>> CONTRADICTORY_VERB_PAIRS = Set.of(
            Set.of("ENSURE", "PREVENT"));

    /** Modal precedence tiers: higher value = higher precedence. */
    static final Map<String, Integer> MODAL_PRECEDENCE = Map.of(
            "MUST", 3,
            "MUST_NOT", 3,
            "SHOULD", 2,
            "SHOULD_NOT", 2,
            "MAY", 1);

    private final DirectiveRepository directiveRepo;
    private final StructureService structureService;
    private final ArchetypeService archetypeService;

    /**
     * Constructs the Directive service with its required dependencies.
     *
     * @param directiveRepo         the directive repository
     * @param structureService      the structure service for reference resolution
     * @param archetypeService      the archetype service for qualifier resolution
     * @param definitionService     the definition service
     * @param transitionService     the status transition service
     * @param ascriptionRepository  the base ascription repository
     * @param entityManager         the JPA entity manager
     * @param dataProtectionService the data protection service
     */
    public DirectiveService(
            DirectiveRepository directiveRepo,
            StructureService structureService,
            ArchetypeService archetypeService,
            DefinitionService definitionService,
            AscriptionStatusTransitionService transitionService,
            AscriptionRepository ascriptionRepository,
            EntityManager entityManager,
            DataProtectionService dataProtectionService) {
        super(definitionService, transitionService, ascriptionRepository, entityManager, dataProtectionService);
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
        return directiveRepo.findAllByDefinitionIdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return directiveRepo.findAllByDefinitionIdAndStatusIn(definitionId, statuses);
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
            return directiveRepo.findAllByStructureId(sourceAscriptionId);
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
                .findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                        qualifierDefId, purposeDefId, CONSISTENCY_IN_EFFECT);

        for (DirectiveEntity sibling : siblings) {
            if (sibling.getDefinition().getId().equals(thisDefId)) {
                continue;
            }

            String sibVerb = sibling.getStatement().get("verb").asText();
            String sibModal = sibling.getStatement().get("modal").asText();

            if (!verb.equals(sibVerb) && CONTRADICTORY_VERB_PAIRS.contains(Set.of(verb, sibVerb))) {
                throw RuleViolationException.of(RuleType.DIRECTIVE_VERB_COMPATIBILITY,
                        "Directive contradiction: " + verb + " and " + sibVerb
                                + " on same qualifier (definition " + qualifierDefId
                                + ") and purpose (definition " + purposeDefId
                                + "). Conflicting directive: " + sibling.getId(),
                        "verb", verb, "siblingVerb", sibVerb,
                        "qualifierDefinitionId", qualifierDefId,
                        "purposeDefinitionId", purposeDefId,
                        "conflictingDirectiveId", sibling.getId());
            }

            if (verb.equals(sibVerb) && areModalContradictions(modal, sibModal)) {
                throw RuleViolationException.of(RuleType.DIRECTIVE_MODAL_COMPATIBILITY,
                        "Directive modal contradiction: " + modal + " " + verb
                                + " vs " + sibModal + " " + sibVerb
                                + " on same qualifier (definition " + qualifierDefId
                                + ") and purpose (definition " + purposeDefId
                                + "). Conflicting directive: " + sibling.getId(),
                        "verb", verb, "siblingVerb", sibVerb,
                        "modal", modal, "siblingModal", sibModal,
                        "qualifierDefinitionId", qualifierDefId,
                        "purposeDefinitionId", purposeDefId,
                        "conflictingDirectiveId", sibling.getId());
            }

            // GSM §DirectiveModal: modal precedence — higher tier overrides lower tier
            if (verb.equals(sibVerb) && !modal.equals(sibModal)) {
                int thisTier = MODAL_PRECEDENCE.getOrDefault(modal, 0);
                int sibTier = MODAL_PRECEDENCE.getOrDefault(sibModal, 0);
                if (thisTier != sibTier) {
                    String winner = thisTier > sibTier ? modal : sibModal;
                    String loser = thisTier > sibTier ? sibModal : modal;
                    LOG.warn("Directive modal precedence: {} {} overrides {} {} on verb {} "
                            + "for qualifier (definition {}) and purpose (definition {}). "
                            + "Overridden directive: {}",
                            winner, verb, loser, verb, verb,
                            qualifierDefId, purposeDefId, sibling.getId());
                }
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
