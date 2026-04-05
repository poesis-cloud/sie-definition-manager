package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Facade for all GSM ascription operations. Absorbs the 10-step create template, generic CRUD, and
 * cross-subtype lookups previously split between {@code AbstractAscriptionService} and the old
 * {@code AscriptionService}.
 *
 * <p>Dispatches type-specific logic to {@link SubtypeHandler} implementations (one per GSM subject
 * type).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
@Transactional("transactionManager")
public class AscriptionService implements SmartInitializingSingleton {

  private static final Logger LOG = LoggerFactory.getLogger(AscriptionService.class);

  private final AscriptionRepository ascriptionRepository;
  private final ArchetypeService archetypeService;
  private final DefinitionService definitionService;
  private final AscriptionStateMachineService stateMachine;
  private final AscriptionStatementValidationService statementValidation;
  private final EntityManager entityManager;
  private final List<SubtypeHandler<?>> handlerList;

  private Map<DefinitionSubjectType, SubtypeHandler<?>> handlers;

  public AscriptionService(
      AscriptionRepository ascriptionRepository,
      ArchetypeService archetypeService,
      DefinitionService definitionService,
      AscriptionStateMachineService stateMachine,
      AscriptionStatementValidationService statementValidation,
      EntityManager entityManager,
      List<SubtypeHandler<?>> handlerList) {
    this.ascriptionRepository = ascriptionRepository;
    this.archetypeService = archetypeService;
    this.definitionService = definitionService;
    this.stateMachine = stateMachine;
    this.statementValidation = statementValidation;
    this.entityManager = entityManager;
    this.handlerList = List.copyOf(handlerList);
  }

  @Override
  public void afterSingletonsInstantiated() {
    Map<DefinitionSubjectType, SubtypeHandler<?>> map = new EnumMap<>(DefinitionSubjectType.class);
    for (SubtypeHandler<?> handler : handlerList) {
      if (map.put(handler.getSubjectType(), handler) != null) {
        throw new IllegalStateException("Duplicate handler for " + handler.getSubjectType());
      }
    }
    for (DefinitionSubjectType type : DefinitionSubjectType.values()) {
      if (!map.containsKey(type)) {
        throw new IllegalStateException("Missing handler for " + type);
      }
    }
    this.handlers = Map.copyOf(map);
  }

  // ======================================================================
  // CREATE (10-step template)
  // ======================================================================

  /**
   * Creates a new ascription through the GSM template method.
   *
   * <p>Resolves the archetype from the given ID, derives the subject type, and dispatches to the
   * appropriate handler via the 10-step create template.
   *
   * @param archetypeId the UUID of the typing archetype
   * @param statement the JSON statement payload
   * @param definitionId optional definition UUID (may be {@code null} for new definitions)
   * @return the persisted ascription entity in DRAFT status
   * @throws ResourceNotFoundException if no archetype exists with the given id
   * @throws RuleViolationException if validation fails
   */
  @Transactional("transactionManager")
  public AscriptionEntity create(UUID archetypeId, JsonNode statement, UUID definitionId) {
    ArchetypeService.ArchetypeResolution resolution =
        archetypeService.resolveForCreation(archetypeId);
    return doCreate(
        requireHandler(resolution.subjectType()), resolution.archetype(), statement, definitionId);
  }

  private <T extends AscriptionEntity> T doCreate(
      SubtypeHandler<T> handler,
      ArchetypeEntity archetypeRef,
      JsonNode statement,
      UUID definitionId) {
    // 1. Validate statement against archetype schema
    statementValidation.validateStatement(statement, archetypeRef, handler.getSubjectType());

    // 2. Resolve or create Definition
    DefinitionEntity definition =
        definitionService.resolveOrCreate(definitionId, handler.getSubjectType());

    // 3. Enforce $gsm:* annotations on statement
    statementValidation.enforceAnnotations(
        statement,
        archetypeRef,
        definition.getId(),
        defId -> handler.getRepository().findAllByDefinitionIdOrderByTimestampDesc(defId));

    // 4. Build subtype-specific entity
    T entity = handler.buildEntity(definition, archetypeRef, statement);

    // 5. Validate identity-bound invariant
    validateIdentityBound(handler, entity);

    // 6. Validate creation referee preconditions
    validateCreationPreconditions(handler, entity);

    // 7. Persist
    T saved;
    try {
      saved = handler.getRepository().save(entity);

      // 8. Flush pending INSERT so refresh() can re-read the row.
      entityManager.flush();

      // 9. Refresh to pick up DB-trigger-assigned fields (status, timestamp)
      entityManager.refresh(saved);

      // 9b. Ensure archetype is initialized after refresh (entity graph does not apply to refresh)
      Hibernate.initialize(saved.getArchetype());
    } catch (DataIntegrityViolationException ex) {
      throw PersistenceExceptionTranslationService.translate(ex);
    }

    // 10. Post-creation hook (e.g., auto-derivation of ports)
    handler.afterCreate(saved);

    return saved;
  }

