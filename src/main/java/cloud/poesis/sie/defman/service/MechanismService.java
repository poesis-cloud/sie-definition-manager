package cloud.poesis.sie.defman.service;

import static cloud.poesis.sie.defman.service.AscriptionParsingService.extractRequiredUuid;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.repository.MechanismRepository;
import cloud.poesis.sie.defman.service.MechanismPortDerivationService.PortDerivation;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * GSM Mechanism ascription service.
 *
 * <p>Manages lifecycle and persistence of {@link MechanismEntity} ascriptions including Starlark
 * rule structural validation, auto-derivation of Effectors and Receptors from the rule AST, and
 * governing cascade from owning Structure.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class MechanismService implements AscriptionSubtypeService<MechanismEntity> {

  private static final Logger LOG = LoggerFactory.getLogger(MechanismService.class);

  private final MechanismRepository mechanismRepo;
  private final StructureService structureService;
  private final MechanismRuleValidationService ruleValidation;
  private final MechanismPortDerivationService portDerivation;
  private final AscriptionService ascriptionService;

  public MechanismService(
      MechanismRepository mechanismRepo,
      StructureService structureService,
      MechanismRuleValidationService ruleValidation,
      MechanismPortDerivationService portDerivation,
      @Lazy AscriptionService ascriptionService) {
    this.mechanismRepo = mechanismRepo;
    this.structureService = structureService;
    this.ruleValidation = ruleValidation;
    this.portDerivation = portDerivation;
    this.ascriptionService = ascriptionService;
  }

  @Override
  public DefinitionSubjectType getSubjectType() {
    return DefinitionSubjectType.MECHANISM;
  }

  @Override
  public AbstractAscriptionRepository<MechanismEntity> getRepository() {
    return mechanismRepo;
  }

  @Override
  public MechanismEntity create(
      DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
    UUID structureId =
        extractRequiredUuid(
            statement,
            "structure",
            AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE);
    StructureEntity structure = structureService.findEntityById(structureId);

    MechanismEntity entity = new MechanismEntity(definition, archetypeRef, statement, structure);

    // GSM: Starlark rule structural validation
    ruleValidation.validateStarlarkRule(statement.get("rule").asText());

    return entity;
  }

  /**
   * Finds a Mechanism entity by its ascription id.
   *
   * @param id the ascription UUID
   * @return the mechanism entity
   * @throws ResourceNotFoundException if no mechanism exists with the given id
   */
  public MechanismEntity findEntityById(UUID id) {
    return mechanismRepo
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(PrimitiveType.MECHANISM, id));
  }

  // ---- Lifecycle descriptors ----

  @Override
  public List<Map.Entry<AscriptionEntity, String>> getRefereeReferences(AscriptionEntity entity) {
    if (!(entity instanceof MechanismEntity m)) {
      throw new IllegalArgumentException(
          "Expected MechanismEntity, got " + entity.getClass().getSimpleName());
    }
    return List.of(Map.entry(m.getStructure(), "structure"));
  }

  @Override
  public Map<DefinitionSubjectType, AscriptionStatusTransitionCascadeType> getCascadeTargetRoles() {
    return Map.of(DefinitionSubjectType.STRUCTURE, AscriptionStatusTransitionCascadeType.GOVERNING);
  }

  @Override
  public List<? extends AscriptionEntity> findCascadeTargetsFrom(
      DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
    if (sourceType == DefinitionSubjectType.STRUCTURE) {
      return mechanismRepo.findAllByStructureId(sourceAscriptionId);
    }
    return List.of();
  }

  @Override
  public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
    if (!(entity instanceof MechanismEntity m)) {
      throw new IllegalArgumentException(
          "Expected MechanismEntity, got " + entity.getClass().getSimpleName());
    }
    return Map.of(
        "structure", m.getStructure().getDefinition().getId(),
        "function", m.getStatement().get("function").asText());
  }

  @Override
  public void validateActivationUniqueness(AscriptionEntity entity) {
    if (!(entity instanceof MechanismEntity m)) {
      throw new IllegalArgumentException(
          "Expected MechanismEntity, got " + entity.getClass().getSimpleName());
    }
    // Statement is immutable and was validated at creation — function is guaranteed
    // non-null/non-blank.
    String function = m.getStatement().get("function").asText();
    UUID structureDefId = m.getStructure().getDefinition().getId();
    AscriptionUniquenessValidationService.validatePropertyAcrossDefinitions(
        getSubjectType(),
        "function",
        function,
        m.getDefinition().getId(),
        mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
            structureDefId,
            EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)));
  }

  // ======================================================================
  // Port auto-derivation (U3/U4 + U12) — derive specs, then create via
  // AscriptionService
  // ======================================================================

  @Override
  public void afterCreate(AscriptionEntity saved) {
    if (!(saved instanceof MechanismEntity mechanism)) {
      throw new IllegalArgumentException(
          "Expected MechanismEntity, got " + saved.getClass().getSimpleName());
    }
    List<PortDerivation> specs = portDerivation.derivePortSpecs(mechanism);
    for (PortDerivation spec : specs) {
      ascriptionService.create(spec.archetypeId(), spec.statement(), null);
    }
  }
}
