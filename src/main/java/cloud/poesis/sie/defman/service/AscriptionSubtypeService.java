package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * Contract for GSM subject type handlers. Each of the 8 GSM subject types (Structure, Mechanism,
 * Effector, Receptor, Interaction, Archetype, Directive, Norm) implements this interface. The
 * {@link AscriptionService} facade delegates to the appropriate handler for type-specific logic.
 *
 * @param <T> the concrete ascription entity type
 * @author Clément Cazaud
 * @since 1.0.0
 */
public interface AscriptionSubtypeService<T extends AscriptionEntity> {

  /**
   * Returns the GSM subject type handled by this handler.
   *
   * @return the definition subject type
   */
  DefinitionSubjectType getSubjectType();

  /**
   * Returns the subtype-specific repository.
   *
   * @return the typed repository
   */
  AbstractAscriptionRepository<T> getRepository();

  /**
   * Creates a subtype-specific entity from the given definition, archetype, and statement.
   *
   * @param definition the stable identity
   * @param archetypeRef the typing archetype
   * @param statement the JSON statement payload
   * @return the constructed entity (not yet persisted)
   */
  T create(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement);

  /**
   * Returns identity-bound field values for the given entity.
   *
   * @param entity the ascription entity
   * @return map of field name to value (empty if this subtype has no identity-bound fields)
   */
  Map<String, Object> getIdentityBoundValues(AscriptionEntity entity);

  /**
   * Returns referee references for lifecycle precondition checks.
   *
   * @param entity the ascription entity
   * @return list of referee references (empty if this subtype has no referees)
   */
  List<Map.Entry<AscriptionEntity, String>> getRefereeReferences(AscriptionEntity entity);

  /**
   * Returns cascade target roles declared by this handler.
   *
   * @return map of source subject type to cascade type (empty if none)
   */
  Map<DefinitionSubjectType, AscriptionStatusTransitionCascadeType> getCascadeTargetRoles();

  /**
   * Finds cascade target entities originating from a source ascription.
   *
   * @param sourceType the source subject type
   * @param sourceAscriptionId the source ascription UUID
   * @return list of target entities (empty if none)
   */
  List<? extends AscriptionEntity> findCascadeTargetsFrom(
      DefinitionSubjectType sourceType, UUID sourceAscriptionId);

  /**
   * Returns the GSM rule type for statement validation violations.
   *
   * @return the statement validation rule type
   */
  default AscriptionConsistencyRuleType statementValidationRule() {
    return AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
  }

  /**
   * Validates activation uniqueness constraints (e.g., Structure purpose, Mechanism function).
   *
   * @param entity the ascription entity being activated
   */
  default void validateActivationUniqueness(AscriptionEntity entity) {}

  /**
   * Hook called when an entity transitions to ACTIVE.
   *
   * @param entity the ascription entity being activated
   */
  default void onActivation(AscriptionEntity entity) {}

  /**
   * Hook called when an entity leaves in-effect status (ACTIVE/DEPRECATED).
   *
   * @param entity the ascription entity being deactivated
   */
  default void onDeactivation(AscriptionEntity entity) {}

  /**
   * Hook called after an entity is created and persisted.
   *
   * @param saved the persisted ascription entity
   */
  default void afterCreate(AscriptionEntity saved) {}

  // ======================================================================
  // Repository mutation convenience defaults
  // ======================================================================

  /**
   * Persists the given entity via the subtype-specific repository.
   *
   * @param entity the entity to save
   * @return the saved entity
   */
  default T save(T entity) {
    return getRepository().save(entity);
  }

  // ======================================================================
  // Repository query convenience defaults
  // ======================================================================

  /**
   * Returns a page of all ascriptions for this subtype.
   *
   * @param pageable pagination parameters
   * @return page of ascription entities
   */
  default Page<T> findAll(Pageable pageable) {
    return getRepository().findAll(pageable);
  }

  /**
   * Returns a page of ascriptions for this subtype filtered by status.
   *
   * @param status the lifecycle status filter
   * @param pageable pagination parameters
   * @return page of matching ascription entities
   */
  default Page<T> findAllByStatus(AscriptionStatusType status, Pageable pageable) {
    return getRepository().findAllByStatus(status, pageable);
  }

  /**
   * Returns a page of ascriptions for this subtype matching a specification.
   *
   * @param spec the JPA specification
   * @param pageable pagination parameters
   * @return page of matching ascription entities
   */
  default Page<T> findAll(Specification<T> spec, Pageable pageable) {
    return getRepository().findAll(spec, pageable);
  }

  /**
   * Returns all ascriptions for a given definition, ordered by timestamp descending.
   *
   * @param definitionId the definition UUID
   * @return ordered list of ascription entities
   */
  default List<T> findAllByDefinitionId(UUID definitionId) {
    return getRepository().findAllByDefinitionIdOrderByTimestampDesc(definitionId);
  }

  /**
   * Returns all ascriptions for a given definition filtered by statuses.
   *
   * @param definitionId the definition UUID
   * @param statuses the status filter
   * @return list of matching ascription entities
   */
  default List<T> findAllByDefinitionIdAndStatus(
      UUID definitionId, Collection<AscriptionStatusType> statuses) {
    return getRepository().findAllByDefinitionIdAndStatusIn(definitionId, statuses);
  }
}
