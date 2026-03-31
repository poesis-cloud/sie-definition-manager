package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.entity.ReceptorEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.repository.ReceptorRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * GSM Receptor ascription service.
 *
 * <p>Manages lifecycle and persistence of {@link ReceptorEntity} ascriptions with constitutive
 * cascade from owning Mechanism and dependent cascade to downstream Interactions.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class ReceptorService extends AbstractAscriptionService<ReceptorEntity> {

  private final ReceptorRepository receptorRepo;
  private final MechanismService mechanismService;
  private final ArchetypeService archetypeService;

  /**
   * Constructs the Receptor service with its required dependencies.
   *
   * @param receptorRepo the receptor repository
   * @param mechanismService the mechanism service for reference resolution
   * @param archetypeService the archetype service for data archetype resolution
   * @param definitionService the definition service
   * @param transitionService the status transition service
   * @param ascriptionRepository the base ascription repository
   * @param entityManager the JPA entity manager
   * @param dataProtectionService the data protection service
   */
  public ReceptorService(
      ReceptorRepository receptorRepo,
      MechanismService mechanismService,
      ArchetypeService archetypeService,
      ArchetypeRepository archetypeRepository,
      DefinitionService definitionService,
      AscriptionStatusTransitionService transitionService,
      AscriptionRepository ascriptionRepository,
      EntityManager entityManager,
      DataProtectionService dataProtectionService) {
    super(
        definitionService,
        transitionService,
        ascriptionRepository,
        archetypeRepository,
        entityManager,
        dataProtectionService);
    this.receptorRepo = receptorRepo;
    this.mechanismService = mechanismService;
    this.archetypeService = archetypeService;
  }

  @Override
  public DefinitionSubjectType getSubjectType() {
    return DefinitionSubjectType.RECEPTOR;
  }

  @Override
  protected AbstractAscriptionRepository<ReceptorEntity> getRepository() {
    return receptorRepo;
  }

  @Override
  public ReceptorEntity buildEntity(
      DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
    UUID mechanismId = extractRequiredUuid(statement, "mechanism");
    MechanismEntity mechanism = mechanismService.findEntityById(mechanismId);

    UUID dataArchetypeId = extractRequiredUuid(statement, "archetype");
    ArchetypeEntity dataArchetype = archetypeService.findEntityById(dataArchetypeId);

    return new ReceptorEntity(definition, archetypeRef, statement, mechanism, dataArchetype);
  }

  /**
   * Finds a Receptor entity by its ascription id.
   *
   * @param id the ascription UUID
   * @return the receptor entity
   * @throws ResourceNotFoundException if no receptor exists with the given id
   */
  public ReceptorEntity findEntityById(UUID id) {
    return receptorRepo
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(PrimitiveType.RECEPTOR, id));
  }

  // ---- Lifecycle descriptors ----

  @Override
  public List<RefereeReference> getRefereeReferences(AscriptionEntity entity) {
    var r = (ReceptorEntity) entity;
    return List.of(new RefereeReference(r.getInputArchetype(), "archetype"));
  }

  @Override
  public Map<DefinitionSubjectType, AscriptionStatusTransitionCascadeType> getCascadeTargetRoles() {
    return Map.of(
        DefinitionSubjectType.MECHANISM, AscriptionStatusTransitionCascadeType.CONSTITUTIVE);
  }

  @Override
  public List<? extends AscriptionEntity> findCascadeTargetsFrom(
      DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
    if (sourceType == DefinitionSubjectType.MECHANISM) {
      return receptorRepo.findAllByMechanismId(sourceAscriptionId);
    }
    return List.of();
  }

  @Override
  public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
    var r = (ReceptorEntity) entity;
    return Map.of(
        "mechanism", r.getMechanism().getDefinition().getId(),
        "archetype", r.getInputArchetype().getDefinition().getId());
  }
}
