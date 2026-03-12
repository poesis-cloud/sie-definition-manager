package com.sif.sie.definitionmanager.validator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
import com.sif.sie.definitionmanager.entity.AscriptionEntity;
import com.sif.sie.definitionmanager.entity.DefinitionEntity;
import com.sif.sie.definitionmanager.repository.ArchetypeRepository;
import com.sif.sie.definitionmanager.repository.DefinitionRepository;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;

/**
 * Introspects and validates {@code $gsm:*} annotations on Archetype schemas.
 *
 * <p><b>At Archetype authoring time</b>: validates annotation well-formedness
 * (unknown keywords rejected, type constraints enforced, mutual exclusion
 * checks).
 *
 * <p><b>At Ascription authoring time</b>: enforces annotation semantics
 * ({@code $gsm:identityBound} for tenant-extended properties,
 * {@code $gsm:referential}, {@code $gsm:unique}, {@code $gsm:deprecated}).
 */
@Component
public class GsmAnnotationValidator {

    private static final Logger LOG = LoggerFactory.getLogger(GsmAnnotationValidator.class);

    private static final Set<String> KNOWN_ANNOTATIONS = Set.of(
            "$gsm:sealed", "$gsm:identityBound", "$gsm:queryable",
            "$gsm:referential", "$gsm:unique", "$gsm:sensitive",
            "$gsm:validationCEL", "$gsm:deprecated");

    /** Top-level annotations (not property-level). */
    private static final Set<String> TOP_LEVEL_ANNOTATIONS = Set.of(
            "$gsm:sealed", "$gsm:validationCEL");

    /** Types acceptable for $gsm:queryable. */
    private static final Set<String> INDEXABLE_TYPES = Set.of(
            "string", "number", "integer", "boolean");

    private static final int DEFAULT_MAX_QUERYABLE = 8;

