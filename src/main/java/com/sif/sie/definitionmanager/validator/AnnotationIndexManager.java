package com.sif.sie.definitionmanager.validator;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;

/**
 * Provisions and deprovisions annotation-driven PostgreSQL indexes for
 * {@code $gsm:queryable} and {@code $gsm:unique} Archetype schema annotations (GSM §8, §9).
 *
 * <p>Indexes are provisioned at Archetype ACTIVE transition:
 * <ul>
 *   <li>{@code $gsm:queryable}: expression index per annotated JSONB path
 *       (B-tree for scalars, GIN for arrays).</li>
 *   <li>{@code $gsm:unique}: partial unique expression index.</li>
 * </ul>
 *
 * <p>Indexes are dropped when no in-effect Ascription of the Archetype remains.
 */
@Component
public class AnnotationIndexManager {

    private static final Logger LOG = LoggerFactory.getLogger(AnnotationIndexManager.class);

    private final JdbcTemplate jdbcTemplate;

    public AnnotationIndexManager(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Provisions annotation-driven indexes when an Archetype transitions to ACTIVE.
     *
     * @param archetype the Archetype entity transitioning to ACTIVE
     */
    public void provisionIndexes(ArchetypeEntity archetype) {
        JsonNode schema = archetype.getStatement().get("schema");
        if (schema == null) {
            return;
        }

        UUID archetypeDefId = archetype.getDefinition().getId();
        String schemaTitle = schema.has("title") ? schema.get("title").asText() : archetypeDefId.toString();

        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }

        List<IndexSpec> specs = collectIndexSpecs(properties, archetypeDefId, schemaTitle);

        for (IndexSpec spec : specs) {
            try {
                jdbcTemplate.execute(spec.ddl());
                LOG.info("Provisioned {} index '{}' for Archetype '{}' property '{}'",
                        spec.type(), spec.indexName(), schemaTitle, spec.propertyName());
            } catch (Exception e) {
                LOG.warn("Failed to provision index '{}': {}", spec.indexName(), e.getMessage());
            }
        }
    }

    /**
     * Deprovisions annotation-driven indexes when an Archetype leaves in-effect status.
     *
     * @param archetype the Archetype entity leaving in-effect status
     */
    public void deprovisionIndexes(ArchetypeEntity archetype) {
        JsonNode schema = archetype.getStatement().get("schema");
        if (schema == null) {
            return;
        }

        UUID archetypeDefId = archetype.getDefinition().getId();
        String schemaTitle = schema.has("title") ? schema.get("title").asText() : archetypeDefId.toString();

        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }

        List<IndexSpec> specs = collectIndexSpecs(properties, archetypeDefId, schemaTitle);

        for (IndexSpec spec : specs) {
            String dropDdl = "DROP INDEX IF EXISTS " + spec.indexName();
            try {
                jdbcTemplate.execute(dropDdl);
                LOG.info("Deprovisioned index '{}' for Archetype '{}'", spec.indexName(), schemaTitle);
            } catch (Exception e) {
                LOG.warn("Failed to deprovision index '{}': {}", spec.indexName(), e.getMessage());
            }
        }
    }

    // ========================================================================
    // Internal
    // ========================================================================

    private record IndexSpec(String indexName, String ddl, String type, String propertyName) {}

    private List<IndexSpec> collectIndexSpecs(
            JsonNode properties, UUID archetypeDefId, String schemaTitle) {

        List<IndexSpec> specs = new ArrayList<>();
        String sanitizedTitle = sanitizeIdentifier(schemaTitle);
        String archetypeIdLiteral = "'" + archetypeDefId + "'";

        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String propName = entry.getKey();
            JsonNode propSchema = entry.getValue();
            String sanitizedProp = sanitizeIdentifier(propName);

            // $gsm:queryable
            if (hasAnnotation(propSchema, "$gsm:queryable")) {
                String indexName = "idx_gsm_q_" + sanitizedTitle + "_" + sanitizedProp;
                String type = propSchema.has("type") ? propSchema.get("type").asText() : "string";
                String indexType = "array".equals(type) ? "GIN" : "BTREE";
                String jsonbPath = "(statement->>'" + escapeJsonbKey(propName) + "')";

                String ddl;
                if ("GIN".equals(indexType)) {
                    jsonbPath = "(statement->'" + escapeJsonbKey(propName) + "')";
                    ddl = "CREATE INDEX IF NOT EXISTS " + indexName
                            + " ON ascription USING GIN (" + jsonbPath + ")"
                            + " WHERE archetype_id = " + archetypeIdLiteral;
                } else {
                    ddl = "CREATE INDEX IF NOT EXISTS " + indexName
                            + " ON ascription (" + jsonbPath + ")"
                            + " WHERE archetype_id = " + archetypeIdLiteral;
                }

                specs.add(new IndexSpec(indexName, ddl, "queryable/" + indexType, propName));
            }

            // $gsm:unique
            if (hasAnnotation(propSchema, "$gsm:unique")) {
                String indexName = "idx_gsm_u_" + sanitizedTitle + "_" + sanitizedProp;
                String jsonbPath = "(statement->>'" + escapeJsonbKey(propName) + "')";

                String ddl = "CREATE UNIQUE INDEX IF NOT EXISTS " + indexName
                        + " ON ascription (" + jsonbPath + ")"
                        + " WHERE archetype_id = " + archetypeIdLiteral
                        + " AND status IN ('ACTIVE','DEPRECATED')";

                specs.add(new IndexSpec(indexName, ddl, "unique", propName));
            }
        }

        return specs;
    }

    /**
     * Sanitizes a string for use as a PostgreSQL identifier component.
     * Keeps only alphanumeric and underscore; truncates to 30 chars.
     */
    private static String sanitizeIdentifier(String input) {
        return input.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase()
                .substring(0, Math.min(input.length(), 30));
    }

    /**
     * Escapes a JSONB key for safe use in SQL expressions.
     * Prevents SQL injection via crafted property names.
     */
    private static String escapeJsonbKey(String key) {
        return key.replace("'", "''").replace("\\", "\\\\");
    }

    private static boolean hasAnnotation(JsonNode node, String annotation) {
        return node.has(annotation) && node.get(annotation).asBoolean(false);
    }
}
