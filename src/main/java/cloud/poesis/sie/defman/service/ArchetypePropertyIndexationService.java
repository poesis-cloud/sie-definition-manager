package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Manages PostgreSQL index provisioning and deprovisioning on ascription statement JSONB columns,
 * driven by {@code $gsm:*} annotations declared in Archetype schemas.
 *
 * <p><b>Precondition for indexation</b> ({@link #provisionIndexes}): an Archetype is activated and
 * its JSON Schema declares properties annotated with {@code $gsm:queryable} (→ BTREE or GIN partial
 * index) or {@code $gsm:unique} (→ UNIQUE partial index scoped to ACTIVE/DEPRECATED status). Only
 * annotated properties produce indexes; unannotated properties are ignored.
 *
 * <p><b>Precondition for deindexation</b> ({@link #deprovisionIndexes}): an Archetype is
 * deactivated (deprecated or withdrawn) and the same annotation scan identifies the indexes to
 * drop.
 *
 * <p>Both operations are idempotent: {@code CREATE INDEX IF NOT EXISTS} / {@code DROP INDEX IF
 * EXISTS}.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class ArchetypePropertyIndexationService {

  private static final Logger LOG =
      LoggerFactory.getLogger(ArchetypePropertyIndexationService.class);

  private final JdbcTemplate jdbcTemplate;

  record IndexSpec(String indexName, String ddl, String type, String propertyName) {}

  public ArchetypePropertyIndexationService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Provisions indexes for properties annotated with {@code $gsm:queryable} or {@code $gsm:unique}
   * in the archetype's JSON Schema.
   *
   * <p>Called by {@link ArchetypeService#onActivation} when an Archetype transitions to ACTIVE.
   *
   * @param archetype the archetype entity whose schema annotations drive index creation
   * @param tableNameSupplier lazily resolved table name — only invoked when the archetype has
   *     annotated properties that require index provisioning
   */
  public void provisionIndexes(ArchetypeEntity archetype, Supplier<String> tableNameSupplier) {
    JsonNode stmt = archetype.getStatement();
    if (stmt == null) {
      return;
    }

    UUID archetypeDefId = archetype.getDefinition().getId();
    String schemaTitle = stmt.has("title") ? stmt.get("title").asText() : archetypeDefId.toString();

    JsonNode properties = stmt.get("properties");
    if (properties == null || !properties.isObject()) {
      return;
    }

    List<IndexSpec> specs =
        collectIndexSpecs(properties, archetypeDefId, schemaTitle, tableNameSupplier.get());

    for (IndexSpec spec : specs) {
      try {
        jdbcTemplate.execute(spec.ddl());
        LOG.info(
            "Provisioned {} index '{}' for Archetype '{}' property '{}'",
            spec.type(),
            spec.indexName(),
            schemaTitle,
            spec.propertyName());
      } catch (Exception e) {
        LOG.warn("Failed to provision index '{}': {}", spec.indexName(), e.getMessage());
      }
    }
  }

  /**
   * Deprovisions indexes previously created for properties annotated with {@code $gsm:queryable} or
   * {@code $gsm:unique} in the archetype's JSON Schema.
   *
   * <p>Called by {@link ArchetypeService#onDeactivation} when an Archetype is deprecated or
   * withdrawn.
   *
   * @param archetype the archetype entity whose schema annotations drive index removal
   * @param tableNameSupplier lazily resolved table name — only invoked when the archetype has
   *     annotated properties that require index deprovisioning
   */
  public void deprovisionIndexes(ArchetypeEntity archetype, Supplier<String> tableNameSupplier) {
    JsonNode stmt = archetype.getStatement();
    if (stmt == null) {
      return;
    }

    UUID archetypeDefId = archetype.getDefinition().getId();
    String schemaTitle = stmt.has("title") ? stmt.get("title").asText() : archetypeDefId.toString();

    JsonNode properties = stmt.get("properties");
    if (properties == null || !properties.isObject()) {
      return;
    }

    List<IndexSpec> specs =
        collectIndexSpecs(properties, archetypeDefId, schemaTitle, tableNameSupplier.get());

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

  List<IndexSpec> collectIndexSpecs(
      JsonNode properties, UUID archetypeDefId, String schemaTitle, String tableName) {

    List<IndexSpec> specs = new ArrayList<>();
    String sanitizedTitle = sanitizeIdentifier(schemaTitle);
    String archetypeIdLiteral = "'" + archetypeDefId + "'";

    for (Map.Entry<String, JsonNode> entry : properties.properties()) {
      String propName = entry.getKey();
      JsonNode propSchema = entry.getValue();
      String sanitizedProp = sanitizeIdentifier(propName);

      // $gsm:queryable
      if (ArchetypeParsingService.hasAnnotation(propSchema, "$gsm:queryable")) {
        String indexName = "idx_gsm_q_" + sanitizedTitle + "_" + sanitizedProp;
        String type = propSchema.has("type") ? propSchema.get("type").asText() : "string";
        String indexType = "array".equals(type) ? "GIN" : "BTREE";
        String jsonbPath = "(statement->>'" + escapeJsonbKey(propName) + "')";

        String ddl;
        if ("GIN".equals(indexType)) {
          jsonbPath = "(statement->'" + escapeJsonbKey(propName) + "')";
          ddl =
              "CREATE INDEX IF NOT EXISTS "
                  + indexName
                  + " ON "
                  + tableName
                  + " USING GIN ("
                  + jsonbPath
                  + ")"
                  + " WHERE archetype_id = "
                  + archetypeIdLiteral;
        } else {
          ddl =
              "CREATE INDEX IF NOT EXISTS "
                  + indexName
                  + " ON "
                  + tableName
                  + " ("
                  + jsonbPath
                  + ")"
                  + " WHERE archetype_id = "
                  + archetypeIdLiteral;
        }

        specs.add(new IndexSpec(indexName, ddl, "queryable/" + indexType, propName));
      }

      // $gsm:unique
      if (ArchetypeParsingService.hasAnnotation(propSchema, "$gsm:unique")) {
        String indexName = "idx_gsm_u_" + sanitizedTitle + "_" + sanitizedProp;
        String jsonbPath = "(statement->>'" + escapeJsonbKey(propName) + "')";

        String ddl =
            "CREATE UNIQUE INDEX IF NOT EXISTS "
                + indexName
                + " ON "
                + tableName
                + " ("
                + jsonbPath
                + ")"
                + " WHERE archetype_id = "
                + archetypeIdLiteral
                + " AND status IN ('ACTIVE','DEPRECATED')";

        specs.add(new IndexSpec(indexName, ddl, "unique", propName));
      }
    }

    return specs;
  }

  static String sanitizeIdentifier(String input) {
    String sanitized = input.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    return sanitized.substring(0, Math.min(sanitized.length(), 30));
  }

  static String escapeJsonbKey(String key) {
    return key.replace("\\", "\\\\").replace("'", "''");
  }
}
