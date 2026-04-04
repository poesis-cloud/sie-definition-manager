package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.NormEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.repository.NormRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * GSM Norm ascription service.
 *
 * <p>Manages lifecycle and persistence of {@link NormEntity} ascriptions including CEL
 * applicability/assertion profile validation (applicability and assertion profiles) and governing
 * cascade from owning Structure.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class NormService extends AbstractAscriptionService<NormEntity> {

  private static final Logger LOG = LoggerFactory.getLogger(NormService.class);

  private final NormRepository normRepo;
  private final StructureService structureService;
  private final ArchetypeService archetypeService;
  private final NormApplicabilityValidationService applicabilityValidation;
  private final NormAssertionValidationService assertionValidation;

  /**
   * Constructs the Norm service with its required dependencies.
   *
   * @param normRepo the norm repository
   * @param structureService the structure service for reference resolution
   * @param archetypeService the archetype service for qualifier resolution
   * @param definitionService the definition service
   * @param stateMachine the ascription state machine
   * @param ascriptionStatementValidationService the ascription statement validation service
   * @param entityManager the JPA entity manager
   * @param applicabilityValidation the Norm applicability CEL validation service
   * @param assertionValidation the Norm assertion CEL validation service
   */
  public NormService(
      NormRepository normRepo,
      StructureService structureService,
      ArchetypeService archetypeService,
      DefinitionService definitionService,
      AscriptionStateMachineService stateMachine,
      AscriptionStatementValidationService ascriptionStatementValidationService,
      EntityManager entityManager,
      NormApplicabilityValidationService applicabilityValidation,
      NormAssertionValidationService assertionValidation) {
    super(definitionService, stateMachine, ascriptionStatementValidationService, entityManager);
    this.normRepo = normRepo;
    this.structureService = structureService;
    this.archetypeService = archetypeService;
    this.applicabilityValidation = applicabilityValidation;
    this.assertionValidation = assertionValidation;
  }

  @Override
  public DefinitionSubjectType getSubjectType() {
    return DefinitionSubjectType.NORM;
  }

  @Override
  protected AbstractAscriptionRepository<NormEntity> getRepository() {
    return normRepo;
  }

  @Override
  public NormEntity buildEntity(
      DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
    // GSM: validate CEL profiles before building entity
    if (statement.has("applicability")) {
      applicabilityValidation.validateApplicability(statement.get("applicability").asText());
    }
    if (statement.has("assertion")) {
      assertionValidation.validateAssertion(statement.get("assertion").asText());
    }

    UUID structureId = UUID.fromString(statement.get("structure").asText());
    StructureEntity structure = structureService.findEntityById(structureId);

    UUID qualifierId = UUID.fromString(statement.get("qualifier").asText());
    ArchetypeEntity qualifier = archetypeService.findEntityById(qualifierId);

    // Semantic validations (after references are resolved)
    if (statement.has("applicability")) {
      String applicability = statement.get("applicability").asText();
      if (applicability != null
          && !applicability.isBlank()
          && !"true".equals(applicability.trim())) {
        applicabilityValidation.validateApplicabilityReferences(applicability);
      }
    }
    if (statement.has("assertion")) {
      assertionValidation.validateAssertionPropertyPaths(
          statement.get("assertion").asText(), qualifier);
    }

    return new NormEntity(definition, archetypeRef, statement, structure, qualifier);
  }

  // ---- Lifecycle descriptors ----

  @Override
  public List<Map.Entry<AscriptionEntity, String>> getRefereeReferences(AscriptionEntity entity) {
    if (!(entity instanceof NormEntity n)) {
      throw new IllegalArgumentException(
          "Expected NormEntity, got " + entity.getClass().getSimpleName());
    }
    return List.of(
        Map.entry(n.getStructure(), "structure"), Map.entry(n.getQualifier(), "qualifier"));
  }

  @Override
  public Map<DefinitionSubjectType, AscriptionStatusTransitionCascadeType> getCascadeTargetRoles() {
    return Map.of(DefinitionSubjectType.STRUCTURE, AscriptionStatusTransitionCascadeType.GOVERNING);
  }

  @Override
  public List<? extends AscriptionEntity> findCascadeTargetsFrom(
      DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
    if (sourceType == DefinitionSubjectType.STRUCTURE) {
      return normRepo.findAllByStructureId(sourceAscriptionId);
    }
    return List.of();
  }

  @Override
  public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
    if (!(entity instanceof NormEntity n)) {
      throw new IllegalArgumentException(
          "Expected NormEntity, got " + entity.getClass().getSimpleName());
    }
    var values = new LinkedHashMap<String, Object>();
    values.put("structure", n.getStructure().getDefinition().getId());
    values.put("qualifier", n.getQualifier().getDefinition().getId());
    var stmt = n.getStatement();
    if (stmt.has("assertion")) {
      values.put("assertion", stmt.get("assertion").asText());
    }
    return values;
  }

  /**
   * Returns norms sharing the same structure definition, filtered by statuses.
   *
   * @param structureDefinitionId the structure definition UUID
   * @param statuses the lifecycle statuses to match
   * @return the matching norm entities
   */
  public List<NormEntity> findAllByStructureDefinitionIdAndStatusIn(
      UUID structureDefinitionId, Collection<AscriptionStatusType> statuses) {
    return normRepo.findAllByStructureDefinitionIdAndStatusIn(structureDefinitionId, statuses);
  }
}
