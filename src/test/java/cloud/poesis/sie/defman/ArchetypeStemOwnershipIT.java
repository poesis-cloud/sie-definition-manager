package cloud.poesis.sie.defman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("tc")
@Testcontainers
class ArchetypeStemOwnershipIT {

  @Container
  static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16.3-alpine");

  @DynamicPropertySource
  static void pgProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", pg::getJdbcUrl);
    registry.add("spring.datasource.username", pg::getUsername);
    registry.add("spring.datasource.password", pg::getPassword);
  }

  @Autowired
  JdbcTemplate jdbc;
  @Autowired
  DataSource dataSource;

  @Test
  void bootstrapPermanentlyOwnsAllEightSeedStems() {
    assertEquals(
        8,
        count(
            "SELECT count(*) FROM archetype_stem_owner owner"
                + " JOIN archetype archetype ON archetype.definition_id = owner.definition_id"
                + " AND regexp_replace(archetype.statement->>'$id', '/v[1-9][0-9]*$', '') = owner.stem"
                + " WHERE archetype.status = 'ACTIVE'::ascription_status"));
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM information_schema.tables"
                + " WHERE table_schema = 'public' AND table_name = 'archetype_stem_owner'"));
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM information_schema.routines"
                + " WHERE routine_schema = 'public'"
                + " AND routine_name = 'gsm_acquire_archetype_stem_owner'"));
    assertEquals(1, count("SELECT count(*) FROM pg_indexes WHERE indexname = 'ix_archetype_stem'"));
    assertEquals(0, count("SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_archetype_stem'"));
    assertEquals(
        0, count("SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_archetype_stem_owner'"));
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM pg_trigger"
                + " WHERE tgname = 'trg_archetype_stem_owner_immutable'"));
  }

  @Test
  void existingOwnerCannotBeTransferred() {
    UUID firstDefinitionId = createDefinition();
    UUID secondDefinitionId = createDefinition();
    String stem = uniqueStem("non-transfer");

    assertEquals(firstDefinitionId, acquire(stem, firstDefinitionId));
    assertEquals(firstDefinitionId, acquire(stem, firstDefinitionId));
    assertEquals(firstDefinitionId, acquire(stem, secondDefinitionId));
    assertEquals(1, count("SELECT count(*) FROM archetype_stem_owner WHERE stem = ?", stem));
  }

  @Test
  void ownerRowRejectsDirectTransferAndDelete() {
    UUID firstDefinitionId = createDefinition();
    UUID secondDefinitionId = createDefinition();
    String stem = uniqueStem("immutable");
    assertEquals(firstDefinitionId, acquire(stem, firstDefinitionId));

    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update(
            "UPDATE archetype_stem_owner SET definition_id = ?::uuid WHERE stem = ?",
            secondDefinitionId.toString(),
            stem));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update(
            "UPDATE archetype_stem_owner SET stem = ? WHERE stem = ?", stem + "/moved", stem));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("DELETE FROM archetype_stem_owner WHERE stem = ?", stem));
    assertEquals(
        firstDefinitionId,
        jdbc.queryForObject(
            "SELECT definition_id FROM archetype_stem_owner WHERE stem = ?", UUID.class, stem));
  }

  @Test
  void rolledBackClaimLeavesNoOwner() throws SQLException {
    String stem = uniqueStem("rollback");

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      UUID definitionId = createDefinition(connection);
      assertEquals(definitionId, acquire(connection, stem, definitionId));
      connection.rollback();
    }

    assertEquals(0, count("SELECT count(*) FROM archetype_stem_owner WHERE stem = ?", stem));
  }

  @Test
  void concurrentFirstClaimsConvergeOnOnePermanentOwner() throws Exception {
    UUID firstDefinitionId = createDefinition();
    UUID secondDefinitionId = createDefinition();
    String stem = uniqueStem("race");
    CyclicBarrier start = new CyclicBarrier(2);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<UUID> first = executor.submit(() -> acquireAfter(start, stem, firstDefinitionId));
      Future<UUID> second = executor.submit(() -> acquireAfter(start, stem, secondDefinitionId));

      UUID firstResult = first.get();
      UUID secondResult = second.get();
      assertEquals(firstResult, secondResult);
      assertTrue(Set.of(firstDefinitionId, secondDefinitionId).contains(firstResult));
      assertEquals(
          firstResult,
          jdbc.queryForObject(
              "SELECT definition_id FROM archetype_stem_owner WHERE stem = ?", UUID.class, stem));
    }
  }

  private UUID acquireAfter(CyclicBarrier start, String stem, UUID definitionId) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      start.await();
      UUID ownerDefinitionId = acquire(connection, stem, definitionId);
      connection.commit();
      return ownerDefinitionId;
    }
  }

  private UUID createDefinition() {
    return jdbc.queryForObject(
        "INSERT INTO definition (subject_type)"
            + " VALUES ('ARCHETYPE'::definition_subject_type) RETURNING id",
        UUID.class);
  }

  private UUID createDefinition(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO definition (subject_type)"
            + " VALUES ('ARCHETYPE'::definition_subject_type) RETURNING id")) {
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getObject(1, UUID.class);
      }
    }
  }

  private UUID acquire(String stem, UUID definitionId) {
    return jdbc.queryForObject(
        "SELECT gsm_acquire_archetype_stem_owner(?::text, ?::uuid)",
        UUID.class,
        stem,
        definitionId.toString());
  }

  private UUID acquire(Connection connection, String stem, UUID definitionId) throws SQLException {
    try (PreparedStatement statement = connection
        .prepareStatement("SELECT gsm_acquire_archetype_stem_owner(?::text, ?::uuid)")) {
      statement.setString(1, stem);
      statement.setString(2, definitionId.toString());
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getObject(1, UUID.class);
      }
    }
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private String uniqueStem(String scenario) {
    return "gsmarc://test/ownership/" + scenario + "/" + UUID.randomUUID();
  }
}
