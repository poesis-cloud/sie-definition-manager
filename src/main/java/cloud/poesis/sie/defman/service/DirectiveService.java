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
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
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

    return new DirectiveEntity(definition, archetypeRef, statement, structure, qualifier);
  }

  // ---- Lifecycle descriptors ----

  @Override
  public List<RefereeReference> getRefereeReferences(AscriptionEntity entity) {
    var d = (DirectiveEntity) entity;
    return List.of(
        new RefereeReference(d.getStructure(), "structure"),
        new RefereeReference(d.getQualifier(), "qualifier"));
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
   * Returns in-effect directives whose statement purpose matches the given string.
   *
   * @param purpose the governed purpose string
   * @return the matching directive entities
   */
  public List<DirectiveEntity> findAllInEffectByPurpose(String purpose) {
    return directiveRepo.findAllInEffectByPurpose(purpose);
  }

  @Override
  public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
    var d = (DirectiveEntity) entity;
    var stmt = d.getStatement();
    var purpose = stmt.has("purpose") ? stmt.get("purpose").asText() : null;
    return purpose != null
        ? Map.of(
            "structure", d.getStructure().getDefinition().getId(),
            "qualifier", d.getQualifier().getDefinition().getId(),
            "purpose", purpose)
        : Map.of(
            "structure", d.getStructure().getDefinition().getId(),
            "qualifier", d.getQualifier().getDefinition().getId());
  }

  @Override
  public void validateActivationUniqueness(AscriptionEntity entity) {
    appraisalService.validateDirectiveCompatibility((DirectiveEntity) entity);
  }
}
