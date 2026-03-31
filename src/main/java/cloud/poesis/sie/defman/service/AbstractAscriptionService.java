package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.InternalException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.RuleType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.DisallowSchemaLoader;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.exception.ConstraintViolationException;
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

  /**
   * Classpath-only JSON Schema factory for resolving GSM base archetype {@code gsm://} URIs. Used
   * when no tenant archetypes need DB resolution. GSM §8 security invariant: DM MUST NOT resolve
   * {@code $schema} URIs from incoming tenant schemas via network — all resolution is local.
   */
  private static final JsonSchemaFactory CLASSPATH_SCHEMA_FACTORY =
      JsonSchemaFactory.getInstance(
          SpecVersion.VersionFlag.V202012,
          builder ->
              builder.schemaMappers(
                  mappers ->
                      mappers.mappings(
                          uri -> uri.startsWith("gsm://archetypes/"),
                          uri -> {
                            // gsm://archetypes/{Name}/v{N} →
                            // classpath:schemas/gsm-archetypes/{Name}.schema.json
                            String rest = uri.substring("gsm://archetypes/".length());
                            String name = rest.split("/")[0];
                            return "classpath:schemas/gsm-archetypes/" + name + ".schema.json";
                          })));

  /** CEL compiler + runtime for $gsm:validation enforcement at Ascription authoring. */
  private final CelCompiler celCompiler =
      CelCompilerFactory.standardCelCompilerBuilder().addVar("this", SimpleType.DYN).build();

  private final CelRuntime celRuntime = CelRuntimeFactory.standardCelRuntimeBuilder().build();
  private final ObjectMapper celMapper = new ObjectMapper();

  // Shared dependencies — constructor-injected into all subclasses
  private final DefinitionService definitionService;
  private final AscriptionStatusTransitionService transitionService;
  private final AscriptionRepository ascriptionRepository;
  private final ArchetypeRepository archetypeRepository;
  private final EntityManager entityManager;
  private final DataProtectionService dataProtectionService;

  /**
   * Constructs the abstract ascription service with shared dependencies.
   *
   * @param definitionService the definition service for identity resolution
   * @param transitionService the status transition service
   * @param ascriptionRepository the base ascription repository
   * @param archetypeRepository the archetype repository for tenant schema resolution
   * @param entityManager the JPA entity manager
   * @param dataProtectionService the data protection service
   */
  protected AbstractAscriptionService(
      DefinitionService definitionService,
      AscriptionStatusTransitionService transitionService,
      AscriptionRepository ascriptionRepository,
      ArchetypeRepository archetypeRepository,
      EntityManager entityManager,
      DataProtectionService dataProtectionService) {
    this.definitionService = definitionService;
    this.transitionService = transitionService;
    this.ascriptionRepository = ascriptionRepository;
    this.archetypeRepository = archetypeRepository;
    this.entityManager = entityManager;
    this.dataProtectionService = dataProtectionService;
  }

  // Referee precondition for [*]→DRAFT creation
  private static final Set<AscriptionStatusType> CREATION_REFEREE_ALLOWED =
      EnumSet.of(
          AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED,
          AscriptionStatusType.APPROVED, AscriptionStatusType.ACTIVE);

  // In-effect statuses for $gsm:unique enforcement
  private static final List<AscriptionStatusType> GSM_IN_EFFECT =
      List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);

  // GSM base schema property sets for extensible subject types (sealed — these
  // match the GSM base archetype schemas and never change at runtime).
  // Used to classify validation errors as GSM-base vs tenant-extension.
  private static final Map<DefinitionSubjectType, Set<String>> GSM_BASE_PROPERTIES =
      Map.of(
          DefinitionSubjectType.STRUCTURE, Set.of("purpose"),
          DefinitionSubjectType.MECHANISM, Set.of("structure", "function", "rule"),
          DefinitionSubjectType.INTERACTION, Set.of("effector", "receptor"));

  // Known PostgreSQL constraint → RuleType mapping (auto-generated FK names
  // and partial unique indexes)
  static final Map<String, RuleType> CONSTRAINT_TO_RULE =
      Map.ofEntries(
          // Directive reference FKs
          Map.entry(
              "directive_structure_id_fkey", RuleType.DIRECTIVE_STRUCTURE_REFERENCE_INTEGRITY),
          Map.entry(
              "directive_qualifier_id_fkey", RuleType.DIRECTIVE_QUALIFIER_REFERENCE_INTEGRITY),
          Map.entry("directive_purpose_id_fkey", RuleType.DIRECTIVE_PURPOSE_REFERENCE_INTEGRITY),
          // Norm reference FKs
          Map.entry("norm_structure_id_fkey", RuleType.NORM_STRUCTURE_REFERENCE_INTEGRITY),
          Map.entry("norm_qualifier_id_fkey", RuleType.NORM_QUALIFIER_REFERENCE_INTEGRITY),
          // Effector / Receptor reference FKs
          Map.entry("effector_mechanism_id_fkey", RuleType.EFFECTOR_MECHANISM_REFERENCE_INTEGRITY),
          Map.entry("receptor_mechanism_id_fkey", RuleType.RECEPTOR_MECHANISM_REFERENCE_INTEGRITY),
          // Interaction reference FKs
          Map.entry(
              "interaction_effector_id_fkey", RuleType.INTERACTION_EFFECTOR_REFERENCE_INTEGRITY),
          Map.entry(
              "interaction_receptor_id_fkey", RuleType.INTERACTION_RECEPTOR_REFERENCE_INTEGRITY),
          // Archetype self-typing FK
          Map.entry("archetype_typed_by_fk", RuleType.ASCRIPTION_ARCHETYPE_REFERENCE_INTEGRITY),
          // Identity uniqueness indexes
          Map.entry(
              "uq_structure_purpose", RuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS),
          Map.entry(
              "uq_mechanism_function", RuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS),
          Map.entry(
              "uq_archetype_title", RuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS));

  // ======================================================================
  // Persistence exception translation
  // ======================================================================

  /**
   * Translates a {@link DataIntegrityViolationException} to a domain exception.
   *
   * @param ex the persistence exception to translate
   * @return a {@link RuleViolationException} for known constraints, or a {@link InternalException}
   *     for unmapped constraints
   */
  static RuntimeException translatePersistenceException(DataIntegrityViolationException ex) {
    String constraintName = extractConstraintName(ex);
    if (constraintName != null) {
      RuleType ruleType = CONSTRAINT_TO_RULE.get(constraintName);
      if (ruleType == null && constraintName.endsWith("_archetype_id_fkey")) {
        ruleType = RuleType.ASCRIPTION_ARCHETYPE_REFERENCE_INTEGRITY;
      }
      if (ruleType == null
          && (constraintName.endsWith("_output_archetype_id_fkey")
              || constraintName.endsWith("_input_archetype_id_fkey"))) {
        ruleType = RuleType.ASCRIPTION_ARCHETYPE_REFERENCE_INTEGRITY;
      }
      if (ruleType != null) {
        return RuleViolationException.of(
            ruleType,
            "Database constraint violation: " + constraintName,
            ex,
            "constraint",
            constraintName);
      }
    }
    LOG.error("Unmapped DB constraint violation (constraint={})", constraintName, ex);
    return new InternalException(
        "Database constraint violation" + (constraintName != null ? ": " + constraintName : ""),
        ex);
  }

  /**
   * Extracts the underlying constraint name from a persistence exception.
   *
   * @param ex the persistence exception
   * @return the constraint name, or {@code null} if unavailable
   */
  static String extractConstraintName(DataIntegrityViolationException ex) {
    Throwable cause = ex.getCause();
    if (cause instanceof ConstraintViolationException cve) {
      return cve.getConstraintName();
    }
    return null;
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
  protected RuleType statementValidationRule() {
    return switch (getSubjectType()) {
      case STRUCTURE -> RuleType.STRUCTURE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
      case MECHANISM -> RuleType.MECHANISM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
      case EFFECTOR -> RuleType.EFFECTOR_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
      case RECEPTOR -> RuleType.RECEPTOR_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
      case INTERACTION -> RuleType.INTERACTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
      case DIRECTIVE -> RuleType.DIRECTIVE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
      case NORM -> RuleType.NORM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
      case ARCHETYPE -> RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
    };
  }

  /**
   * Returns the GSM rule type for non-GSM (tenant-extension) statement validation violations.
   *
   * @return the non-GSM statement validation rule type, or {@code null} for sealed types
   */
  protected RuleType extensionStatementValidationRule() {
    return switch (getSubjectType()) {
      case STRUCTURE -> RuleType.STRUCTURE_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE;
      case MECHANISM -> RuleType.MECHANISM_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE;
      case INTERACTION -> RuleType.INTERACTION_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE;
      default -> null; // Other types have no tenant-extensible base schemas
    };
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
    validateStatement(statement, archetypeRef);

    // 2. Resolve or create Definition
    DefinitionEntity definition = definitionService.resolveOrCreate(definitionId, getSubjectType());

    // 3. Enforce $gsm:* annotations on statement
    enforceAnnotations(statement, archetypeRef, definition.getId());

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

      // 8. Record initial DRAFT transition
      transitionService.recordTransition(saved, null, AscriptionStatusType.DRAFT);

      // 9. Refresh to pick up DB-trigger-assigned fields (status, timestamp)
      entityManager.refresh(saved);
    } catch (DataIntegrityViolationException ex) {
      throw translatePersistenceException(ex);
    }

    // 10. Post-creation hook (e.g., auto-derivation of ports)
    afterCreate(saved);

    return saved;
  }

  // ======================================================================
  // Inner types (lifecycle descriptors)
  // ======================================================================

  /**
   * A reference edge: the entity being referenced (referee → reference). Used by the lifecycle
   * service to check referee preconditions.
   *
   * @param reference the referenced ascription entity
   * @param label human-readable label for the reference (used in error messages)
   */
  public record RefereeReference(AscriptionEntity reference, String label) {}

  // ======================================================================
  // Lifecycle descriptor methods (overridable)
  // ======================================================================

  /**
   * Returns referee references for lifecycle precondition checks.
   *
   * @param entity the ascription entity
   * @return list of referee references (empty by default)
   */
  public List<RefereeReference> getRefereeReferences(AscriptionEntity entity) {
    return List.of();
  }

  /**
   * Returns cascade target roles declared by this service.
   *
   * @return map of source subject type to cascade type (empty by default)
   */
  public Map<DefinitionSubjectType, AscriptionStatusTransitionCascadeType> getCascadeTargetRoles() {
    return Map.of();
  }

  /**
   * Finds cascade target entities originating from a source ascription.
   *
   * @param sourceType the source subject type
   * @param sourceAscriptionId the source ascription UUID
   * @return list of target entities (empty by default)
   */
  public List<? extends AscriptionEntity> findCascadeTargetsFrom(
      DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
    return List.of();
  }

  /**
   * Returns identity-bound field values for the given entity.
   *
   * @param entity the ascription entity
   * @return map of field name to value (empty by default)
   */
  public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
    return Map.of();
  }

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
  // Lifecycle hooks (called by AscriptionLifecycleService)
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
   * Returns the status transition service (for subtype post-creation logic).
   *
   * @return the transition service
   */
  protected AscriptionStatusTransitionService getTransitionService() {
    return transitionService;
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
            RuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION,
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
  // Creation referee preconditions (private — called from create())
  // ======================================================================

  void validateCreationPreconditions(AscriptionEntity entity) {
    List<RefereeReference> refs = getRefereeReferences(entity);
    if (refs.isEmpty()) {
      return;
    }

    for (RefereeReference ref : refs) {
      AscriptionStatusType refStatus = ref.reference().getStatus();
      if (!CREATION_REFEREE_ALLOWED.contains(refStatus)) {
        throw RuleViolationException.of(
            RuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
            "Referee '"
                + ref.label()
                + "' ("
                + ref.reference().getId()
                + ") is "
                + refStatus.name()
                + "; creation requires one of "
                + CREATION_REFEREE_ALLOWED.stream().map(Enum::name).collect(Collectors.toSet()),
            "fromStatus",
            null,
            "toStatus",
            AscriptionStatusType.DRAFT.name(),
            "refereeLabel",
            ref.label(),
            "refereeId",
            ref.reference().getId(),
            "refereeStatus",
            refStatus.name(),
            "requiredStatuses",
            CREATION_REFEREE_ALLOWED.stream().map(Enum::name).collect(Collectors.toSet()));
      }
    }
  }

  // ======================================================================
  // Statement validation (inlined from StatementValidator)
  // ======================================================================

  /**
   * Validates a statement against the archetype's JSON Schema.
   *
   * @param statement the JSON statement payload to validate
   * @param archetype the archetype whose schema defines the validation surface
   * @throws RuleViolationException if validation fails
   */
  void validateStatement(JsonNode statement, ArchetypeEntity archetype) {
    JsonNode archetypeStatement = archetype.getStatement();

    SchemaValidatorsConfig config =
        SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    JsonSchemaFactory factory = buildSchemaFactory(archetypeStatement);
    JsonSchema schema = factory.getSchema(archetypeStatement, config);
    Set<ValidationMessage> errors = schema.validate(statement);

    if (errors.isEmpty()) {
      return;
    }

    // For extensible subject types (Structure, Mechanism, Interaction),
    // classify errors as GSM-base vs tenant-extension violations.
    Set<String> baseProps = GSM_BASE_PROPERTIES.get(getSubjectType());
    RuleType extensionRule = extensionStatementValidationRule();

    if (baseProps != null && extensionRule != null) {
      List<String> baseMessages = new ArrayList<>();
      List<String> extensionMessages = new ArrayList<>();

      for (ValidationMessage err : errors) {
        if (isBaseSchemaError(err, baseProps)) {
          baseMessages.add(err.getMessage());
        } else {
          extensionMessages.add(err.getMessage());
        }
      }

      // Throw GSM violations first (higher precedence)
      if (!baseMessages.isEmpty()) {
        throw RuleViolationException.of(
            statementValidationRule(),
            "Statement validation failed against archetype "
                + archetype.getDefinition().getId()
                + ": "
                + baseMessages,
            "archetypeDefinitionId",
            archetype.getDefinition().getId(),
            "violations",
            baseMessages);
      }
      if (!extensionMessages.isEmpty()) {
        throw RuleViolationException.of(
            extensionRule,
            "Statement validation failed against tenant-extended archetype "
                + archetype.getDefinition().getId()
                + ": "
                + extensionMessages,
            "archetypeDefinitionId",
            archetype.getDefinition().getId(),
            "violations",
            extensionMessages);
      }
    }

    // Non-extensible types or fallback: all errors are GSM violations
    List<String> messages = errors.stream().map(ValidationMessage::getMessage).toList();
    throw RuleViolationException.of(
        statementValidationRule(),
        "Statement validation failed against archetype "
            + archetype.getDefinition().getId()
            + ": "
            + messages,
        "archetypeDefinitionId",
        archetype.getDefinition().getId(),
        "violations",
        messages);
  }

  // --- Schema factory with tenant-aware resolution (R1 from E1 gap register) ---

  /** GSM base archetype titles that live on classpath. */
  private static final Set<String> CLASSPATH_ARCHETYPE_TITLES =
      Set.of(
          "Archetype",
          "StructureArchetype",
          "MechanismArchetype",
          "EffectorArchetype",
          "ReceptorArchetype",
          "InteractionArchetype",
          "DirectiveArchetype",
          "NormArchetype");

  private static final java.util.regex.Pattern GSM_URI_PATTERN =
      java.util.regex.Pattern.compile("^gsm://archetypes/([^/]+)/v\\d+$");

  /**
   * Builds a {@link JsonSchemaFactory} that resolves gsm:// URIs. GSM base archetypes are resolved
   * from classpath; tenant archetypes are resolved from the database. Network-based resolution is
   * blocked via {@link DisallowSchemaLoader}.
   */
  private JsonSchemaFactory buildSchemaFactory(JsonNode archetypeSchema) {
    Map<String, String> tenantSchemaJsonByUri = collectTenantSchemaMap(archetypeSchema);

    if (tenantSchemaJsonByUri.isEmpty()) {
      return CLASSPATH_SCHEMA_FACTORY;
    }

    return JsonSchemaFactory.getInstance(
        SpecVersion.VersionFlag.V202012,
        builder ->
            builder
                .schemaLoaders(
                    loaders ->
                        loaders.add(
                            iri -> {
                              String uri = iri.toString();
                              String json = tenantSchemaJsonByUri.get(uri);
                              if (json != null) {
                                return () ->
                                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
                              }
                              return null;
                            }))
                .schemaMappers(
                    mappers ->
                        mappers.mappings(
                            uri -> uri.startsWith("gsm://archetypes/"),
                            uri -> {
                              if (tenantSchemaJsonByUri.containsKey(uri)) {
                                return uri;
                              }
                              String rest = uri.substring("gsm://archetypes/".length());
                              String name = rest.split("/")[0];
                              return "classpath:schemas/gsm-archetypes/" + name + ".schema.json";
                            })));
  }

  /**
   * Scans the archetype schema for gsm:// $ref URIs that reference tenant archetypes (not on
   * classpath) and preloads them from the database. Returns a map from gsm:// URI to the JSON
   * content of the resolved schema.
   */
  private Map<String, String> collectTenantSchemaMap(JsonNode schema) {
    Map<String, String> result = new HashMap<>();
    collectTenantRefs(schema, result);
    return result;
  }

  private void collectTenantRefs(JsonNode node, Map<String, String> result) {
    if (node == null) {
      return;
    }
    if (node.isObject()) {
      if (node.has("$ref")) {
        String ref = node.get("$ref").asText();
        if (!result.containsKey(ref)) {
          java.util.regex.Matcher m = GSM_URI_PATTERN.matcher(ref);
          if (m.matches()) {
            String title = m.group(1);
            if (!CLASSPATH_ARCHETYPE_TITLES.contains(title)) {
              resolveTenantArchetypeFromDb(ref, title, result);
            }
          }
        }
      }
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        collectTenantRefs(field.getValue(), result);
      }
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        collectTenantRefs(child, result);
      }
    }
  }

  private void resolveTenantArchetypeFromDb(String uri, String title, Map<String, String> result) {
    List<ArchetypeEntity> inEffect =
        archetypeRepository.findAllByStatusIn(
            List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED));
    for (ArchetypeEntity a : inEffect) {
      JsonNode stmt = a.getStatement();
      if (stmt != null && stmt.has("title") && title.equals(stmt.get("title").asText())) {
        result.put(uri, stmt.toString());
        // Recursively collect refs from the resolved schema too
        collectTenantRefs(stmt, result);
        return;
      }
    }
    LOG.warn(
        "Tenant archetype '{}' referenced by gsm:// URI '{}' not found in-effect — "
            + "statement validation may fail if it uses properties from this schema",
        title,
        uri);
  }

  /**
   * Determines whether a validation error pertains to a GSM base schema property. Uses instance
   * location path and the property hint from the validation message.
   */
  private static boolean isBaseSchemaError(ValidationMessage error, Set<String> baseProps) {
    // Check instance location: root property of the failing path
    var instanceLoc = error.getInstanceLocation();
    if (instanceLoc != null && instanceLoc.getNameCount() > 0) {
      String rootProp = instanceLoc.getName(0);
      return baseProps.contains(rootProp);
    }
    // Check the property hint (used by 'required', 'additionalProperties', etc.)
    String property = error.getProperty();
    if (property != null && !property.isEmpty()) {
      return baseProps.contains(property);
    }
    // Root-level structural errors (e.g., type mismatch on root) → GSM
    return true;
  }

  // ======================================================================
  // $gsm:* annotation enforcement on Ascription
  // ======================================================================

  /**
   * Enforces {@code $gsm:*} vocabulary keywords on a statement at authoring time.
   *
   * @param statement the JSON statement payload
   * @param archetype the archetype carrying vocabulary annotations
   * @param definitionId the definition UUID (for uniqueness scoping)
   * @throws RuleViolationException if an annotation constraint is violated
   */
  void enforceAnnotations(JsonNode statement, ArchetypeEntity archetype, UUID definitionId) {
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

      if (!statement.has(propName)) {
        continue;
      }

      JsonNode value = statement.get(propName);

      if (hasAnnotation(propSchema, "$gsm:identityBound")) {
        enforceStatementIdentityBound(propName, value, definitionId);
      }

      if (hasAnnotation(propSchema, "$gsm:unique")) {
        enforceUnique(propName, value, archetype, definitionId);
      }

      if (propSchema.has("$gsm:dataProtection")) {
        dataProtectionService.applyAtRestProtection(
            propSchema.get("$gsm:dataProtection"), propName, (ObjectNode) statement);
      }
    }

    // GSM §8: $gsm:validation — evaluate top-level CEL constraints against
    // statement
    if (archetypeStmt.has("$gsm:validation")) {
      evaluateValidation(archetypeStmt.get("$gsm:validation"), statement);
    }
  }

  private void enforceUnique(
      String propName, JsonNode value, ArchetypeEntity archetype, UUID definitionId) {
    List<AscriptionEntity> inEffect =
        ascriptionRepository.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
            archetype.getId(), GSM_IN_EFFECT, definitionId);

    String valueStr = value.isTextual() ? value.asText() : value.toString();
    for (AscriptionEntity existing : inEffect) {
      JsonNode existingStmt = existing.getStatement();
      if (existingStmt.has(propName)) {
        JsonNode existingVal = existingStmt.get(propName);
        String existingStr =
            existingVal.isTextual() ? existingVal.asText() : existingVal.toString();
        if (valueStr.equals(existingStr)) {
          throw RuleViolationException.of(
              RuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
              propName
                  + " '"
                  + valueStr
                  + "' duplicates ascription "
                  + existing.getId()
                  + " (definition "
                  + existing.getDefinition().getId()
                  + ")",
              "field",
              propName,
              "value",
              valueStr,
              "conflictingAscriptionId",
              existing.getId(),
              "conflictingDefinitionId",
              existing.getDefinition().getId());
        }
      }
    }
  }

  private void enforceStatementIdentityBound(String propName, JsonNode value, UUID definitionId) {
    List<? extends AscriptionEntity> existing = findAllByDefinitionId(definitionId);
    if (existing.isEmpty()) {
      return;
    }

    AscriptionEntity first = existing.getLast(); // ordered by timestamp DESC, so last = earliest
    JsonNode firstStmt = first.getStatement();
    if (!firstStmt.has(propName)) {
      return;
    }

    String newStr = value.isTextual() ? value.asText() : value.toString();
    JsonNode firstVal = firstStmt.get(propName);
    String firstStr = firstVal.isTextual() ? firstVal.asText() : firstVal.toString();

    if (!newStr.equals(firstStr)) {
      throw RuleViolationException.of(
          RuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION,
          "Identity-bound field '"
              + propName
              + "' changed: expected '"
              + firstStr
              + "' but got '"
              + newStr
              + "'",
          "field",
          propName,
          "definitionId",
          definitionId,
          "expectedValue",
          firstStr,
          "actualValue",
          newStr);
    }
  }

  private static boolean hasAnnotation(JsonNode node, String annotation) {
    return node.has(annotation) && node.get(annotation).asBoolean(false);
  }

  // ======================================================================
  // $gsm:validation evaluation at Ascription authoring
  // ======================================================================

  /**
   * Evaluates {@code $gsm:validation} CEL expressions against a statement payload.
   *
   * @param validationNode the JSON array of CEL expression strings
   * @param statement the JSON statement payload ({@code this} in CEL context)
   * @throws RuleViolationException if any expression fails or cannot be parsed
   */
  void evaluateValidation(JsonNode validationNode, JsonNode statement) {
    if (!validationNode.isArray() || validationNode.isEmpty()) {
      return;
    }

    Map<String, Object> statementMap =
        celMapper.convertValue(statement, new TypeReference<Map<String, Object>>() {});

    for (int i = 0; i < validationNode.size(); i++) {
      JsonNode exprNode = validationNode.get(i);
      if (!exprNode.isTextual()) {
        continue;
      }
      String expr = exprNode.asText();
      if (expr.isBlank()) {
        continue;
      }

      try {
        CelValidationResult compileResult = celCompiler.compile(expr);
        if (compileResult.hasError()) {
          throw RuleViolationException.of(
              statementValidationRule(),
              "$gsm:validation[" + i + "] CEL parse error: " + compileResult.getErrorString(),
              "keyword",
              "$gsm:validation",
              "index",
              i);
        }
        CelAbstractSyntaxTree ast = compileResult.getAst();
        CelRuntime.Program program = celRuntime.createProgram(ast);
        Object result = program.eval(Map.of("this", statementMap));

        if (!(result instanceof Boolean b) || !b) {
          throw RuleViolationException.of(
              statementValidationRule(),
              "$gsm:validation["
                  + i
                  + "] constraint failed: expression '"
                  + expr
                  + "' evaluated to "
                  + result,
              "keyword",
              "$gsm:validation",
              "index",
              i,
              "expression",
              expr);
        }
      } catch (RuleViolationException e) {
        throw e; // re-throw our own exceptions
      } catch (Exception e) {
        throw RuleViolationException.of(
            statementValidationRule(),
            "$gsm:validation["
                + i
                + "] evaluation error for expression '"
                + expr
                + "': "
                + e.getMessage(),
            "keyword",
            "$gsm:validation",
            "index",
            i,
            "expression",
            expr);
      }
    }
  }
}
