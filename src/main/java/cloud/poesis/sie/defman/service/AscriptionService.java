package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.observability.PayloadLogHelper;
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
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.hibernate.Hibernate;
import org.slf4j.MDC;
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
  private static final String INSTRUMENTATION_SCOPE = "cloud.poesis.sie.defman.observability";
  private static final String CREATE_SPAN_NAME = "gsm.definition.create";
  private static final String TRANSFORM_SPAN_NAME = "gsm.definition.transform";
  private static final String OPERATION_CREATE = "create";
  private static final String OPERATION_TRANSFORM = "transform";
  private static final String ATTR_OPERATION = "gsm.definition.operation";
  private static final String ATTR_DEFINITION_ID = "gsm.definition.id";
  private static final String ATTR_DEFINITION_KIND = "gsm.definition.kind";
  private static final String ATTR_TENANT_ID = "gsm.tenant.id";
  private static final String ATTR_COMPONENT = "sie.component";
  private static final String COMPONENT_DEFINITION_MANAGER = "definition-manager";
  private static final String EVENT_VALIDATE_STATEMENT = "validate-statement";
  private static final String EVENT_RESOLVE_DEFINITION = "resolve-definition";
  private static final String EVENT_PROTECT_STATEMENT = "apply-data-protection";
  private static final String EVENT_BUILD_SUBTYPE = "build-subtype";
  private static final String EVENT_VALIDATE_IDENTITY = "validate-identity-bound";
  private static final String EVENT_VALIDATE_UNIQUENESS = "validate-uniqueness";
  private static final String EVENT_VALIDATE_REFEREES = "validate-referees";
  private static final String EVENT_PERSIST = "persist";
  private static final String EVENT_AFTER_CREATE = "after-create";
  private static final String EVENT_OUTCOME_SUCCESS = "success";
  private static final String MDC_TRACE_ID = "trace_id";
  private static final String MDC_SPAN_ID = "span_id";
  private static final int DEFAULT_CREATE_RESULT_PAYLOAD_CAP_BYTES = 16_384;

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
  private final Tracer tracer;
  private final io.opentelemetry.api.logs.Logger otelLogger;
  private final int createResultPayloadCapBytes;

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
      this(
        ascriptionRepository,
        archetypeService,
        definitionService,
        stateMachine,
        statementValidation,
        identityBoundValidation,
        uniquenessValidation,
        statementProtection,
        entityManager,
        handlerList,
        GlobalOpenTelemetry.getTracer(INSTRUMENTATION_SCOPE, "1"),
        DEFAULT_CREATE_RESULT_PAYLOAD_CAP_BYTES);
      }

      AscriptionService(
        AscriptionRepository ascriptionRepository,
        ArchetypeService archetypeService,
        DefinitionService definitionService,
        AscriptionStateMachineService stateMachine,
        AscriptionParsingValidationService statementValidation,
        AscriptionIdentityBoundValidationService identityBoundValidation,
        AscriptionUniquenessValidationService uniquenessValidation,
        AscriptionProtectionService statementProtection,
        EntityManager entityManager,
        List<AscriptionSubtypeService<?>> handlerList,
        Tracer tracer,
        int createResultPayloadCapBytes) {
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
    this.tracer = tracer;
    this.otelLogger =
      GlobalOpenTelemetry
        .get()
        .getLogsBridge()
        .loggerBuilder(INSTRUMENTATION_SCOPE)
        .setInstrumentationVersion("1")
        .build();
    this.createResultPayloadCapBytes = createResultPayloadCapBytes;
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
    if (definitionId == null) {
      return doCreateWithOperationSpan(
          requireHandler(resolution.subjectType()), resolution.archetype(), statement);
    }
    return doTransformWithOperationSpan(
        requireHandler(resolution.subjectType()),
        resolution.archetype(),
        statement,
        definitionId);
  }

  private <T extends AscriptionEntity> T doCreateWithOperationSpan(
      AscriptionSubtypeService<T> handler, ArchetypeEntity archetype, JsonNode statement) {
    Span createSpan =
        startDefinitionSpan(CREATE_SPAN_NAME, OPERATION_CREATE, handler.getSubjectType(), null);

    try (Scope ignored = createSpan.makeCurrent()) {
      return doCreate(handler, archetype, statement, null, createSpan, OPERATION_CREATE, null);
    } catch (RuntimeException ex) {
      createSpan.recordException(ex);
      throw ex;
    } finally {
      createSpan.end();
    }
  }

  private <T extends AscriptionEntity> T doTransformWithOperationSpan(
      AscriptionSubtypeService<T> handler,
      ArchetypeEntity archetype,
      JsonNode statement,
      UUID definitionId) {
    Span transformSpan =
        startDefinitionSpan(
            TRANSFORM_SPAN_NAME,
            OPERATION_TRANSFORM,
            handler.getSubjectType(),
            definitionId);
    JsonNode priorStatement = (statement == null) ? null : statement.deepCopy();

    try (Scope ignored = transformSpan.makeCurrent()) {
      return doCreate(
          handler,
          archetype,
          statement,
          definitionId,
          transformSpan,
          OPERATION_TRANSFORM,
          priorStatement);
    } catch (RuntimeException ex) {
      transformSpan.recordException(ex);
      throw ex;
    } finally {
      transformSpan.end();
    }
  }

  private <T extends AscriptionEntity> T doCreate(
      AscriptionSubtypeService<T> handler,
      ArchetypeEntity archetype,
      JsonNode statement,
      UUID definitionId) {
    return doCreate(handler, archetype, statement, definitionId, null, OPERATION_CREATE, null);
  }

  private <T extends AscriptionEntity> T doCreate(
      AscriptionSubtypeService<T> handler,
      ArchetypeEntity archetype,
      JsonNode statement,
      UUID definitionId,
      Span definitionSpan,
      String operation,
      JsonNode priorStatement) {
    addDefinitionEvent(definitionSpan, operation, EVENT_VALIDATE_STATEMENT);
    // 1. Validate statement against archetype schema
    statementValidation.validateStatement(statement, archetype, handler.getSubjectType());

    addDefinitionEvent(definitionSpan, operation, EVENT_RESOLVE_DEFINITION);
    // 2. Resolve or create Definition
    DefinitionEntity definition = definitionService.resolve(definitionId, handler.getSubjectType());
    setCreateSpanDefinitionAttributes(definitionSpan, definition.getId(), handler.getSubjectType());
    emitCorrelatedMilestoneLog(definitionSpan, operation, EVENT_RESOLVE_DEFINITION, EVENT_OUTCOME_SUCCESS);

    addDefinitionEvent(definitionSpan, operation, EVENT_PROTECT_STATEMENT);
    // 3. Apply $gsm:dataProtection (at-rest) transformations on statement
    applyDataProtection(statement, archetype);

    addDefinitionEvent(definitionSpan, operation, EVENT_BUILD_SUBTYPE);
    // 4. Create subtype-specific entity
    T entity = handler.create(definition, archetype, statement);

    addDefinitionEvent(definitionSpan, operation, EVENT_VALIDATE_IDENTITY);
    // 5. Validate identity-bound invariant (handler-declared + annotation-declared)
    identityBoundValidation.validate(handler, entity, archetype);

    addDefinitionEvent(definitionSpan, operation, EVENT_VALIDATE_UNIQUENESS);
    // 6. Validate $gsm:unique annotation-driven uniqueness
    uniquenessValidation.validate(statement, archetype, definition.getId());

    addDefinitionEvent(definitionSpan, operation, EVENT_VALIDATE_REFEREES);
    // 7. Validate creation referee preconditions
    stateMachine.validateRefereePreconditions(
        handler.getRefereeReferences(entity), null, AscriptionStatusType.DRAFT);

    addDefinitionEvent(definitionSpan, operation, EVENT_PERSIST);
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
      emitCorrelatedMilestoneLog(definitionSpan, operation, EVENT_PERSIST, EVENT_OUTCOME_SUCCESS);
    } catch (DataIntegrityViolationException ex) {
      throw PersistenceExceptionTranslationService.translate(ex);
    }

    addDefinitionEvent(definitionSpan, operation, EVENT_AFTER_CREATE);
    // 11. Post-creation hook (e.g., auto-derivation of ports)
    handler.afterCreate(saved);
    emitCorrelatedMilestoneLog(definitionSpan, operation, EVENT_AFTER_CREATE, EVENT_OUTCOME_SUCCESS);
    emitOperationPayloadLog(
        definitionSpan,
        operation,
        saved,
        definition.getId(),
        priorStatement);

    return saved;
  }

  private Span startDefinitionSpan(
      String spanName,
      String operation,
      DefinitionSubjectType definitionKind,
      UUID definitionId) {
    Span definitionSpan =
        tracer
            .spanBuilder(spanName)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPERATION, operation)
            .setAttribute(ATTR_DEFINITION_KIND, definitionKind.name())
            .setAttribute(ATTR_COMPONENT, COMPONENT_DEFINITION_MANAGER)
            .startSpan();

    if (definitionId != null) {
      definitionSpan.setAttribute(ATTR_DEFINITION_ID, definitionId.toString());
    }

    String tenantId = MDC.get(ATTR_TENANT_ID);
    if (tenantId != null && !tenantId.isBlank()) {
      definitionSpan.setAttribute(ATTR_TENANT_ID, tenantId);
    }

    return definitionSpan;
  }

  private static void addDefinitionEvent(Span definitionSpan, String operation, String eventSuffix) {
    if (definitionSpan != null) {
      definitionSpan.addEvent("gsm.definition." + operation + "." + eventSuffix);
    }
  }

  private static void setCreateSpanDefinitionAttributes(
      Span createSpan, UUID definitionId, DefinitionSubjectType definitionKind) {
    if (createSpan == null) {
      return;
    }
    createSpan.setAttribute(ATTR_DEFINITION_ID, definitionId.toString());
    createSpan.setAttribute(ATTR_DEFINITION_KIND, definitionKind.name());
  }

  private void emitCorrelatedMilestoneLog(
      Span definitionSpan, String operation, String eventSuffix, String eventOutcome) {
    if (definitionSpan == null) {
      return;
    }

    String eventName = "gsm.definition." + operation + "." + eventSuffix;
    SpanContext spanContext = definitionSpan.getSpanContext();
    String traceId = spanContext.isValid() ? spanContext.getTraceId() : MDC.get(MDC_TRACE_ID);
    String spanId = spanContext.isValid() ? spanContext.getSpanId() : MDC.get(MDC_SPAN_ID);
    String tenantId = MDC.get(ATTR_TENANT_ID);

    emitOtelCorrelationLogRecord(eventName, eventOutcome, traceId, spanId, tenantId);
  }

  private void emitOtelCorrelationLogRecord(
      String eventName,
      String eventOutcome,
      String traceId,
      String spanId,
      String tenantId) {
    otelLogger
        .logRecordBuilder()
        .setContext(Context.current())
        .setSeverity(Severity.INFO)
        .setSeverityText(Severity.INFO.name())
        .setAttribute("event.name", eventName)
        .setAttribute("event.outcome", eventOutcome)
        .setAttribute("sie.component", COMPONENT_DEFINITION_MANAGER)
        .setAttribute("trace_id", traceId == null ? "" : traceId)
        .setAttribute("span_id", spanId == null ? "" : spanId)
        .setAttribute("gsm.tenant.id", tenantId == null ? "" : tenantId)
        .emit();
  }

  private void emitOperationPayloadLog(
      Span definitionSpan,
      String operation,
      AscriptionEntity saved,
      UUID definitionId,
      JsonNode priorStatement) {
    if (definitionSpan == null) {
      return;
    }

    if (OPERATION_CREATE.equals(operation)) {
      emitCreateResultPayloadLog(saved, definitionId);
      return;
    }

    emitTransformPayloadContextLog(definitionId, priorStatement, saved.getStatement());
  }

  private void emitCreateResultPayloadLog(AscriptionEntity saved, UUID definitionId) {
    if (saved.getStatement() == null) {
      return;
    }

    String payload = saved.getStatement().toString();
    PayloadLogHelper.boundedPayloadWithSpanSummary(
      "create", definitionId.toString(), payload, createResultPayloadCapBytes);
  }

  private void emitTransformPayloadContextLog(
      UUID definitionId,
      JsonNode priorStatement,
      JsonNode resultStatement) {
    String priorPayload = priorStatement == null ? null : priorStatement.toString();
    String resultPayload = resultStatement == null ? null : resultStatement.toString();

    if (priorPayload == null && resultPayload == null) {
      return;
    }

    PayloadLogHelper.boundedPayloadWithSpanSummary(
      "transform.prior", definitionId.toString(), priorPayload, createResultPayloadCapBytes);
    PayloadLogHelper.boundedPayloadWithSpanSummary(
      "transform.result", definitionId.toString(), resultPayload, createResultPayloadCapBytes);
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