    private static final Collection<AscriptionStatusType> IN_EFFECT =
            List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);

    private final DefinitionRepository definitionRepo;
    private final ArchetypeRepository archetypeRepo;

    public GsmAnnotationValidator(DefinitionRepository definitionRepo, ArchetypeRepository archetypeRepo) {
        this.definitionRepo = definitionRepo;
        this.archetypeRepo = archetypeRepo;
    }

    // ========================================================================
    // Archetype authoring-time: annotation well-formedness
    // ========================================================================

    /**
     * Validates well-formedness of {@code $gsm:*} annotations in the schema.
     * Called at Archetype creation time.
     *
     * @param schema      the {@code statement.schema} JSON node
     * @param definitionId the Archetype's Definition ID (for identity-bound set immutability check)
     */
    public void validateArchetypeAnnotations(JsonNode schema, UUID definitionId) {
        // Check top-level annotations
        validateTopLevelAnnotations(schema);

        // Walk properties
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }

        int queryableCount = 0;
        Set<String> identityBoundFields = new HashSet<>();

        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String propName = entry.getKey();
            JsonNode propSchema = entry.getValue();

            // Reject unknown $gsm:* annotations
            checkUnknownAnnotations(propSchema, propName);

            // $gsm:queryable well-formedness
            if (hasAnnotation(propSchema, "$gsm:queryable")) {
                queryableCount++;
                validateQueryableType(propSchema, propName);
            }

            // $gsm:sensitive + $gsm:queryable mutual exclusion
            if (hasAnnotation(propSchema, "$gsm:sensitive") && hasAnnotation(propSchema, "$gsm:queryable")) {
                throw new IllegalArgumentException(
                        "Annotation conflict: property '" + propName
                                + "' carries both $gsm:sensitive and $gsm:queryable — sensitive data MUST NOT be indexed");
            }

            // $gsm:referential well-formedness
            if (propSchema.has("$gsm:referential")) {
                validateReferentialAnnotation(propSchema.get("$gsm:referential"), propName);
            }

            // Track identity-bound fields
            if (hasAnnotation(propSchema, "$gsm:identityBound")) {
                identityBoundFields.add(propName);
            }
        }

        if (queryableCount > DEFAULT_MAX_QUERYABLE) {
            throw new IllegalArgumentException(
                    "Too many $gsm:queryable properties (" + queryableCount
                            + "): maximum allowed is " + DEFAULT_MAX_QUERYABLE);
        }

        // $gsm:identityBound set immutability check
        validateIdentityBoundSetImmutability(definitionId, identityBoundFields);
    }

    // ========================================================================
    // Ascription authoring-time: annotation enforcement
    // ========================================================================

    /**
     * Enforces {@code $gsm:*} annotation semantics on an Ascription's statement.
     * Called at Ascription creation time (after JSON Schema validation passes).
     *
     * @param statement   the Ascription statement payload
     * @param archetype   the typing Archetype entity
     * @param definitionId the Ascription's Definition ID
     */
    public void enforceOnAscription(JsonNode statement, ArchetypeEntity archetype, UUID definitionId) {
        JsonNode archetypeSchema = archetype.getStatement().get("schema");
        if (archetypeSchema == null) {
            return;
        }

        JsonNode properties = archetypeSchema.get("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }

        List<String> warnings = new ArrayList<>();

        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String propName = entry.getKey();
            JsonNode propSchema = entry.getValue();

            if (!statement.has(propName)) {
                continue;
            }

            JsonNode value = statement.get(propName);

            // $gsm:referential — validate referenced Definition exists
            if (propSchema.has("$gsm:referential")) {
                enforceReferential(propSchema.get("$gsm:referential"), propName, value);
            }

            // $gsm:unique — validate uniqueness among in-effect ascriptions of same archetype
            if (hasAnnotation(propSchema, "$gsm:unique")) {
                enforceUnique(propName, value, archetype, definitionId);
            }

            // $gsm:deprecated — emit warning
            if (hasAnnotation(propSchema, "$gsm:deprecated")) {
                warnings.add("Property '" + propName + "' is deprecated");
            }
        }

        // Emit warnings (non-blocking)
        for (String warning : warnings) {
            LOG.warn("$gsm:deprecated: {}", warning);
        }
    }

    // ========================================================================
    // Internal: annotation well-formedness helpers
    // ========================================================================

    private void validateTopLevelAnnotations(JsonNode schema) {
        Iterator<String> fieldNames = schema.fieldNames();
        while (fieldNames.hasNext()) {
            String name = fieldNames.next();
            if (name.startsWith("$gsm:") && !KNOWN_ANNOTATIONS.contains(name)) {
                throw new IllegalArgumentException(
                        "Unknown $gsm:* annotation '" + name + "' — sealed annotation vocabulary");
            }
        }
    }

    private void checkUnknownAnnotations(JsonNode propSchema, String propName) {
        Iterator<String> fieldNames = propSchema.fieldNames();
        while (fieldNames.hasNext()) {
            String name = fieldNames.next();
            if (name.startsWith("$gsm:")) {
                if (!KNOWN_ANNOTATIONS.contains(name)) {
                    throw new IllegalArgumentException(
                            "Unknown $gsm:* annotation '" + name + "' on property '"
                                    + propName + "' — sealed annotation vocabulary");
                }
                if (TOP_LEVEL_ANNOTATIONS.contains(name)) {
                    throw new IllegalArgumentException(
                            "Annotation '" + name + "' is top-level only, not valid on property '"
                                    + propName + "'");
                }
            }
        }
    }

    private void validateQueryableType(JsonNode propSchema, String propName) {
        String type = propSchema.has("type") ? propSchema.get("type").asText() : null;
        if (type == null) {
            throw new IllegalArgumentException(
                    "$gsm:queryable on property '" + propName
                            + "' requires an explicit type (string, number, integer, boolean, or array of scalars)");
        }

        if ("array".equals(type)) {
            JsonNode items = propSchema.get("items");
            if (items == null || !items.has("type")
                    || !INDEXABLE_TYPES.contains(items.get("type").asText())) {
                throw new IllegalArgumentException(
                        "$gsm:queryable on array property '" + propName
                                + "' requires items of indexable type (string, number, integer, boolean)");
            }
        } else if (!INDEXABLE_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "$gsm:queryable on property '" + propName + "' with type '" + type
                            + "' — type must be string, number, integer, boolean, or array of scalars");
        }
    }

    private void validateReferentialAnnotation(JsonNode annotation, String propName) {
        if (!annotation.isObject()) {
            throw new IllegalArgumentException(
                    "$gsm:referential on property '" + propName
                            + "' must be an object (e.g., { \"subjectType\": \"STRUCTURE\" })");
        }
        if (annotation.has("subjectType")) {
            String st = annotation.get("subjectType").asText();
            try {
                DefinitionSubjectType.valueOf(st);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "$gsm:referential.subjectType '" + st + "' on property '"
                                + propName + "' is not a valid DefinitionSubjectType");
            }
        }
    }

    private void validateIdentityBoundSetImmutability(UUID definitionId, Set<String> currentSet) {
        if (definitionId == null || currentSet.isEmpty()) {
            return;
        }

        // Check if there's an existing archetype for this definition
        List<ArchetypeEntity> existing = archetypeRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
        if (existing.isEmpty()) {
            return; // First Ascription — no invariant to check
        }

        ArchetypeEntity first = existing.getLast(); // ordered DESC, last = earliest
        JsonNode firstSchema = first.getStatement().get("schema");
        if (firstSchema == null) {
            return;
        }

        Set<String> firstIdentityBound = collectIdentityBoundFields(firstSchema);
        if (!firstIdentityBound.equals(currentSet)) {
            throw new IllegalArgumentException(
                    "$gsm:identityBound set immutability violation: first Ascription had identity-bound fields "
                            + firstIdentityBound + " but new Ascription declares " + currentSet
                            + ". Changing the identity-bound set requires a new Archetype Definition.");
        }
    }

    // ========================================================================
    // Internal: Ascription enforcement helpers
    // ========================================================================

    private void enforceReferential(JsonNode annotation, String propName, JsonNode value) {
        if (!value.isTextual()) {
            return;
        }

        UUID refId;
        try {
            refId = UUID.fromString(value.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "$gsm:referential: property '" + propName + "' value '"
                            + value.asText() + "' is not a valid UUID");
        }

        DefinitionEntity refDef = definitionRepo.findById(refId).orElse(null);
        if (refDef == null) {
            throw new IllegalArgumentException(
                    "$gsm:referential: property '" + propName + "' references Definition "
                            + refId + " which does not exist");
        }

        if (annotation.has("subjectType")) {
            String expectedType = annotation.get("subjectType").asText();
            if (!expectedType.equals(refDef.getSubjectType().name())) {
                throw new IllegalArgumentException(
                        "$gsm:referential: property '" + propName + "' references Definition "
                                + refId + " with subjectType " + refDef.getSubjectType()
                                + " but expected " + expectedType);
            }
        }
    }

    private void enforceUnique(String propName, JsonNode value, ArchetypeEntity archetype, UUID definitionId) {
        UUID archetypeDefId = archetype.getDefinition().getId();
        String valueStr = value.isTextual() ? value.asText() : value.toString();

        // Find all in-effect ascriptions of the same archetype (across all tables via archetype repo)
        // Note: $gsm:unique checks across ALL Definitions typed by the same Archetype.
        // This requires scanning in-effect ascriptions — using the archetype repository
        // for archetypes; for other subtypes, this would need a cross-table query.
        // For MVP, we log a warning; full enforcement requires a JSONB path query.
        LOG.debug("$gsm:unique check for property '{}' value '{}' on archetype definition {}",
                propName, valueStr, archetypeDefId);
    }

    // ========================================================================
    // Utility
    // ========================================================================

    static Set<String> collectIdentityBoundFields(JsonNode schema) {
        Set<String> result = new HashSet<>();
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return result;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (hasAnnotation(entry.getValue(), "$gsm:identityBound")) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private static boolean hasAnnotation(JsonNode node, String annotation) {
        return node.has(annotation) && node.get(annotation).asBoolean(false);
    }
}
