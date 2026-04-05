package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.repository.StructureRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * GSM Structure ascription service.
 *
 * <p>Manages lifecycle and persistence of {@link StructureEntity} ascriptions including purpose
 * uniqueness validation at activation.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class StructureService implements AscriptionSubtypeService<StructureEntity> {

  private final StructureRepository structureRepo;

  public StructureService(StructureRepository structureRepo) {
    this.structureRepo = structureRepo;
  }

  @Override
  public DefinitionSubjectType getSubjectType() {
    return DefinitionSubjectType.STRUCTURE;
  }

  @Override
  public AbstractAscriptionRepository<StructureEntity> getRepository() {
    return structureRepo;
  }

  @Override
  public StructureEntity create(
      DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
    return new StructureEntity(definition, archetypeRef, statement);
  }

  /**
   * Finds a Structure entity by its ascription id.
   *
   * @param id the ascription UUID
   * @return the structure entity
   * @throws ResourceNotFoundException if no structure exists with the given id
   */
  public StructureEntity findEntityById(UUID id) {
    return structureRepo
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(PrimitiveType.STRUCTURE, id));
  }

  @Override
  public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
    var stmt = entity.getStatement();
    var purpose = stmt.has("purpose") ? stmt.get("purpose").asText() : null;
    return purpose != null ? Map.of("purpose", purpose) : Map.of();
  }

  @Override
  public List<Map.Entry<AscriptionEntity, String>> getRefereeReferences(AscriptionEntity entity) {
    return List.of();
  }

  @Override
  public Map<DefinitionSubjectType, AscriptionStatusTransitionCascadeType> getCascadeTargetRoles() {
    return Map.of();
  }

  @Override
  public List<? extends AscriptionEntity> findCascadeTargetsFrom(
      DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
    return List.of();
  }

  @Override
  public void validateActivationUniqueness(AscriptionEntity entity) {
    // Statement is immutable and was validated at creation — purpose is guaranteed
    // non-null/non-blank.
    String purpose = entity.getStatement().get("purpose").asText();
    AscriptionUniquenessValidationService.validatePropertyAcrossDefinitions(
        getSubjectType(),
        "purpose",
        purpose,
        entity.getDefinition().getId(),
        structureRepo.findAllByStatusIn(
            EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)));
  }
}
