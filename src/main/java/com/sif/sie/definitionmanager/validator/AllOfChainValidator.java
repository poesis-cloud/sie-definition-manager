package com.sif.sie.definitionmanager.validator;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
import com.sif.sie.definitionmanager.repository.ArchetypeRepository;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;

/**
 * Validates Archetype schema {@code allOf} chain convergence (GSM §5) and
 * {@code $gsm:sealed} enforcement (GSM §8).
 *
 * <ul>
 *   <li>Every tenant Archetype's schema allOf chain MUST converge to exactly one
 *       GSM base archetype schema.</li>
 *   <li>Tenant schemas MUST NOT reference a sealed schema via allOf.</li>
 * </ul>
 *
 * GSM base archetypes are exempt from this validation.
 */
@Component
public class AllOfChainValidator {

    /** GSM base archetype titles — exempt from allOf chain validation. */
    private static final Set<String> GSM_BASE_TITLES = Set.of(
            "StructureArchetype", "MechanismArchetype", "InteractionArchetype",
            "InterfaceArchetype", "Archetype", "EffectorArchetype",
            "ReceptorArchetype", "DirectiveArchetype", "NormArchetype");

    /** Pattern for gsm:// URI convention: gsm://archetypes/{title}/v{version} */
    private static final Pattern GSM_URI_PATTERN =
            Pattern.compile("^gsm://archetypes/([^/]+)/v\\d+$");

    private static final Collection<AscriptionStatusType> IN_EFFECT =
            List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);

    private final ArchetypeRepository archetypeRepo;

    public AllOfChainValidator(ArchetypeRepository archetypeRepo) {
        this.archetypeRepo = archetypeRepo;
    }

    /**
     * Validates that the schema's allOf chain converges to exactly one GSM base
     * archetype and does not reference any sealed schema.
     *
     * @param schema the {@code statement.schema} JSON node of the Archetype
     */
    public void validate(JsonNode schema) {
        String title = schema.has("title") ? schema.get("title").asText() : null;

        // GSM base archetypes are exempt
        if (title != null && GSM_BASE_TITLES.contains(title)) {
            return;
        }

        JsonNode allOf = schema.get("allOf");
        if (allOf == null || !allOf.isArray() || allOf.isEmpty()) {
            throw new IllegalArgumentException(
                    "Archetype schema must declare allOf referencing a GSM base archetype schema");
        }

        Set<String> resolvedBases = new HashSet<>();
        Set<String> visited = new HashSet<>();
        if (title != null) {
            visited.add(title);
        }

        walkAllOfChain(allOf, resolvedBases, visited);

        if (resolvedBases.isEmpty()) {
            throw new IllegalArgumentException(
                    "Archetype schema allOf chain does not converge to any GSM base archetype");
        }
        if (resolvedBases.size() > 1) {
            throw new IllegalArgumentException(
                    "Archetype schema allOf chain converges to multiple GSM base archetypes: "
                            + resolvedBases);
        }
    }

    private void walkAllOfChain(JsonNode allOf, Set<String> resolvedBases, Set<String> visited) {
        for (JsonNode entry : allOf) {
            if (!entry.has("$ref")) {
                continue;
            }

            String ref = entry.get("$ref").asText();
            String refTitle = extractTitleFromRef(ref);

            if (refTitle == null) {
                throw new IllegalArgumentException(
                        "Cannot resolve allOf $ref '" + ref
                                + "': must use gsm://archetypes/{title}/v{version} convention");
            }

            if (!visited.add(refTitle)) {
                throw new IllegalArgumentException(
                        "Cycle detected in allOf chain: '" + refTitle + "' already visited");
            }

            if (GSM_BASE_TITLES.contains(refTitle)) {
                // Check if the base archetype is sealed
                if (isSealedBaseArchetype(refTitle)) {
                    throw new IllegalArgumentException(
                            "Archetype schema allOf references sealed schema '"
                                    + refTitle + "' — tenant-defined archetypes MUST NOT extend sealed schemas");
                }
                resolvedBases.add(refTitle);
            } else {
                // Intermediate tenant archetype — look up and walk its allOf
                JsonNode intermediateSchema = resolveArchetypeSchema(refTitle);
                if (intermediateSchema == null) {
                    throw new IllegalArgumentException(
                            "Cannot resolve intermediary archetype '" + refTitle
                                    + "' referenced via allOf — no in-effect Archetype with this schema.title");
                }

                // Check if intermediate is sealed
                if (intermediateSchema.has("$gsm:sealed")
                        && intermediateSchema.get("$gsm:sealed").asBoolean()) {
                    throw new IllegalArgumentException(
                            "Archetype schema allOf references sealed schema '"
                                    + refTitle + "' — tenant-defined archetypes MUST NOT extend sealed schemas");
                }

                JsonNode intermediateAllOf = intermediateSchema.get("allOf");
                if (intermediateAllOf != null && intermediateAllOf.isArray()) {
                    walkAllOfChain(intermediateAllOf, resolvedBases, visited);
                }
            }
        }
    }

    private boolean isSealedBaseArchetype(String title) {
        // Per GSM §5+§8: sealed = Archetype, DirectiveArchetype, NormArchetype,
        // EffectorArchetype, ReceptorArchetype.
        // NOT sealed: StructureArchetype, MechanismArchetype, InteractionArchetype,
        // InterfaceArchetype.
        // We can also check the actual schema $gsm:sealed annotation.
        JsonNode schema = resolveBootstrapSchema(title);
        if (schema != null && schema.has("$gsm:sealed")) {
            return schema.get("$gsm:sealed").asBoolean();
        }
        return false;
    }

    private JsonNode resolveBootstrapSchema(String title) {
        List<ArchetypeEntity> archetypes = archetypeRepo.findAllByStatusIn(IN_EFFECT);
        for (ArchetypeEntity a : archetypes) {
            JsonNode stmt = a.getStatement();
            if (stmt.has("schema")) {
                JsonNode schema = stmt.get("schema");
                if (schema.has("title") && title.equals(schema.get("title").asText())) {
                    return schema;
                }
            }
        }
        return null;
    }

    private JsonNode resolveArchetypeSchema(String title) {
        return resolveBootstrapSchema(title);
    }

    static String extractTitleFromRef(String ref) {
        Matcher m = GSM_URI_PATTERN.matcher(ref);
        if (m.matches()) {
            return m.group(1);
        }
        return null;
    }
}
