package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

/**
 * Base class for all GSM ascription services. Provides:
 *
 * <ul>
 *   <li>Subtype identity ({@link #getSubjectType()})
 *   <li>Entity CRUD ({@link #buildEntity}, {@link #save}, find methods)
 *   <li>Create template method ({@link #create})
 *   <li>Lifecycle descriptors (referee references, cascade roles, identity-bound values)
 *   <li>Lifecycle hooks ({@link #onActivation}, {@link #onDeactivation})
 *   <li>Statement JSON extraction utilities ({@link #extractRequiredUuid}, {@link
 *       #extractUuidList})
 * </ul>
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public abstract class AbstractAscriptionService<T extends AscriptionEntity> {

  private static final Logger LOG = LoggerFactory.getLogger(AbstractAscriptionService.class);

  // Shared dependencies — constructor-injected into all subclasses
  private final DefinitionService definitionService;
  private final AscriptionStateMachineService stateMachine;
  private final AscriptionStatementValidationService statementValidation;
  private final EntityManager entityManager;

  /**
   * Constructs the abstract ascription service with shared dependencies.
   *
   * @param definitionService the definition service for identity resolution
   * @param stateMachine the ascription state machine for transition recording and validation
   * @param statementValidation the statement validation service for schema and annotation checks
   * @param entityManager the JPA entity manager
   */
  protected AbstractAscriptionService(
      DefinitionService definitionService,
      AscriptionStateMachineService stateMachine,
      AscriptionStatementValidationService statementValidation,
      EntityManager entityManager) {
    this.definitionService = definitionService;
    this.stateMachine = stateMachine;
    this.statementValidation = statementValidation;
    this.entityManager = entityManager;
  }

  // ======================================================================
  // Subtype identity
  // ======================================================================

  /**
   * Returns the GSM subject type handled by this service.
   *
   * @return the definition subject type
   */
  public abstract DefinitionSubjectType getSubjectType();

  /**
   * Returns the GSM rule type for statement validation violations.
   *
   * @return the statement validation rule type
   */
  protected AscriptionConsistencyRuleType statementValidationRule() {
    return AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
  }

  // ======================================================================
  // Entity CRUD
  // ======================================================================

  /**
   * Returns the subtype-specific repository.
   *
   * @return the typed repository
   */
  protected abstract AbstractAscriptionRepository<T> getRepository();

  /**
   * Builds a subtype-specific entity from the given definition, archetype, and statement.
   *
   * @param definition the stable identity
   * @param archetypeRef the typing archetype
   * @param statement the JSON statement payload
   * @return the constructed entity (not yet persisted)
   */
  public abstract T buildEntity(
      DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement);

  /**
   * Persists a subtype-specific entity.
   *
   * @param entity the entity to persist
   * @return the persisted entity
   */
  public T save(T entity) {
    return getRepository().save(entity);
  }

  /**
   * Returns a page of all ascriptions for this subject type.
   *
   * @param pageable pagination parameters
   * @return a page of ascription entities
   */
  public Page<T> findAll(Pageable pageable) {
    return getRepository().findAll(pageable);
  }

  /**
   * Returns a page of ascriptions filtered by status.
   *
   * @param status the status filter
   * @param pageable pagination parameters
   * @return a page of matching ascription entities
   */
  public Page<T> findAllByStatus(AscriptionStatusType status, Pageable pageable) {
    return getRepository().findAllByStatus(status, pageable);
  }

  /**
   * Returns all ascriptions for a given definition.
   *
   * @param definitionId the definition UUID
   * @return ordered list of ascription entities
   */
  public List<T> findAllByDefinitionId(UUID definitionId) {
    return getRepository().findAllByDefinitionIdOrderByTimestampDesc(definitionId);
  }

  /**
   * Returns all ascriptions for a given definition filtered by statuses.
   *
   * @param definitionId the definition UUID
   * @param statuses the status filter
   * @return list of matching ascription entities
   */
  public List<T> findAllByDefinitionIdAndStatus(
      UUID definitionId, Collection<AscriptionStatusType> statuses) {
    return getRepository().findAllByDefinitionIdAndStatusIn(definitionId, statuses);
  }

  /**
   * Returns a page of ascriptions filtered by archetype, statement properties, and optionally by
   * status. Used for {@code $gsm:queryable} statement filtering.
   *
   * @param archetypeDefinitionId the archetype's Definition UUID
   * @param statementFilters map of property name → value (strict equality)
   * @param status optional status filter (may be {@code null})
   * @param pageable pagination parameters
   * @return a page of matching ascription entities
   */
  public Page<T> findAllFiltered(
      UUID archetypeDefinitionId,
      Map<String, String> statementFilters,
      AscriptionStatusType status,
      Pageable pageable) {
    Specification<T> spec = buildFilterSpec(archetypeDefinitionId, statementFilters, status);
    return getRepository().findAll(spec, pageable);
  }

  /**
   * Builds a JPA {@link Specification} for filtered ascription queries. Combines archetype
   * definition filtering (via JPA join), optional status filtering, and JSONB property equality
   * matching via PostgreSQL {@code jsonb_extract_path_text}.
   *
   * @param <T> the entity type
   * @param archetypeDefinitionId the archetype's Definition UUID (required)
   * @param statementFilters map of property name → value (strict equality)
   * @param status optional status filter (may be {@code null})
   * @return a composed JPA Specification
   */
  protected static <T extends AscriptionEntity> Specification<T> buildFilterSpec(
      UUID archetypeDefinitionId,
      Map<String, String> statementFilters,
      AscriptionStatusType status) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      predicates.add(
          cb.equal(root.get("archetype").get("definition").get("id"), archetypeDefinitionId));
      if (status != null) {
        predicates.add(cb.equal(root.get("status"), status));
      }
      for (var entry : statementFilters.entrySet()) {
        predicates.add(
            cb.equal(
                cb.function(
                    "jsonb_extract_path_text",
                    String.class,
                    root.get("statement"),
                    cb.literal(entry.getKey())),
                entry.getValue()));
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }

  /**
   * Returns Ascription history for a Definition, with definition and archetype eagerly fetched via
   * {@code @EntityGraph} on the repository method.
   *
   * @param definitionId the definition UUID
   * @return ordered list of ascription entities
   */
  @Transactional(value = "transactionManager", readOnly = true)
  public List<T> getHistory(UUID definitionId) {
    return findAllByDefinitionId(definitionId);
  }

  // ======================================================================
  // CREATE (template method — do not override)
  // ======================================================================

  /**
   * Creates a new ascription through the GSM template method.
   *
   * <p>Validates the statement against the archetype schema, resolves or creates the definition,
   * enforces {@code $gsm:*} annotations, validates identity-bound invariants and referee
   * preconditions, persists, and records the initial DRAFT transition.
   *
   * @param archetypeRef the typing archetype
   * @param statement the JSON statement payload
   * @param definitionId optional definition UUID (may be {@code null} for new definitions)
   * @return the persisted ascription entity in DRAFT status
   * @throws RuleViolationException if validation fails
   */
  @Transactional("transactionManager")
  public T create(ArchetypeEntity archetypeRef, JsonNode statement, UUID definitionId) {
    // 1. Validate statement against archetype schema
    statementValidation.validateStatement(statement, archetypeRef, getSubjectType());

    // 2. Resolve or create Definition
    DefinitionEntity definition = definitionService.resolveOrCreate(definitionId, getSubjectType());

    // 3. Enforce $gsm:* annotations on statement
    statementValidation.enforceAnnotations(
        statement, archetypeRef, definition.getId(), this::findAllByDefinitionId);

    // 4. Build subtype-specific entity
    T entity = buildEntity(definition, archetypeRef, statement);

    // 5. Validate identity-bound invariant
    validateIdentityBound(entity);

    // 6. Validate creation referee preconditions
    validateCreationPreconditions(entity);

    // 7. Persist
    T saved;
    try {
      saved = save(entity);

      // 8. Flush pending INSERT so refresh() can re-read the row.
      // Previously, the now-removed recordTransition() call between save() and
      // refresh()
      // triggered an implicit flush; without it, refresh() would fail with
      // EntityNotFoundException because the INSERT was still pending in the session.
      entityManager.flush();

      // 9. Refresh to pick up DB-trigger-assigned fields (status, timestamp)
      entityManager.refresh(saved);
    } catch (DataIntegrityViolationException ex) {
      throw PersistenceExceptionTranslationService.translate(ex);
    }

    // 10. Post-creation hook (e.g., auto-derivation of ports)
    afterCreate(saved);

    return saved;
  }

  // ======================================================================
  // Lifecycle descriptor methods
  // ======================================================================

  /**
   * Returns referee references for lifecycle precondition checks.
   *
   * @param entity the ascription entity
   * @return list of referee references (empty if this subtype has no referees)
   */
  public abstract List<Map.Entry<AscriptionEntity, String>> getRefereeReferences(
      AscriptionEntity entity);

  /**
   * Returns cascade target roles declared by this service.
   *
   * @return map of source subject type to cascade type (empty if this subtype declares no cascades)
   */
  public abstract Map<DefinitionSubjectType, AscriptionStatusTransitionCascadeType>
      getCascadeTargetRoles();

  /**
   * Finds cascade target entities originating from a source ascription.
   *
   * @param sourceType the source subject type
   * @param sourceAscriptionId the source ascription UUID
   * @return list of target entities (empty if this subtype has no cascade targets)
   */
  public abstract List<? extends AscriptionEntity> findCascadeTargetsFrom(
      DefinitionSubjectType sourceType, UUID sourceAscriptionId);

  /**
   * Returns identity-bound field values for the given entity.
   *
   * @param entity the ascription entity
   * @return map of field name to value (empty if this subtype has no identity-bound fields)
   */
  public abstract Map<String, Object> getIdentityBoundValues(AscriptionEntity entity);

  /**
   * Validates activation uniqueness constraints (e.g., Structure purpose, Mechanism function).
   *
   * @param entity the ascription entity being activated
   * @throws RuleViolationException if a uniqueness constraint is violated
   */
  public void validateActivationUniqueness(AscriptionEntity entity) {
    // Default: no activation uniqueness checks
  }

  // ======================================================================
  // Lifecycle hooks (called by AscriptionStatusTransitionService)
  // ======================================================================

  /**
   * Hook called when an entity transitions to ACTIVE. Override in concrete services for
   * subtype-specific activation logic (e.g., index provisioning for Archetypes).
   *
   * @param entity the ascription entity being activated
   */
  public void onActivation(AscriptionEntity entity) {
    // Default: no-op
  }

  /**
   * Hook called when an entity leaves in-effect status (ACTIVE/DEPRECATED). Override in concrete
   * services for subtype-specific deactivation logic (e.g., index deprovisioning for Archetypes).
   *
   * @param entity the ascription entity being deactivated
   */
  public void onDeactivation(AscriptionEntity entity) {
    // Default: no-op
  }

  /**
   * Hook called after an entity is created and persisted. Override in concrete services for
   * subtype-specific post-creation logic (e.g., auto-derivation of Effectors/Receptors for
   * generative Mechanisms).
   *
   * @param saved the persisted ascription entity
   */
  protected void afterCreate(AscriptionEntity saved) {
    // Default: no-op
  }

  /**
   * Returns the definition service (for subtype post-creation logic).
   *
   * @return the definition service
   */
  protected DefinitionService getDefinitionService() {
    return definitionService;
  }

  /**
   * Returns the ascription state machine (for subtype post-creation logic).
   *
   * @return the state machine service
   */
  protected AscriptionStateMachineService getStateMachine() {
    return stateMachine;
  }

  /**
   * Returns the JPA entity manager (for subtype post-creation logic).
   *
   * @return the entity manager
   */
  protected EntityManager getEntityManager() {
    return entityManager;
  }

  // ======================================================================
  // Statement JSON extraction utilities (protected)
  // ======================================================================

  /**
   * Extracts a required UUID from a statement JSON field.
   *
   * @param statement the JSON statement
   * @param field the field name
   * @return the extracted UUID
   * @throws RuleViolationException if the field is missing or not a valid UUID
   */
  protected UUID extractRequiredUuid(JsonNode statement, String field) {
    JsonNode node = statement.get(field);
    if (node == null || node.isNull() || node.asText().isBlank()) {
      throw RuleViolationException.of(
          statementValidationRule(),
          "Required field '" + field + "' missing in statement payload",
          "field",
          field);
    }
    try {
      return UUID.fromString(node.asText());
    } catch (IllegalArgumentException e) {
      throw RuleViolationException.of(
          statementValidationRule(),
          "Invalid UUID for field '" + field + "': " + node.asText(),
          "field",
          field,
          "value",
          node.asText());
    }
  }

  /**
   * Extracts a list of UUIDs from a statement JSON array field.
   *
   * @param statement the JSON statement
   * @param field the field name
   * @return the extracted UUID list (empty if field is absent)
   * @throws RuleViolationException if any element is not a valid UUID
   */
  protected List<UUID> extractUuidList(JsonNode statement, String field) {
    JsonNode node = statement.get(field);
    if (node == null || node.isNull() || !node.isArray()) {
      return List.of();
    }
    List<UUID> result = new ArrayList<>(node.size());
    for (JsonNode element : node) {
      try {
        result.add(UUID.fromString(element.asText()));
      } catch (IllegalArgumentException e) {
        throw RuleViolationException.of(
            statementValidationRule(),
            "Invalid UUID in '" + field + "': " + element.asText(),
            "field",
            field,
            "value",
            element.asText());
      }
    }
    return result;
  }

  // ======================================================================
  // Identity-bound validation (private — called from create())
  // ======================================================================

  void validateIdentityBound(AscriptionEntity entity) {
    Map<String, Object> newValues = getIdentityBoundValues(entity);
    if (newValues.isEmpty()) {
      return;
    }

    UUID definitionId = entity.getDefinition().getId();
    List<? extends AscriptionEntity> existing = findAllByDefinitionId(definitionId);
    if (existing.isEmpty()) {
      return;
    }

    // Compare against the first (earliest) ascription's identity-bound values
    AscriptionEntity first = existing.getLast(); // ordered by timestamp DESC, so last = earliest
    Map<String, Object> firstValues = getIdentityBoundValues(first);

    for (var entry : newValues.entrySet()) {
      String field = entry.getKey();
      Object newVal = entry.getValue();
      Object firstVal = firstValues.get(field);
      if (!Objects.equals(newVal, firstVal)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION,
            "Identity-bound field '"
                + field
                + "' changed: expected '"
                + firstVal
                + "' but got '"
                + newVal
                + "'",
            "field",
            field,
            "definitionId",
            definitionId,
            "expectedValue",
            String.valueOf(firstVal),
            "actualValue",
            String.valueOf(newVal));
      }
    }
  }

  // ======================================================================
  // Creation referee preconditions (delegates to
  // AscriptionStatusTransitionService)
  // ======================================================================

  void validateCreationPreconditions(AscriptionEntity entity) {
    stateMachine.validateRefereePreconditions(
        getRefereeReferences(entity), null, AscriptionStatusType.DRAFT);
  }

  // ======================================================================
  // Activation uniqueness validation (protected utility)
  // ======================================================================

  /**
   * Checks that a property value is unique across all in-effect ascriptions of the same type (from
   * different definitions). Used by concrete services in {@link #validateActivationUniqueness}.
   *
   * @param propertyName the statement property to check
   * @param propertyValue the property value to check for uniqueness
   * @param thisDefId the definition UUID of the entity being validated (excluded from check)
   * @param inEffectSiblings the in-effect siblings to check against
   * @throws RuleViolationException if a duplicate is found
   */
  protected void validatePropertyUniquenessAcrossDefinitions(
      String propertyName,
      String propertyValue,
      UUID thisDefId,
      Collection<? extends AscriptionEntity> inEffectSiblings) {
    for (AscriptionEntity sibling : inEffectSiblings) {
      if (sibling.getDefinition().getId().equals(thisDefId)) continue;
      JsonNode sibStmt = sibling.getStatement();
      String sibValue =
          (sibStmt != null && sibStmt.has(propertyName))
              ? sibStmt.get(propertyName).asText()
              : null;
      if (propertyValue.equals(sibValue)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
            getSubjectType().getPrimitiveType().getLabel()
                + " "
                + propertyName
                + " '"
                + propertyValue
                + "' already in effect",
            "field",
            propertyName,
            "value",
            propertyValue);
      }
    }
  }
}
