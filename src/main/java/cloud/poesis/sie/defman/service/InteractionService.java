package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.EffectorEntity;
import cloud.poesis.sie.defman.entity.InteractionEntity;
import cloud.poesis.sie.defman.entity.ReceptorEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.repository.InteractionRepository;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * GSM Interaction ascription service.
 *
 * <p>Manages lifecycle and persistence of {@link InteractionEntity} ascriptions including
 * effector/receptor archetype compatibility validation and dependent cascade from referenced
 * Effector and Receptor.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class InteractionService implements SubtypeHandler<InteractionEntity> {

  private final InteractionRepository interactionRepo;
  private final EffectorService effectorService;
  private final ReceptorService receptorService;

  public InteractionService(
      InteractionRepository interactionRepo,
      EffectorService effectorService,
      ReceptorService receptorService) {
    this.interactionRepo = interactionRepo;
    this.effectorService = effectorService;
    this.receptorService = receptorService;
  }

  @Override
  public DefinitionSubjectType getSubjectType() {
    return DefinitionSubjectType.INTERACTION;
  }

  @Override
  public AbstractAscriptionRepository<InteractionEntity> getRepository() {
    return interactionRepo;
  }

  @Override
  public InteractionEntity buildEntity(
      DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
    UUID effectorId = UUID.fromString(statement.get("effector").asText());
    EffectorEntity effector = effectorService.findEntityById(effectorId);

    UUID receptorId = UUID.fromString(statement.get("receptor").asText());
    ReceptorEntity receptor = receptorService.findEntityById(receptorId);

    // GSM Interaction validation: effector.archetype must be schema-compatible with
    // receptor.archetype
    UUID effArchDefId = effector.getOutputArchetype().getDefinition().getId();
    UUID recArchDefId = receptor.getInputArchetype().getDefinition().getId();
    if (!effArchDefId.equals(recArchDefId)) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.INTERACTION_EFFECTOR_RECEPTOR_COMPATIBILITY,
          "Interaction archetype mismatch: effector output archetype (definition "
              + effArchDefId
              + ") is not compatible with receptor input archetype (definition "
              + recArchDefId
              + ")",
          "effectorArchetypeDefinitionId",
          effArchDefId,
          "receptorArchetypeDefinitionId",
          recArchDefId);
    }

    return new InteractionEntity(definition, archetypeRef, statement, effector, receptor);
  }

  // ---- Lifecycle descriptors ----

  @Override
  public List<Map.Entry<AscriptionEntity, String>> getRefereeReferences(AscriptionEntity entity) {
    var i = (InteractionEntity) entity;
    return List.of(Map.entry(i.getEffector(), "effector"), Map.entry(i.getReceptor(), "receptor"));
  }

  @Override
  public Map<DefinitionSubjectType, AscriptionStatusTransitionCascadeType> getCascadeTargetRoles() {
    return Map.of(
        DefinitionSubjectType.EFFECTOR, AscriptionStatusTransitionCascadeType.DEPENDENT,
        DefinitionSubjectType.RECEPTOR, AscriptionStatusTransitionCascadeType.DEPENDENT);
  }

  @Override
  public List<? extends AscriptionEntity> findCascadeTargetsFrom(
      DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
    return switch (sourceType) {
      case EFFECTOR -> interactionRepo.findAllByEffectorId(sourceAscriptionId);
      case RECEPTOR -> interactionRepo.findAllByReceptorId(sourceAscriptionId);
      default -> List.of();
    };
  }

  @Override
  public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
    var i = (InteractionEntity) entity;
    return Map.of(
        "effector", i.getEffector().getDefinition().getId(),
        "receptor", i.getReceptor().getDefinition().getId());
  }
}
