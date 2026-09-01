package cloud.poesis.sie.defman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("tc")
class VersionMaterializationIT extends AbstractPostgresIT {

  private static final List<String> TABLES =
      List.of(
          "archetype",
          "structure",
          "mechanism",
          "effector",
          "receptor",
          "interaction",
          "directive",
          "norm");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registerIsolatedDatabase(registry);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired ArchetypeRepository archetypeRepository;

  @Test
  void everyConcreteTableHasVersionGuards() {
    for (String table : TABLES) {
      assertEquals(
          1,
          count(
              "SELECT count(*) FROM information_schema.columns"
                  + " WHERE table_schema = 'public' AND table_name = ? AND column_name = 'version'",
              table));
      assertEquals(
          1,
          count(
              "SELECT count(*) FROM pg_constraint WHERE conname = ?",
              "ck_" + table + "_status_version"));
      assertEquals(
          1,
          count(
              "SELECT count(*) FROM pg_indexes WHERE indexname = ?",
              "uq_" + table + "_definition_version"));
      assertEquals(
          1,
          count(
              "SELECT count(*) FROM pg_trigger WHERE tgname = ?",
              "trg_" + table + "_version_materialization"));
    }
  }

  @Test
  void bootstrapSeedsAreGovernedVersionOne() {
    assertEquals(
        8,
        count(
            "SELECT count(*) FROM archetype"
                + " WHERE status = 'ACTIVE'::ascription_status AND version = 1"));
  }

  @Test
  void resolvableArchetypeUrisHaveNamedUniqueGuard() {
    assertEquals(
        1,
        count("SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_archetype_resolvable_uri'"));
    assertEquals(1, count("SELECT count(*) FROM pg_indexes WHERE indexname = 'ix_archetype_id'"));
    assertEquals(
        0, count("SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_archetype_title'"));
  }

  @Test
  void duplicatePositiveVersionArchetypeIdIsRejected() {
    UUID typingArchetypeId =
        jdbc.queryForObject(
            "SELECT id FROM archetype WHERE statement->>'title' = 'Archetype'", UUID.class);
    UUID firstDefinitionId = createArchetypeDefinition();
    UUID secondDefinitionId = createArchetypeDefinition();
    String statement =
        "{\"$id\":\"gsmarc://test/ResolvableGuard/v1\",\"title\":\"ResolvableGuard\"}";

    UUID firstArchetypeId = createDraftArchetype(firstDefinitionId, typingArchetypeId, statement);
    UUID secondArchetypeId = createDraftArchetype(secondDefinitionId, typingArchetypeId, statement);
    assertEquals(
        2,
        count(
            "SELECT count(*) FROM archetype"
                + " WHERE statement->>'$id' = 'gsmarc://test/ResolvableGuard/v1'"
                + " AND version = 0"));

    transition(firstArchetypeId, "DRAFT", "PROPOSED");
    transition(firstArchetypeId, "PROPOSED", "APPROVED");
    transition(secondArchetypeId, "DRAFT", "PROPOSED");

    DataIntegrityViolationException exception =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> transition(secondArchetypeId, "PROPOSED", "APPROVED"));
    PSQLException postgresException = (PSQLException) exception.getCause();
    assertEquals(
        "uq_archetype_resolvable_uri", postgresException.getServerErrorMessage().getConstraint());
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM archetype"
                + " WHERE statement->>'$id' = 'gsmarc://test/ResolvableGuard/v1'"
                + " AND version > 0"));
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM archetype WHERE id = ?::uuid"
                + " AND status = 'PROPOSED'::ascription_status AND version = 0",
            secondArchetypeId.toString()));
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM ascription_status_transition"
                + " WHERE ascription_id = ?::uuid"
                + " AND pre_status = 'PROPOSED'::ascription_status"
                + " AND post_status = 'APPROVED'::ascription_status",
            secondArchetypeId.toString()));
  }

  @Test
  void resolvableUriLookupKeepsOlderRetiredVersionReadableAndExcludesCandidate() {
    UUID typingArchetypeId =
        jdbc.queryForObject(
            "SELECT id FROM archetype WHERE statement->>'title' = 'Archetype'", UUID.class);
    UUID definitionId = createArchetypeDefinition();
    String title = "ResolvableHistory" + UUID.randomUUID().toString().replace("-", "");
    String stem = "gsmarc://test/" + title;

    UUID versionOne =
        createDraftArchetype(
            definitionId,
            typingArchetypeId,
            "{\"$id\":\"" + stem + "/v1\",\"title\":\"" + title + "\"}");
    transition(versionOne, "DRAFT", "PROPOSED");
    transition(versionOne, "PROPOSED", "APPROVED");
    transition(versionOne, "APPROVED", "ACTIVE");
    transition(versionOne, "ACTIVE", "DEPRECATED");
    transition(versionOne, "DEPRECATED", "RETIRED");

    UUID versionTwo =
        createDraftArchetype(
            definitionId,
            typingArchetypeId,
            "{\"$id\":\"" + stem + "/v2\",\"title\":\"" + title + "\"}");
    transition(versionTwo, "DRAFT", "PROPOSED");
    transition(versionTwo, "PROPOSED", "APPROVED");
    transition(versionTwo, "APPROVED", "ACTIVE");

    createDraftArchetype(
        definitionId,
        typingArchetypeId,
        "{\"$id\":\"" + stem + "/v3\",\"title\":\"" + title + "\"}");

    assertEquals(
        AscriptionStatusType.RETIRED,
        archetypeRepository.findResolvableByUri(stem + "/v1").orElseThrow().getStatus());
    assertEquals(
        AscriptionStatusType.ACTIVE,
        archetypeRepository.findResolvableByUri(stem + "/v2").orElseThrow().getStatus());
    assertTrue(archetypeRepository.findResolvableByUri(stem + "/v3").isEmpty());
  }

  @Test
  void directVersionMutationIsRejected() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE archetype SET version = 2" + " WHERE statement->>'title' = 'Archetype'"));
  }

  @Test
  void crossTypeViewExposesVersion() {
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = 'ascription_all'"
                + " AND column_name = 'version'"));
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private UUID createArchetypeDefinition() {
    return jdbc.queryForObject(
        "INSERT INTO definition (subject_type)"
            + " VALUES ('ARCHETYPE'::definition_subject_type) RETURNING id",
        UUID.class);
  }

  private UUID createDraftArchetype(UUID definitionId, UUID typingArchetypeId, String statement) {
    return jdbc.queryForObject(
        "INSERT INTO archetype"
            + " (definition_id, archetype_id, statement, status, version)"
            + " VALUES (?::uuid, ?::uuid, ?::jsonb, 'DRAFT'::ascription_status, 0)"
            + " RETURNING id",
        UUID.class,
        definitionId.toString(),
        typingArchetypeId.toString(),
        statement);
  }

  private void transition(UUID ascriptionId, String preStatus, String postStatus) {
    jdbc.update(
        "INSERT INTO ascription_status_transition (ascription_id, pre_status, post_status)"
            + " VALUES (?::uuid, ?::ascription_status, ?::ascription_status)",
        ascriptionId.toString(),
        preStatus,
        postStatus);
  }
}
