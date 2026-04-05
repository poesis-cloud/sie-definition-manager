package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
 * Facade for all GSM ascription operations.
 *
 * <p>Owns the 10-step create template, generic CRUD delegation, cross-subtype lookups on the union
 * ascription table, and shared validation utilities consumed by {@link AscriptionSubtypeService}
 * implementations.
 *
 * <p>Type-specific logic (entity construction, identity-bound values, lifecycle cascades) is
 * dispatched to the appropriate {@link AscriptionSubtypeService} — one per GSM subject type.
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
  private final AscriptionParsingValidationService statementValidation;
  private final AscriptionIdentityBoundValidationService identityBoundValidation;
  private final AscriptionUniquenessValidationService uniquenessValidation;
  private final AscriptionProtectionService statementProtection;
  private final EntityManager entityManager;
  private final List<AscriptionSubtypeService<?>> handlerList;

  private Map<DefinitionSubjectType, AscriptionSubtypeService<?>> handlers;

  public AscriptionService(
      AscriptionRepository ascriptionRepository,
      ArchetypeService archetypeService,
      DefinitionService definitionService,
      AscriptionStateMachineService stateMachine,
      AscriptionParsingValidationService statementValidation,
      AscriptionIdentityBoundValidationService identityBoundValidation,
      AscriptionUniquenessValidationService uniquenessValidation,
      AscriptionProtectionService statementProtection,
      EntityManager entityManager,
      List<AscriptionSubtypeService<?>> handlerList) {
    this.ascriptionRepository = ascriptionRepository;
    this.archetypeService = archetypeService;
    this.definitionService = definitionService;
    this.stateMachine = stateMachine;
    this.statementValidation = statementValidation;
    this.identityBoundValidation = identityBoundValidation;
    this.uniquenessValidation = uniquenessValidation;
    this.statementProtection = statementProtection;
    this.entityManager = entityManager;
    this.handlerList = List.copyOf(handlerList);
  }

  @Override
  public void afterSingletonsInstantiated() {
    Map<DefinitionSubjectType, AscriptionSubtypeService<?>> map =
        new EnumMap<>(DefinitionSubjectType.class);
    for (AscriptionSubtypeService<?> handler : handlerList) {
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
      AscriptionSubtypeService<T> handler,
      ArchetypeEntity archetype,
      JsonNode statement,
      UUID definitionId) {
    // 1. Validate statement against archetype schema
    statementValidation.validateStatement(statement, archetype, handler.getSubjectType());

    // 2. Resolve or create Definition
    DefinitionEntity definition = definitionService.resolve(definitionId, handler.getSubjectType());

    // 3. Apply $gsm:dataProtection (at-rest) transformations on statement
    applyDataProtection(statement, archetype);

    // 4. Create subtype-specific entity
    T entity = handler.create(definition, archetype, statement);

    // 5. Validate identity-bound invariant (handler-declared + annotation-declared)
    identityBoundValidation.validate(handler, entity, archetype);

    // 6. Validate $gsm:unique annotation-driven uniqueness
    uniquenessValidation.validate(statement, archetype, definition.getId());

    // 7. Validate creation referee preconditions
    stateMachine.validateRefereePreconditions(
        handler.getRefereeReferences(entity), null, AscriptionStatusType.DRAFT);

    // 8. Persist
    T saved;
    try {
      saved = handler.save(entity);

      // 9. Flush pending INSERT so refresh() can re-read the row.
      entityManager.flush();

      // 10. Refresh to pick up DB-trigger-assigned fields (status, timestamp)
      entityManager.refresh(saved);

      // 10b. Ensure archetype is initialized after refresh (entity graph does not
      // apply to refresh)
      Hibernate.initialize(saved.getArchetype());
    } catch (DataIntegrityViolationException ex) {
      throw PersistenceExceptionTranslationService.translate(ex);
    }

    // 11. Post-creation hook (e.g., auto-derivation of ports)
    handler.afterCreate(saved);

    return saved;
  }

  // ======================================================================
  // Read — subtype-scoped queries (delegated to handler)
  // ======================================================================

  private <T extends AscriptionEntity> Page<T> findAll(
      DefinitionSubjectType type, Pageable pageable) {
    return this.<T>requireHandler(type).findAll(pageable);
  }

  private <T extends AscriptionEntity> Page<T> findAllByStatus(
      DefinitionSubjectType type, AscriptionStatusType status, Pageable pageable) {
    return this.<T>requireHandler(type).findAllByStatus(status, pageable);
  }

  private <T extends AscriptionEntity> List<T> findAllByDefinitionId(
      DefinitionSubjectType type, UUID definitionId) {
    return this.<T>requireHandler(type).findAllByDefinitionId(definitionId);
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
    ArchetypeEntity archetypeEntity = findInEffectArchetypeByTitle(archetype, statementFilters);
    if (!statementFilters.isEmpty()) {
      validatePropertiesQueryability(archetypeEntity, statementFilters);
    }
    UUID archetypeDefId = archetypeEntity.getDefinition().getId();
    Specification<AscriptionEntity> spec =
        buildFilterSpec(archetypeDefId, statementFilters, status);
    return requireHandler(type).findAll(spec, pageable);
  }

  /**
   * Finds an in-effect archetype by title.
   *
   * @param title the archetype title, may be {@code null}
   * @param statementFilters statement filters (used only to contextualize the error message)
   * @return the in-effect archetype entity
   * @throws IllegalArgumentException if {@code title} is null or no in-effect archetype matches
   */
  private ArchetypeEntity findInEffectArchetypeByTitle(
      String title, Map<String, String> statementFilters) {
    if (title == null) {
      throw new IllegalArgumentException(
          "Statement attribute filtering requires the 'archetype' parameter.");
    }
    return archetypeService
        .findInEffectByTitle(title)
        .orElseThrow(
            () -> new IllegalArgumentException("No in-effect Archetype found for: " + title));
  }

  // ======================================================================
  // Read — cross-subtype lookups (union ascription table)
  // ======================================================================

  /**
   * Finds an ascription by its unique ID across all subject types.
   *
   * @param ascriptionId the ascription UUID
   * @return the ascription entity
   * @throws ResourceNotFoundException if no ascription exists with the given id
   */
  @Transactional(value = "transactionManager", readOnly = true)
  public AscriptionEntity getById(UUID ascriptionId) {
    return ascriptionRepository
        .findById(ascriptionId)
        .orElseThrow(() -> new ResourceNotFoundException(PrimitiveType.ASCRIPTION, ascriptionId));
  }

  /**
   * Returns all ascriptions for a given archetype filtered by statuses, excluding a specific
   * definition. Used by activation-uniqueness checks to find in-effect siblings from other
   * definitions.
   *
   * @param archetypeId the archetype UUID
   * @param statuses the allowed lifecycle statuses
   * @param excludeDefinitionId the definition UUID to exclude from results
   * @return list of matching ascription entities
   */
  @Transactional(value = "transactionManager", readOnly = true)
  public List<AscriptionEntity> findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
      UUID archetypeId, Collection<AscriptionStatusType> statuses, UUID excludeDefinitionId) {
    return ascriptionRepository.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
        archetypeId, statuses, excludeDefinitionId);
  }

  @SuppressWarnings("unchecked")
  private <T extends AscriptionEntity> AscriptionSubtypeService<T> requireHandler(
      DefinitionSubjectType type) {
    AscriptionSubtypeService<?> handler = handlers.get(type);
    if (handler == null) {
      throw new IllegalStateException("No handler for subject type: " + type);
    }
    return (AscriptionSubtypeService<T>) handler;
  }

  // ======================================================================
  // Query helpers (private)
  // ======================================================================

  /**
   * Validates that every statement filter key is annotated {@code $gsm:queryable} in the archetype
   * schema.
   */
  private static void validatePropertiesQueryability(
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
  // Create-template: $gsm:dataProtection (at-rest transformation)
  // ======================================================================

  /**
   * Walks archetype schema properties and applies {@code $gsm:dataProtection} at-rest
   * transformations to the statement before entity creation. Mutates the statement in place.
   */
  private void applyDataProtection(JsonNode statement, ArchetypeEntity archetype) {
    JsonNode archetypeStmt = archetype.getStatement();
    if (archetypeStmt == null) {
      return;
    }
    JsonNode properties = archetypeStmt.get("properties");
    if (properties == null || !properties.isObject()) {
      return;
    }

    for (Map.Entry<String, JsonNode> entry : properties.properties()) {
      String propName = entry.getKey();
      JsonNode propSchema = entry.getValue();

      if (!propSchema.has("$gsm:dataProtection")) {
        continue;
      }
      if (!statement.has(propName)) {
        continue;
      }

      statementProtection.applyAtRestProtection(
          propSchema.get("$gsm:dataProtection"), propName, (ObjectNode) statement);
    }
  }

  /** Builds a JPA {@link Specification} for filtered ascription queries. */
  private static <T extends AscriptionEntity> Specification<T> buildFilterSpec(
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
}
