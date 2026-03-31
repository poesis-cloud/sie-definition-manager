package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.repository.StructureRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import cloud.poesis.sie.defman.type.RuleType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
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
public class StructureService extends AbstractAscriptionService<StructureEntity> {

  private final StructureRepository structureRepo;

  /**
   * Constructs the Structure service with its required dependencies.
   *
   * @param structureRepo the structure repository
   * @param definitionService the definition service
   * @param transitionService the status transition service
   * @param ascriptionService the ascription service for cross-subtype queries
   * @param entityManager the JPA entity manager
   * @param dataProtectionService the data protection service
   */
  public StructureService(
      StructureRepository structureRepo,
      ArchetypeRepository archetypeRepository,
      DefinitionService definitionService,
      AscriptionStatusTransitionService transitionService,
      AscriptionService ascriptionService,
      EntityManager entityManager,
      DataProtectionService dataProtectionService) {
    super(
        definitionService,
        transitionService,
        ascriptionService,
        archetypeRepository,
        entityManager,
        dataProtectionService);
    this.structureRepo = structureRepo;
  }

  @Override
  public DefinitionSubjectType getSubjectType() {
    return DefinitionSubjectType.STRUCTURE;
  }

  @Override
  protected AbstractAscriptionRepository<StructureEntity> getRepository() {
    return structureRepo;
  }

  @Override
  public StructureEntity buildEntity(
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
  public void validateActivationUniqueness(AscriptionEntity entity) {
    var stmt = entity.getStatement();
    String purpose = stmt.has("purpose") ? stmt.get("purpose").asText() : null;
    if (purpose == null || purpose.isBlank()) {
      throw RuleViolationException.of(
          RuleType.STRUCTURE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          "Structure purpose must not be empty",
          "field",
          "purpose");
    }
    UUID thisDefId = entity.getDefinition().getId();
    List<StructureEntity> inEffect =
        structureRepo.findAllByStatusIn(
            List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED));
    for (StructureEntity s : inEffect) {
      if (s.getDefinition().getId().equals(thisDefId)) continue;
      String sPurpose =
          s.getStatement().has("purpose") ? s.getStatement().get("purpose").asText() : null;
      if (purpose.equals(sPurpose)) {
        throw RuleViolationException.of(
            RuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
            "Structure purpose '" + purpose + "' already in effect",
            "field",
            "purpose",
            "value",
            purpose,
            "conflictingAscriptionId",
            s.getId(),
            "conflictingDefinitionId",
            s.getDefinition().getId());
      }
    }
  }
}