  // ======================================================================
  // Generic CRUD (delegates to handler's repository)
  // ======================================================================

  public <T extends AscriptionEntity> Page<T> findAll(
      DefinitionSubjectType type, Pageable pageable) {
    return this.<T>requireHandler(type).getRepository().findAll(pageable);
  }

  public <T extends AscriptionEntity> Page<T> findAllByStatus(
      DefinitionSubjectType type, AscriptionStatusType status, Pageable pageable) {
    return this.<T>requireHandler(type).getRepository().findAllByStatus(status, pageable);
  }

  public <T extends AscriptionEntity> List<T> findAllByDefinitionId(
      DefinitionSubjectType type, UUID definitionId) {
    return this.<T>requireHandler(type)
        .getRepository()
        .findAllByDefinitionIdOrderByTimestampDesc(definitionId);
  }

  /**
   * Queries ascriptions with optional archetype, status, and statement-property filters.
   *
   * <p>When {@code archetype} and {@code statementFilters} are both absent, falls back to a simple
   * status-only or unfiltered query. When statement filters are present, the archetype parameter is
   * required (throws {@link IllegalArgumentException} if missing) and each filter key must be
   * annotated {@code $gsm:queryable} in the archetype schema.
   *
   * @param type the GSM subject type
   * @param archetype optional archetype UUID or title (required when statementFilters is non-empty)
   * @param statementFilters statement property key-value filters (may be empty)
   * @param status optional lifecycle status filter
   * @param pageable pagination parameters
   * @return page of matching ascriptions
   */
  public Page<? extends AscriptionEntity> findAllFiltered(
      DefinitionSubjectType type,
      String archetype,
      Map<String, String> statementFilters,
      AscriptionStatusType status,
      Pageable pageable) {
    if (statementFilters.isEmpty() && archetype == null) {
      return (status != null) ? findAllByStatus(type, status, pageable) : findAll(type, pageable);
    }
    ArchetypeEntity archetypeEntity = resolveArchetypeParam(archetype, statementFilters);
    if (!statementFilters.isEmpty()) {
      validateQueryableProperties(archetypeEntity, statementFilters);
    }
    UUID archetypeDefId = archetypeEntity.getDefinition().getId();
    Specification<AscriptionEntity> spec =
        buildFilterSpec(archetypeDefId, statementFilters, status);
    return requireHandler(type).getRepository().findAll(spec, pageable);
  }

  @Transactional(value = "transactionManager", readOnly = true)
  public <T extends AscriptionEntity> List<T> getHistory(
      DefinitionSubjectType type, UUID definitionId) {
    return findAllByDefinitionId(type, definitionId);
  }

  // ======================================================================
  // Cross-subtype lookups (union ascription table)
  // ======================================================================

  @Transactional(value = "transactionManager", readOnly = true)
  public AscriptionEntity getById(UUID ascriptionId) {
    return ascriptionRepository
        .findById(ascriptionId)
        .orElseThrow(() -> new ResourceNotFoundException(PrimitiveType.ASCRIPTION, ascriptionId));
  }

