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
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
  private final ArchetypeAnnotationValidationService annotationValidation;
  private final ArchetypeCompositionValidationService compositionValidation;

  public ArchetypeService(
      ArchetypeRepository archetypeRepo,
      ArchetypePropertyIndexationService indexProvisioning,
      ArchetypeAnnotationValidationService annotationValidation,
      ArchetypeCompositionValidationService compositionValidation) {
    this.archetypeRepo = archetypeRepo;
    this.indexProvisioning = indexProvisioning;
    this.annotationValidation = annotationValidation;
    this.compositionValidation = compositionValidation;
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

    // GSM §5: $ref chain convergence + §8: $gsm:sealed enforcement
    compositionValidation.validateSchemaComposition(statement, this::resolveArchetypeSchema);

    // GSM §8: deep $ref URI policy — reject external URIs everywhere
    annotationValidation.validateRefUriPolicy(statement);

    // GSM §8: $gsm:* annotation well-formedness
    annotationValidation.validateArchetypeAnnotations(
        statement, archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(definition.getId()));

    return new ArchetypeEntity(definition, archetypeRef, statement);
  }

  // ======================================================================
  // Subject type resolution + entity lookup
  // ======================================================================

  /**
   * Resolves an Archetype by id and derives the DefinitionSubjectType from its schema title. Used
   * by the controller to dispatch creation requests.
   *
   * @param archetypeId the archetype UUID
   * @return the resolved archetype with its derived subject type
   * @throws ResourceNotFoundException if no archetype exists with the given id
   * @throws RuleViolationException if the archetype has no title or is rootless
   */
  public ArchetypeResolution resolveForCreation(UUID archetypeId) {
    ArchetypeEntity archetype = findEntityById(archetypeId);
    DefinitionSubjectType type = resolveSubjectType(archetype);
    return new ArchetypeResolution(archetype, type);
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

  /**
   * Finds an in-effect Archetype by its schema title via repository query.
   *
   * @param title the Archetype's schema title
   * @return an optional containing the archetype, or empty if not found
   */
  public java.util.Optional<ArchetypeEntity> findInEffectByTitle(String title) {
    return archetypeRepo.findInEffectByTitle(title);
  }

  /**
   * Batch-fetches Archetypes by their IDs.
   *
   * <p>Part of the explicit-fetch design (see README.md § "Batch fetch pattern").
   *
   * @param ids collection of Archetype IDs to retrieve
   * @return map of ID → ArchetypeEntity; IDs not found in the database are silently absent from the
   *     returned map
   */
  public Map<UUID, ArchetypeEntity> getByIds(Collection<UUID> ids) {
    return archetypeRepo.findAllById(ids).stream()
        .collect(Collectors.toMap(ArchetypeEntity::getId, Function.identity()));
  }

  /**
   * Find an in-effect Archetype by its title. Returns null if no matching in-effect Archetype
   * exists.
   *
   * @param title the Archetype's schema title
   * @return the archetype entity, or {@code null} if not found
   */
  public ArchetypeEntity findInEffectBySchemaTitle(String title) {
    return archetypeRepo.findInEffectByTitle(title).orElse(null);
  }

  private DefinitionSubjectType resolveSubjectType(ArchetypeEntity archetype) {
    JsonNode stmt = archetype.getStatement();
    if (stmt == null || !stmt.has("title")) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          "Cannot derive subject type: archetype has no title: " + archetype.getId(),
          "archetypeId",
          archetype.getId());
    }
    String title = stmt.get("title").asText();

    // Direct match for GSM base archetypes.
    DefinitionSubjectType type = DefinitionSubjectType.fromArchetypeTitle(title);
    if (type != null) {
      return type;
    }

    // Tenant archetype: walk the top-level $ref chain to find the structural base.
    JsonNode refNode = stmt.get("$ref");
    if (refNode == null || !refNode.isTextual()) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          "Rootless archetype '"
              + title
              + "' cannot be used as archetype_id — no structural base "
              + "(top-level $ref to a GSM base required)",
          "title",
          title);
    }

    Set<String> resolvedBases =
        compositionValidation.resolveGsmBases(
            refNode.asText(), title, this::resolveArchetypeSchema);

    if (resolvedBases.isEmpty()) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          "Rootless archetype '"
              + title
              + "' cannot be used as archetype_id — $ref chain does not "
              + "converge to any GSM base",
          "title",
          title);
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

    String baseName = resolvedBases.iterator().next();
    type = DefinitionSubjectType.fromArchetypeTitle(baseName);
    if (type == null) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          "Cannot map structural base '" + baseName + "' to a DefinitionSubjectType",
          "baseName",
          baseName);
    }
    return type;
  }

  // ---- Lifecycle descriptors ----

  @Override
  public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
    JsonNode stmt = entity.getStatement();
    if (stmt == null || !stmt.has("title")) return Map.of();
    return Map.of("title", stmt.get("title").asText());
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
    // Statement is immutable and was validated at creation — title is guaranteed
    // non-null/non-blank.
    String title = entity.getStatement().get("title").asText();
    AscriptionUniquenessValidationService.validatePropertyAcrossDefinitions(
        getSubjectType(),
        "title",
        title,
        entity.getDefinition().getId(),
        archetypeRepo.findAllByStatusIn(
            EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)));
  }

  // ---- Lifecycle hooks ----

  @Override
  public void onActivation(AscriptionEntity entity) {
    if (entity instanceof ArchetypeEntity archetypeEntity) {
      // GSM §5: strict schema composition — all intermediaries must be in-effect.
      compositionValidation.validateSchemaComposition(
          archetypeEntity.getStatement(), true, this::resolveArchetypeSchema);
      // $ref URI policy is NOT re-checked: statement is immutable (validated at
      // creation).
      indexProvisioning.provisionIndexes(
          archetypeEntity, () -> resolveSubjectType(archetypeEntity).name().toLowerCase());
    }
  }

  @Override
  public void onDeactivation(AscriptionEntity entity) {
    if (entity instanceof ArchetypeEntity archetypeEntity) {
      indexProvisioning.deprovisionIndexes(
          archetypeEntity, () -> resolveSubjectType(archetypeEntity).name().toLowerCase());
    }
  }

  // ========================================================================
  // AllOf chain validation
  // ========================================================================

  // ---- Public descendant resolution API ----

  /**
   * Returns the set of all ancestor Archetype titles reachable through the schema composition chain
   * (top-level {@code $ref} and {@code allOf} entries) of the given archetype. Includes the
   * archetype's own title if present. Does not include titles that cannot be resolved at the time
   * of the call (lenient mode). GSM base titles are included when reached.
   *
   * <p><b>Ordering guarantee:</b> the returned {@link LinkedHashSet} preserves insertion order,
   * which is nearest-ancestor-first (depth-first traversal of the composition chain). The
   * archetype's own title, when present, is always the first element. Callers (e.g., {@code
   * NormService} governance chain validation) may rely on this ordering.
   *
   * @param archetypeId the ascription ID of the archetype to inspect
   * @return the set of ancestor titles in nearest-ancestor-first order (may be empty for rootless
   *     archetypes)
   */
  public Set<String> getAncestorTitles(UUID archetypeId) {
    ArchetypeEntity archetype = findEntityById(archetypeId);
    JsonNode schema = archetype.getStatement();
    String title = schema.has("title") ? schema.get("title").asText() : null;

    Set<String> ancestors = new LinkedHashSet<>();
    if (title != null) {
      ancestors.add(title);
    }

    Set<String> visited = new HashSet<>();
    if (title != null) {
      visited.add(title);
    }

    collectAncestorTitles(schema, ancestors, visited);
    return ancestors;
  }

  /**
   * Checks whether the given archetype is a descendant (via schema composition chain) of an
   * ancestor identified by title. Returns true if the archetype's own title matches, or if any
   * ancestor matches.
   *
   * @param archetypeId the ascription ID of the archetype to check
   * @param ancestorTitle the title of the potential ancestor
   * @return true if the archetype descends from the ancestor
   */
  public boolean isDescendantOf(UUID archetypeId, String ancestorTitle) {
    return getAncestorTitles(archetypeId).contains(ancestorTitle);
  }

  private void collectAncestorTitles(JsonNode schema, Set<String> ancestors, Set<String> visited) {
    // Walk top-level $ref (base extension chain)
    if (schema.has("$ref") && schema.get("$ref").isTextual()) {
      String ref = schema.get("$ref").asText();
      String refTitle = ArchetypeParsingService.extractTitleFromRef(ref);
      if (refTitle != null && visited.add(refTitle)) {
        ancestors.add(refTitle);
        if (!ArchetypeParsingService.isGsmBaseTitle(refTitle)) {
          JsonNode intermediateSchema = resolveArchetypeSchema(refTitle);
          if (intermediateSchema != null) {
            collectAncestorTitles(intermediateSchema, ancestors, visited);
          }
        }
      }
    }

    // Walk allOf entries (facets)
    JsonNode allOf = schema.get("allOf");
    if (allOf != null && allOf.isArray()) {
      for (JsonNode entry : allOf) {
        if (!entry.has("$ref")) {
          continue;
        }
        String ref = entry.get("$ref").asText();
        String refTitle = ArchetypeParsingService.extractTitleFromRef(ref);
        if (refTitle == null || !visited.add(refTitle)) {
          continue;
        }
        ancestors.add(refTitle);
        if (ArchetypeParsingService.isGsmBaseTitle(refTitle)) {
          continue;
        }
        JsonNode intermediateSchema = resolveArchetypeSchema(refTitle);
        if (intermediateSchema == null) {
          continue; // lenient: unresolvable intermediary skipped
        }
        collectAncestorTitles(intermediateSchema, ancestors, visited);
      }
    }
  }

  // Schema composition validation is delegated to
  // ArchetypeCompositionValidationService.

  JsonNode resolveArchetypeSchema(String title) {
    return archetypeRepo.findInEffectByTitle(title).map(ArchetypeEntity::getStatement).orElse(null);
  }
}
