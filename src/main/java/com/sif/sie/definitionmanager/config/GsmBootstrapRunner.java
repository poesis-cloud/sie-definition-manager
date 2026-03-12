package com.sif.sie.definitionmanager.config;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sif.sie.definitionmanager.util.UuidV7GeneratorUtil;

/**
 * Seeds GSM base archetypes from {@code schemas/gsm-archetypes/*.schema.json}
 * classpath resources on first startup (idempotent — skips if archetypes
 * already exist).
 *
 * <p>
 * Uses raw JDBC with triggers temporarily disabled to replicate the
 * original V1 migration seed semantics (which ran before trigger creation).
 * Inserts with explicit {@code status=ACTIVE, version=1} and full audit trail.
 *
 * <p>
 * Schema files are sourced from {@code definition/schemas/gsm-archetypes/}
 * (mapped to classpath via Maven resource directory). Single source of truth:
 * edit schema files, restart — no migration changes needed.
 */
@Component
public class GsmBootstrapRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(GsmBootstrapRunner.class);
    private static final String SCHEMA_PATTERN = "classpath:schemas/gsm-archetypes/*.schema.json";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ResourcePatternResolver resolver;

    public GsmBootstrapRunner(JdbcTemplate jdbc, ObjectMapper mapper,
            ResourcePatternResolver resolver) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.resolver = resolver;
    }

    @Override
    @Transactional("transactionManager")
    public void run(ApplicationArguments args) throws Exception {
        Long count = jdbc.queryForObject("SELECT count(*) FROM archetype", Long.class);
        if (count != null && count > 0) {
            LOG.info("GSM base archetypes already seeded ({} row(s)); skipping bootstrap.", count);
            return;
        }
        seed();
    }

    private void seed() throws IOException {
        // Disable triggers — seed inserts use explicit status/version values
        // (mirrors V1 migration seed which ran before trigger creation)
        jdbc.execute("ALTER TABLE archetype DISABLE TRIGGER ALL");
        jdbc.execute("ALTER TABLE ascription_status_transition DISABLE TRIGGER ALL");
        try {
            doSeed();
        } finally {
            jdbc.execute("ALTER TABLE ascription_status_transition ENABLE TRIGGER ALL");
            jdbc.execute("ALTER TABLE archetype ENABLE TRIGGER ALL");
        }
    }

    private void doSeed() throws IOException {
        Map<String, JsonNode> schemas = loadSchemas();

        // Meta-archetype must be seeded first (all others reference it)
        JsonNode metaSchema = schemas.remove("Archetype");
        if (metaSchema == null) {
            throw new IllegalStateException(
                    "Archetype.schema.json (title='Archetype') not found on classpath");
        }

        UUID metaDefId = UuidV7GeneratorUtil.generate();
        UUID metaAscId = UuidV7GeneratorUtil.generate();

        insertDefinition(metaDefId);
        insertArchetype(metaAscId, metaDefId, metaAscId, wrapSchema(metaSchema));
        insertLifecycleTransitions(metaAscId);
        LOG.info("Seeded meta-archetype: Archetype (self-typed bootstrap)");

        for (Map.Entry<String, JsonNode> entry : schemas.entrySet()) {
            UUID defId = UuidV7GeneratorUtil.generate();
            UUID ascId = UuidV7GeneratorUtil.generate();

            insertDefinition(defId);
            insertArchetype(ascId, defId, metaAscId, wrapSchema(entry.getValue()));
            insertLifecycleTransitions(ascId);
            LOG.info("Seeded base archetype: {}", entry.getKey());
        }

        LOG.info("GSM bootstrap complete: {} base archetype(s) seeded.", 1 + schemas.size());
    }

    private Map<String, JsonNode> loadSchemas() throws IOException {
        Resource[] resources = resolver.getResources(SCHEMA_PATTERN);
        Map<String, JsonNode> schemas = new LinkedHashMap<>();
        for (Resource r : resources) {
            JsonNode schema = mapper.readTree(r.getInputStream());
            JsonNode titleNode = schema.get("title");
            if (titleNode == null || titleNode.asText().isBlank()) {
                throw new IllegalStateException(
                        "Schema file missing 'title': " + r.getFilename());
            }
            schemas.put(titleNode.asText(), schema);
        }
        if (schemas.isEmpty()) {
            throw new IllegalStateException("No schema files found at " + SCHEMA_PATTERN);
        }
        return schemas;
    }

    private void insertDefinition(UUID id) {
        jdbc.update(
                "INSERT INTO definition (id, subject_type) VALUES (?::uuid, 'ARCHETYPE'::definition_subject_type)",
                id.toString());
    }

    private void insertArchetype(UUID id, UUID definitionId, UUID archetypeId, String statement) {
        jdbc.update(
                "INSERT INTO archetype (id, definition_id, archetype_id, statement, version, status)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?::jsonb, 1, 'ACTIVE'::ascription_status)",
                id.toString(), definitionId.toString(), archetypeId.toString(), statement);
    }

    private void insertLifecycleTransitions(UUID ascriptionId) {
        String id = ascriptionId.toString();
        // Audit trail only (triggers disabled) — mirrors original V1 seed
        jdbc.update(
                "INSERT INTO ascription_status_transition (ascription_id, pre_status, post_status)"
                        + " VALUES (?::uuid, NULL, 'DRAFT'::ascription_status)",
                id);
        jdbc.update(
                "INSERT INTO ascription_status_transition (ascription_id, pre_status, post_status)"
                        + " VALUES (?::uuid, 'DRAFT'::ascription_status, 'PROPOSED'::ascription_status)",
                id);
        jdbc.update(
                "INSERT INTO ascription_status_transition (ascription_id, pre_status, post_status)"
                        + " VALUES (?::uuid, 'PROPOSED'::ascription_status, 'APPROVED'::ascription_status)",
                id);
        jdbc.update(
                "INSERT INTO ascription_status_transition (ascription_id, pre_status, post_status)"
                        + " VALUES (?::uuid, 'APPROVED'::ascription_status, 'ACTIVE'::ascription_status)",
                id);
    }

    private static String wrapSchema(JsonNode schema) {
        return "{\"schema\":" + schema.toString() + "}";
    }
}