  @Transactional(value = "transactionManager", readOnly = true)
  public List<AscriptionEntity> findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
      UUID archetypeId, Collection<AscriptionStatusType> statuses, UUID excludeDefinitionId) {
    return ascriptionRepository.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
        archetypeId, statuses, excludeDefinitionId);
  }

  // ======================================================================
  // Handler access
  // ======================================================================

  public SubtypeHandler<?> getHandler(DefinitionSubjectType type) {
    return requireHandler(type);
  }

  @SuppressWarnings("unchecked")
  private <T extends AscriptionEntity> SubtypeHandler<T> requireHandler(
      DefinitionSubjectType type) {
    SubtypeHandler<?> handler = handlers.get(type);
    if (handler == null) {
      throw new IllegalStateException("No handler for subject type: " + type);
    }
    return (SubtypeHandler<T>) handler;
  }

  // ======================================================================
  // Archetype resolution and query validation (absorbed from controller)
  // ======================================================================

  /**
   * Resolves an archetype parameter that may be a UUID or a title.
   *
   * @param archetype the archetype identifier (UUID string or title), may be {@code null}
   * @param statementFilters statement filters (used only in the error message)
   * @return the resolved archetype entity
   * @throws IllegalArgumentException if {@code archetype} is null or cannot be resolved
   */
  private ArchetypeEntity resolveArchetypeParam(
      String archetype, Map<String, String> statementFilters) {
    if (archetype == null) {
      throw new IllegalArgumentException(
          "Statement attribute filtering requires the 'archetype' parameter.");
    }
    try {
      UUID archetypeId = UUID.fromString(archetype);
      return archetypeService.findEntityById(archetypeId);
    } catch (IllegalArgumentException ignored) {
      // Not a UUID — try title
    }
    return archetypeService
        .findInEffectByTitle(archetype)
        .orElseThrow(
            () -> new IllegalArgumentException("No in-effect Archetype found for: " + archetype));
  }

  /**
   * Validates that every statement filter key is annotated {@code $gsm:queryable} in the archetype
   * schema.
   */
  private static void validateQueryableProperties(
      ArchetypeEntity archetypeEntity, Map<String, String> statementFilters) {
    JsonNode schema = archetypeEntity.getStatement();
    JsonNode properties = schema.path("properties");
    for (String propName : statementFilters.keySet()) {
      JsonNode propSchema = properties.path(propName);
      if (propSchema.isMissingNode() || !propSchema.path("$gsm:queryable").asBoolean(false)) {
        throw new IllegalArgumentException(
            "Property '"
                + propName
                + "' is not annotated with $gsm:queryable "
                + "in Archetype '"
                + schema.path("title").asText()
                + "'.");
      }
    }
  }

  // ======================================================================
  // Identity-bound validation (called from create template)
  // ======================================================================

  private <T extends AscriptionEntity> void validateIdentityBound(
      SubtypeHandler<T> handler, AscriptionEntity entity) {
    Map<String, Object> newValues = handler.getIdentityBoundValues(entity);
    if (newValues.isEmpty()) {
      return;
    }

    UUID definitionId = entity.getDefinition().getId();
    List<T> existing = handler.findAllByDefinitionId(definitionId);
    if (existing.isEmpty()) {
      return;
    }

    AscriptionEntity first = existing.getLast();
    Map<String, Object> firstValues = handler.getIdentityBoundValues(first);

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
  // Creation referee preconditions
  // ======================================================================

  private void validateCreationPreconditions(SubtypeHandler<?> handler, AscriptionEntity entity) {
    stateMachine.validateRefereePreconditions(
        handler.getRefereeReferences(entity), null, AscriptionStatusType.DRAFT);
  }

  // ======================================================================
  // Static utilities (used by handlers)
  // ======================================================================

  /**
   * Checks that a property value is unique across all in-effect ascriptions of the same type (from
   * different definitions). Used by handlers in {@link
   * SubtypeHandler#validateActivationUniqueness}.
   */
  static void validatePropertyUniquenessAcrossDefinitions(
      DefinitionSubjectType subjectType,
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
            subjectType.getPrimitiveType().getLabel()
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

  /** Builds a JPA {@link Specification} for filtered ascription queries. */
  static <T extends AscriptionEntity> Specification<T> buildFilterSpec(
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

  /** Extracts a required UUID from a statement JSON field. */
  static UUID extractRequiredUuid(
      JsonNode statement, String field, AscriptionConsistencyRuleType rule) {
    JsonNode node = statement.get(field);
    if (node == null || node.isNull() || node.asText().isBlank()) {
      throw RuleViolationException.of(
          rule, "Required field '" + field + "' missing in statement payload", "field", field);
    }
    try {
      return UUID.fromString(node.asText());
    } catch (IllegalArgumentException e) {
      throw RuleViolationException.of(
          rule,
          "Invalid UUID for field '" + field + "': " + node.asText(),
          "field",
          field,
          "value",
          node.asText());
    }
  }

  /** Extracts a list of UUIDs from a statement JSON array field. */
  static List<UUID> extractUuidList(
      JsonNode statement, String field, AscriptionConsistencyRuleType rule) {
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
            rule,
            "Invalid UUID in '" + field + "': " + element.asText(),
            "field",
            field,
            "value",
            element.asText());
      }
    }
    return result;
  }
}
