package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * GSM Archetype ascription service.
 *
 * <p>Manages lifecycle and persistence of {@link ArchetypeEntity} ascriptions including schema
 * composition validation ({@code $ref} chain + {@code allOf} facets), {@code $gsm:*} annotation
 * well-formedness, subject type resolution, and vocabulary-driven index provisioning.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class ArchetypeService implements AscriptionSubtypeService<ArchetypeEntity> {

  public record ArchetypeResolution(ArchetypeEntity archetype, DefinitionSubjectType subjectType) {}

  private final ArchetypeRepository archetypeRepo;
  private final ArchetypePropertyIndexationService indexProvisioning;
  private final ArchetypeIdentityValidationService identityValidation;
  private final ArchetypeAnnotationValidationService annotationValidation;
  private final ArchetypeCompositionValidationService compositionValidation;
  private final ArchetypeSchemaResolverService schemaResolver;
  private final JsonSchemaPositionWalker schemaPositionWalker;

  public ArchetypeService(
      ArchetypeRepository archetypeRepo,
      ArchetypePropertyIndexationService indexProvisioning,
      ArchetypeIdentityValidationService identityValidation,
      ArchetypeAnnotationValidationService annotationValidation,
      ArchetypeCompositionValidationService compositionValidation,
      ArchetypeSchemaResolverService schemaResolver,
      JsonSchemaPositionWalker schemaPositionWalker) {
    this.archetypeRepo = archetypeRepo;
    this.indexProvisioning = indexProvisioning;
    this.identityValidation = identityValidation;
    this.annotationValidation = annotationValidation;
    this.compositionValidation = compositionValidation;
    this.schemaResolver = schemaResolver;
    this.schemaPositionWalker = schemaPositionWalker;
  }

  /**
   * Returns the Archetype's resolved {@code properties} node — own declarations composed with
   * everything inherited through its {@code $ref} chain and {@code allOf} facets (GSM §11.1).
   *
   * @param archetype the resolved Archetype
   * @return the resolved {@code properties} node
   */
  public JsonNode resolvedProperties(ArchetypeEntity archetype) {
    return schemaResolver.resolvedProperties(archetype);
  }

  @Override
  public DefinitionSubjectType getSubjectType() {
    return DefinitionSubjectType.ARCHETYPE;
  }

  @Override
  public AbstractAscriptionRepository<ArchetypeEntity> getRepository() {
    return archetypeRepo;
  }

  @Override
  public ArchetypeEntity create(
      DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
    if (statement == null || !statement.isObject()) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          "Archetype statement must be a JSON object",
          "field",
          "statement");
    }

    identityValidation.validate(statement);

    // GSM §8: schema-position $ref URI policy and authored $dynamicRef prohibition
    annotationValidation.validateRefUriPolicy(statement);

    // GSM §5: $ref chain convergence + §8: $gsm:sealed enforcement
    compositionValidation.validateSchemaComposition(statement, this::resolveArchetypeSchema);

    // GSM §8: $gsm:* annotation well-formedness
    annotationValidation.validateArchetypeAnnotations(
        statement, archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(definition.getId()));

    return new ArchetypeEntity(definition, archetypeRef, statement);
  }

  // ======================================================================
  // Subject type resolution + entity lookup
  // ======================================================================

  /**
   * Resolves an Archetype URI for the typing role at creation time.
   *
   * @param archetypeUri the complete version-pinned {@code gsmarc://} Archetype URI
   * @return the resolved archetype with its derived subject type
   * @throws RuleViolationException if the URI does not resolve or is not eligible for CREATE
   */
  public ArchetypeResolution resolveForCreation(String archetypeUri) {
    return resolveInEffectArchetypeUri(archetypeUri);
  }

  /** Resolves one Archetype URI and asserts the resolved Ascription is in effect for typing. */
  public ArchetypeResolution resolveInEffectArchetypeUri(String archetypeUri) {
    ArchetypeEntity archetype = resolveArchetypeUri(archetypeUri, "archetype");
    validateTypingEligibility(archetype, archetypeUri);
    return new ArchetypeResolution(archetype, resolveSubjectType(archetype));
  }

  /**
   * Resolves one Archetype URI for the typing-filter role in read queries.
   *
   * <p>Unlike {@link #resolveInEffectArchetypeUri}, no in-effect eligibility is enforced: any
   * resolvable (post-approval) status is queryable, since existing ascriptions typed by an
   * Archetype remain valid and findable regardless of that Archetype's later status.
   *
   * @param archetypeUri the complete version-pinned {@code gsmarc://} Archetype URI
   * @return the resolved archetype with its derived subject type
   * @throws RuleViolationException if the URI does not resolve
   */
  public ArchetypeResolution resolveForQuery(String archetypeUri) {
    ArchetypeEntity archetype = resolveArchetypeUri(archetypeUri, "archetype");
    return new ArchetypeResolution(archetype, resolveSubjectType(archetype));
  }

  private void validateTypingEligibility(ArchetypeEntity archetype, Object requestedIdentity) {
    if (archetype.getStatus() != AscriptionStatusType.ACTIVE
        && archetype.getStatus() != AscriptionStatusType.DEPRECATED) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_IN_EFFECT,
          "Typing Archetype is not eligible for CREATE: "
              + requestedIdentity
              + " has status "
              + archetype.getStatus(),
          "archetype",
          requestedIdentity,
          "status",
          archetype.getStatus());
    }
  }

  /**
   * Resolves an authored Archetype URI to the single Ascription it dereferences to.
   *
   * <p>This method owns URI grammar and resolution only — the returned Ascription may be in any
   * post-approval status, including {@code RETIRED}, because statements are immutable and existing
   * references must keep resolving. Lifecycle eligibility remains with the generic Referee
   * state-machine validation performed after subtype creation.
   *
   * @param archetypeUri the complete version-pinned {@code gsmarc://} Archetype URI
   * @param surface the authored field name used in diagnostics
   * @return the Archetype Ascription the URI resolves to
   * @throws RuleViolationException if the URI is malformed or does not resolve
   */
  public ArchetypeEntity resolveArchetypeUri(String archetypeUri, String surface) {
    try {
      ArchetypeParsingService.parseIdentity(archetypeUri);
    } catch (IllegalArgumentException exception) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_REF_NORM,
          "Malformed Archetype $id for '" + surface + "': " + archetypeUri,
          "surface",
          surface,
          "value",
          archetypeUri);
    }
    return archetypeRepo
        .findResolvableByUri(archetypeUri)
        .orElseThrow(
            () ->
                RuleViolationException.of(
                    AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY,
                    "Archetype reference '"
                        + surface
                        + "' does not resolve to a governed Archetype URI: "
                        + archetypeUri,
                    "surface",
                    surface,
                    "value",
                    archetypeUri));
  }

  /** Enforces the Archetype-valued Referee policy for CREATE and SUBMIT. */
  public void validateRefereeEligibility(ArchetypeEntity archetype, String surface) {
    if (archetype.getStatus() != AscriptionStatusType.APPROVED
        && archetype.getStatus() != AscriptionStatusType.ACTIVE) {
      throw RuleViolationException.of(
          AscriptionStatusTransitionRuleType
              .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          "Archetype Referee '"
              + surface
              + "' ("
              + archetype.getId()
              + ") is "
              + archetype.getStatus()
              + "; creation requires one of [APPROVED, ACTIVE]",
          "refereeLabel",
          surface,
          "status",
          archetype.getStatus(),
          "allowedStatuses",
          List.of(AscriptionStatusType.APPROVED, AscriptionStatusType.ACTIVE));
    }
  }

  /**
   * Finds an Archetype entity by its ascription id.
   *
   * @param id the ascription UUID
   * @return the archetype entity
   * @throws ResourceNotFoundException if no archetype exists with the given id
   */
  public ArchetypeEntity findEntityById(UUID id) {
    return archetypeRepo
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(PrimitiveType.ARCHETYPE, id));
  }

  private DefinitionSubjectType resolveSubjectType(ArchetypeEntity archetype) {
    Optional<DefinitionSubjectType> subjectType = findSubjectType(archetype);
    if (subjectType.isPresent()) {
      return subjectType.get();
    }
    JsonNode stmt = archetype.getStatement();
    String title = stmt.get("title").asText();
    throw RuleViolationException.of(
        AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
        "Rootless archetype '"
            + title
            + "' cannot be used as archetype_id — "
            + (stmt.path("$ref").isTextual()
                ? "$ref chain does not converge to any GSM base"
                : "no structural base (top-level $ref to a GSM base required)"),
        "title",
        title);
  }

  /**
   * Derives the {@link DefinitionSubjectType} this Archetype <em>confers on the Ascriptions it
   * types</em> — read off the GSM base its top-level {@code $ref} chain converges to, and used to
   * name the primitive table those Ascriptions land in. This is not the subject type of the
   * Archetype itself, which is always {@code ARCHETYPE} (see {@link #getSubjectType()}).
   *
   * <p>Empty for a rootless Archetype (GSM §9.2): converging to no base, it types no Ascription and
   * so confers nothing. It remains a valid first-class Archetype in the qualifier and data roles.
   */
  private Optional<DefinitionSubjectType> findSubjectType(ArchetypeEntity archetype) {
    JsonNode stmt = archetype.getStatement();
    if (stmt == null || !stmt.has("title")) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          "Cannot derive subject type: archetype has no title: " + archetype.getId(),
          "archetypeId",
          archetype.getId());
    }
    String title = stmt.get("title").asText();

    String id = stmt.has("$id") && stmt.get("$id").isTextual() ? stmt.get("$id").asText() : null;

    // Direct match only for the eight exact GSM seed identities.
    if (id != null && ArchetypeParsingService.isGsmBaseId(id)) {
      return Optional.of(
          DefinitionSubjectType.fromArchetypeTitle(
              ArchetypeParsingService.parseIdentity(id).title()));
    }

    // Tenant archetype: walk the top-level $ref chain to find the structural base.
    JsonNode refNode = stmt.get("$ref");
    if (refNode == null || !refNode.isTextual()) {
      return Optional.empty();
    }

    Set<String> resolvedBases =
        compositionValidation.resolveGsmBases(refNode.asText(), id, this::resolveArchetypeSchema);

    if (resolvedBases.isEmpty()) {
      return Optional.empty();
    }
    // resolvedBases.size() > 1 is already rejected by validateSchemaComposition
    // at authoring time; defensive check here for safety.
    if (resolvedBases.size() > 1) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_EXCLUSIVE_BASE_CONVERGENCE,
          "Archetype '" + title + "' $ref chain converges to multiple GSM bases: " + resolvedBases,
          "title",
          title,
          "resolvedBases",
          resolvedBases);
    }

    String baseId = resolvedBases.iterator().next();
    DefinitionSubjectType type =
        ArchetypeParsingService.isGsmBaseId(baseId)
            ? DefinitionSubjectType.fromArchetypeTitle(
                ArchetypeParsingService.parseIdentity(baseId).title())
            : null;
    if (type == null) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          "Cannot map structural base '" + baseId + "' to a DefinitionSubjectType",
          "baseId",
          baseId);
    }
    return Optional.of(type);
  }

  // ---- Lifecycle descriptors ----

  @Override
  public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
    JsonNode stmt = entity.getStatement();
    if (stmt == null
        || !stmt.has("title")
        || !stmt.get("title").isTextual()
        || !stmt.has("$id")
        || !stmt.get("$id").isTextual()) {
      return Map.of();
    }
    ArchetypeParsingService.ArchetypeIdentity identity =
        ArchetypeParsingService.parseIdentity(stmt.get("$id").asText());
    return Map.of("stem", identity.stem(), "title", stmt.get("title").asText());
  }

  @Override
  public void validateCreationUniqueness(AscriptionEntity entity) {
    JsonNode statement = entity.getStatement();
    ArchetypeParsingService.ArchetypeIdentity identity =
        ArchetypeParsingService.parseIdentity(statement.get("$id").asText());
    UUID definitionId = entity.getDefinition().getId();

    archetypeRepo.flush();
    UUID ownerDefinitionId = archetypeRepo.acquireDefinitionIdByStem(identity.stem(), definitionId);
    if (!definitionId.equals(ownerDefinitionId)) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_STEM_UNIQUENESS_ACROSS_DEFINITIONS,
          "Archetype identity stem '"
              + identity.stem()
              + "' is permanently owned by Definition "
              + ownerDefinitionId,
          "stem",
          identity.stem(),
          "definitionId",
          definitionId,
          "ownerDefinitionId",
          ownerDefinitionId);
    }

    Span.current()
        .addEvent(
            "gsm.archetype.stem.owner.acquired",
            Attributes.builder()
                .put("gsm.archetype.stem", identity.stem())
                .put("gsm.definition.id", definitionId.toString())
                .build());
  }

  @Override
  public void validateStatusTransition(
      AscriptionEntity entity,
      AscriptionStatusType currentStatus,
      AscriptionStatusType targetStatus) {
    if (currentStatus != AscriptionStatusType.DRAFT
        || targetStatus != AscriptionStatusType.PROPOSED) {
      return;
    }

    UUID definitionId = entity.getDefinition().getId();
    int expectedVersion =
        archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(definitionId).stream()
                .mapToInt(AscriptionEntity::getVersion)
                .filter(version -> version > 0)
                .max()
                .orElse(0)
            + 1;
    int candidateVersion =
        ArchetypeParsingService.parseIdentity(entity.getStatement().get("$id").asText()).version();
    if (candidateVersion != expectedVersion) {
      throw RuleViolationException.of(
          AscriptionStatusTransitionRuleType
              .ASCRIPTION_STATUS_TRANSITION_ARCHETYPE_CANDIDATE_VERSION,
          "Archetype candidate $id version "
              + candidateVersion
              + " must equal next Definition version "
              + expectedVersion,
          "definitionId",
          definitionId,
          "candidateVersion",
          candidateVersion,
          "expectedVersion",
          expectedVersion);
    }
  }

  @Override
  public void validatePersistedStatusTransition(
      AscriptionEntity entity,
      AscriptionStatusType previousStatus,
      AscriptionStatusType persistedStatus) {
    if (previousStatus != AscriptionStatusType.PROPOSED
        || persistedStatus != AscriptionStatusType.APPROVED) {
      return;
    }

    int authoredVersion =
        ArchetypeParsingService.parseIdentity(entity.getStatement().get("$id").asText()).version();
    int materializedVersion = entity.getVersion();
    if (materializedVersion != authoredVersion) {
      throw RuleViolationException.of(
          AscriptionStatusTransitionRuleType
              .ASCRIPTION_STATUS_TRANSITION_ARCHETYPE_VERSION_RECONCILIATION,
          "Archetype materialized version "
              + materializedVersion
              + " does not match authored $id version "
              + authoredVersion,
          "ascriptionId",
          entity.getId(),
          "materializedVersion",
          materializedVersion,
          "authoredVersion",
          authoredVersion);
    }
  }

  @Override
  public List<Map.Entry<AscriptionEntity, String>> getRefereeReferences(AscriptionEntity entity) {
    if (!(entity instanceof ArchetypeEntity archetype)) {
      throw new IllegalArgumentException(
          "Expected ArchetypeEntity, got "
              + (entity == null ? "null" : entity.getClass().getSimpleName()));
    }
    if (archetype.getStatement() == null) {
      throw new IllegalArgumentException("Archetype statement must not be null");
    }

    List<Map.Entry<AscriptionEntity, String>> references = new ArrayList<>();
    schemaPositionWalker.walk(
        archetype.getStatement(),
        (schema, pointer) -> {
          JsonNode refNode = schema.get("$ref");
          if (refNode == null || !refNode.isTextual() || refNode.asText().startsWith("#")) {
            return;
          }

          String ref = refNode.asText();
          ArchetypeEntity target =
              archetypeRepo
                  .findResolvableByUri(ref)
                  .orElseThrow(
                      () ->
                          RuleViolationException.of(
                              AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY,
                              "Cannot resolve Archetype $ref '" + ref + "'",
                              "ref",
                              ref,
                              "site",
                              pointer + "/$ref"));
          references.add(Map.entry(target, pointer + "/$ref"));
        });
    return List.copyOf(references);
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

  // ---- Lifecycle hooks ----

  @Override
  public void onActivation(AscriptionEntity entity) {
    if (entity instanceof ArchetypeEntity archetypeEntity) {
      // Revalidate immutable composition before provisioning indexes.
      compositionValidation.validateSchemaComposition(
          archetypeEntity.getStatement(), this::resolveArchetypeSchema);
      // $ref URI policy is NOT re-checked: statement is immutable (validated at
      // creation).
      // Rootless: confers no subject type, so there is no typed population to index (GSM §11.1).
      findSubjectType(archetypeEntity)
          .ifPresent(
              type ->
                  indexProvisioning.provisionIndexes(
                      archetypeEntity, () -> type.name().toLowerCase()));
    }
  }

  @Override
  public void onDeactivation(AscriptionEntity entity) {
    if (entity instanceof ArchetypeEntity archetypeEntity) {
      findSubjectType(archetypeEntity)
          .ifPresent(
              type ->
                  indexProvisioning.deprovisionIndexes(
                      archetypeEntity, () -> type.name().toLowerCase()));
    }
  }

  /**
   * Provisions indexes for the GSM base Archetypes.
   *
   * <p>The bases are inserted straight to ACTIVE by the bootstrap seed runner, so they never pass
   * through {@link #onActivation}, the only other provisioning trigger. Tenant Archetypes need no
   * equivalent sweep: an Archetype's resolved composition chain is fixed for its whole lifetime
   * (GSM §11.1), so what {@link #onActivation} provisions stays correct. DDL is idempotent.
   *
   * @return the number of base Archetypes provisioned
   */
  public int reconcileBaseArchetypeIndexes() {
    int reconciled = 0;
    for (String baseUri : ArchetypeParsingService.gsmBaseIds()) {
      ArchetypeEntity base = archetypeRepo.findResolvableByUri(baseUri).orElse(null);
      if (base != null) {
        indexProvisioning.provisionIndexes(
            base, () -> resolveSubjectType(base).name().toLowerCase());
        reconciled++;
      }
    }
    return reconciled;
  }

  // ========================================================================
  // AllOf chain validation
  // ========================================================================

  // ---- Descendant resolution API (package-private, test-covered) ----

  /** Returns Archetype family stems in nearest-ancestor-first order. */
  Set<String> getAncestorStems(UUID archetypeId) {
    ArchetypeEntity archetype = findEntityById(archetypeId);
    JsonNode schema = archetype.getStatement();

    Set<String> ancestors = new LinkedHashSet<>();
    Set<String> visited = new HashSet<>();
    if (schema.has("$id") && schema.get("$id").isTextual()) {
      String id = schema.get("$id").asText();
      visited.add(id);
      ancestors.add(ArchetypeParsingService.parseIdentity(id).stem());
    }

    collectAncestorStems(schema, ancestors, visited);
    return ancestors;
  }

  boolean isDescendantOf(UUID archetypeId, String ancestorStem) {
    return getAncestorStems(archetypeId).contains(ancestorStem);
  }

  private void collectAncestorStems(JsonNode schema, Set<String> ancestors, Set<String> visited) {
    // Walk top-level $ref (base extension chain)
    if (schema.has("$ref") && schema.get("$ref").isTextual()) {
      collectAncestorStem(schema.get("$ref").asText(), ancestors, visited);
    }

    // Walk allOf entries (facets)
    JsonNode allOf = schema.get("allOf");
    if (allOf != null && allOf.isArray()) {
      for (JsonNode entry : allOf) {
        if (!entry.has("$ref")) {
          continue;
        }
        collectAncestorStem(entry.get("$ref").asText(), ancestors, visited);
      }
    }
  }

  private void collectAncestorStem(String ref, Set<String> ancestors, Set<String> visited) {
    if (ref.startsWith("#") || !visited.add(ref)) {
      return;
    }

    String stem = ArchetypeParsingService.parseIdentity(ref).stem();
    if (ArchetypeParsingService.isGsmBaseId(ref)) {
      ancestors.add(stem);
      return;
    }

    JsonNode intermediateSchema = resolveArchetypeSchema(ref);
    if (intermediateSchema != null) {
      ancestors.add(stem);
      collectAncestorStems(intermediateSchema, ancestors, visited);
    }
  }

  // Schema composition validation is delegated to
  // ArchetypeCompositionValidationService.

  JsonNode resolveArchetypeSchema(String uri) {
    return schemaResolver.resolveUri(uri);
  }
}
