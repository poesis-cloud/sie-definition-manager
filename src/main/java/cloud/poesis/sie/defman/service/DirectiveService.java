package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.DirectiveEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.repository.DirectiveRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * GSM Directive ascription service.
 *
 * <p>Manages lifecycle and persistence of {@link DirectiveEntity} ascriptions including Directive
 * consistency validation (verb/modal contradiction detection) and governing cascade from owning
 * Structure.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class DirectiveService extends AbstractAscriptionService<DirectiveEntity> {

  private final DirectiveRepository directiveRepo;
  private final StructureService structureService;
  private final ArchetypeService archetypeService;
  private final AppraisalService appraisalService;

  /**
   * Constructs the Directive service with its required dependencies.
   *
   * @param directiveRepo the directive repository
   * @param structureService the structure service for reference resolution
   * @param archetypeService the archetype service for qualifier resolution
   * @param definitionService the definition service
   * @param transitionService the status transition service
   * @param ascriptionService the ascription service for cross-subtype queries
   * @param entityManager the JPA entity manager
   * @param dataProtectionService the data protection service
   * @param appraisalService the appraisal service for governance compatibility checks (lazy to
   *     break circular dependency)
   */
  public DirectiveService(
      DirectiveRepository directiveRepo,
      StructureService structureService,
      ArchetypeService archetypeService,
      ArchetypeRepository archetypeRepository,
      DefinitionService definitionService,
      AscriptionStatusTransitionService transitionService,
      AscriptionService ascriptionService,
      EntityManager entityManager,
      DataProtectionService dataProtectionService,
      @Lazy AppraisalService appraisalService) {
    super(
        definitionService,
        transitionService,
        ascriptionService,
        archetypeRepository,
        entityManager,
        dataProtectionService);
    this.directiveRepo = directiveRepo;
    this.structureService = structureService;
    this.archetypeService = archetypeService;
    this.appraisalService = appraisalService;
  }

  @Override
  public DefinitionSubjectType getSubjectType() {
    return DefinitionSubjectType.DIRECTIVE;
  }

  @Override
  protected AbstractAscriptionRepository<DirectiveEntity> getRepository() {
    return directiveRepo;
  }

  @Override
  public DirectiveEntity buildEntity(
      DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
    UUID structureId = extractRequiredUuid(statement, "structure");
    StructureEntity structure = structureService.findEntityById(structureId);

    UUID qualifierId = extractRequiredUuid(statement, "qualifier");
    ArchetypeEntity qualifier = archetypeService.findEntityById(qualifierId);

    UUID purposeId = extractRequiredUuid(statement, "purpose");
    StructureEntity purpose = structureService.findEntityById(purposeId);

    return new DirectiveEntity(definition, archetypeRef, statement, structure, qualifier, purpose);
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

  /**
   * Returns directives whose purpose targets the given structure definition, filtered by statuses.
   *
   * @param purposeDefinitionId the purpose structure definition UUID
   * @param statuses the lifecycle statuses to match
   * @return the matching directive entities
   */
  public List<DirectiveEntity> findAllByPurposeDefinitionIdAndStatusIn(
      UUID purposeDefinitionId, Collection<AscriptionStatusType> statuses) {
    return directiveRepo.findAllByPurposeDefinitionIdAndStatusIn(purposeDefinitionId, statuses);
  }

  /**
   * Returns directives sharing the same qualifier + purpose axis, filtered by statuses.
   *
   * @param qualifierDefinitionId the qualifier definition UUID
   * @param purposeDefinitionId the purpose definition UUID
   * @param statuses the lifecycle statuses to match
   * @return the matching directive entities
   */
  public List<DirectiveEntity> findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
      UUID qualifierDefinitionId,
      UUID purposeDefinitionId,
      Collection<AscriptionStatusType> statuses) {
    return directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
        qualifierDefinitionId, purposeDefinitionId, statuses);
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
    appraisalService.validateDirectiveCompatibility((DirectiveEntity) entity);
  }
}
