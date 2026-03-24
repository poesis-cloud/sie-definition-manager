package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.DefinitionRepository;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import cloud.poesis.sie.defman.type.RuleType;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for GSM Definition (stable identity) operations. Owns {@link DefinitionRepository}.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
@Transactional("transactionManager")
public class DefinitionService {

  private final DefinitionRepository definitionRepository;

  /**
   * Constructs the service with its required repository.
   *
   * @param definitionRepository the definition repository
   */
  public DefinitionService(DefinitionRepository definitionRepository) {
    this.definitionRepository = definitionRepository;
  }

  /**
   * Retrieves a definition by its unique identifier.
   *
   * @param id the definition UUID
   * @return the definition entity
   * @throws ResourceNotFoundException if no definition exists with the given id
   */
  @Transactional(value = "transactionManager", readOnly = true)
  public DefinitionEntity getById(@NonNull UUID id) {
    DefinitionEntity entity =
        definitionRepository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(PrimitiveType.DEFINITION, id));
    if (entity.getAscriptions().isEmpty()) {
      throw RuleViolationException.of(
          RuleType.DEFINITION_ASCRIPTIONS_ALWAYS_PRESENT,
          "Definition has no ascriptions",
          "definitionId",
          id.toString());
    }
    return entity;
  }

  /**
   * Retrieves a definition by ID with its ascriptions and their archetypes eagerly loaded (entity
   * graph, avoids N+1).
   *
   * @param id the definition UUID
   * @return the definition entity with ascriptions and archetypes initialized
   * @throws ResourceNotFoundException if no definition exists with the given id
   */
  @Transactional(value = "transactionManager", readOnly = true)
  public DefinitionEntity getByIdWithArchetypes(@NonNull UUID id) {
    DefinitionEntity entity =
        definitionRepository
            .findWithAscriptionArchetypesById(id)
            .orElseThrow(() -> new ResourceNotFoundException(PrimitiveType.DEFINITION, id));
    if (entity.getAscriptions().isEmpty()) {
      throw RuleViolationException.of(
          RuleType.DEFINITION_ASCRIPTIONS_ALWAYS_PRESENT,
          "Definition has no ascriptions",
          "definitionId",
          id.toString());
    }
    return entity;
  }

  /**
   * Batch-fetches Definitions by their IDs.
   *
   * <p>Part of the explicit-fetch design (see README.md § "Batch fetch pattern").
   *
   * @param ids collection of Definition IDs to retrieve
   * @return map of ID → DefinitionEntity; IDs not found in the database are silently absent from
   *     the returned map
   */
  @Transactional(value = "transactionManager", readOnly = true)
  public Map<UUID, DefinitionEntity> getByIds(Collection<UUID> ids) {
    return definitionRepository.findAllById(ids).stream()
        .collect(Collectors.toMap(DefinitionEntity::getId, Function.identity()));
  }

  /**
   * Creates a new definition for the given subject type.
   *
   * @param type the GSM structural role of the new definition
   * @return the persisted definition entity
   */
  public DefinitionEntity create(DefinitionSubjectType type) {
    return definitionRepository.save(new DefinitionEntity(type));
  }

  /**
   * Resolves an existing Definition by id, or creates a new one if definitionId is null.
   *
   * @param definitionId the definition UUID, or {@code null} to create a new one
   * @param type the GSM subject type (used only when creating)
   * @return the resolved or newly created definition entity
   * @throws ResourceNotFoundException if {@code definitionId} is non-null but not found
   */
  public DefinitionEntity resolveOrCreate(UUID definitionId, DefinitionSubjectType type) {
    if (definitionId != null) {
      return getById(definitionId);
    }
    return create(type);
  }
}
