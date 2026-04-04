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
 * Manages annotation-driven PostgreSQL index provisioning/deprovisioning for Archetypes.
 *
 * <p>Scans {@code $gsm:queryable} and {@code $gsm:unique} annotations in archetype schemas and
 * creates or drops corresponding partial indexes on the target table's JSONB {@code statement}
 * column.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class ArchetypeIndexProvisioningService {

  private static final Logger LOG =
      LoggerFactory.getLogger(ArchetypeIndexProvisioningService.class);

  private final JdbcTemplate jdbcTemplate;

  record IndexSpec(String indexName, String ddl, String type, String propertyName) {}

  public ArchetypeIndexProvisioningService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Provisions annotation-driven indexes for the given archetype on the target table.
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
   * Deprovisions annotation-driven indexes for the given archetype from the target table.
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
      if (hasAnnotation(propSchema, "$gsm:queryable")) {
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
      if (hasAnnotation(propSchema, "$gsm:unique")) {
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

  private static boolean hasAnnotation(JsonNode node, String annotation) {
    return node.has(annotation) && node.get(annotation).asBoolean(false);
  }
}
