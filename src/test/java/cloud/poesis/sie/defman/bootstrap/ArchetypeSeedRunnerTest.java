package cloud.poesis.sie.defman.bootstrap;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchetypeSeedRunnerTest {

  @Mock private JdbcTemplate jdbc;
  @Mock private ObjectMapper mapper;
  @Mock private ResourcePatternResolver resolver;
  @Mock private ApplicationArguments args;

  private static final String SCHEMA_PATTERN = "classpath:schemas/gsm-archetypes/*.schema.json";
  private ArchetypeSeedRunner runner;

  @BeforeEach
  void setUp() {
    runner = new ArchetypeSeedRunner(jdbc, mapper, resolver, SCHEMA_PATTERN);
  }

  // ========================================================================
  // run() — skip when already seeded
  // ========================================================================

  @Nested
  class RunSkip {

    @Test
    void skipsWhenArchetypesExist() throws Exception {
      when(jdbc.queryForObject("SELECT count(*) FROM archetype", Long.class)).thenReturn(8L);

      runner.run(args);

      // No trigger disable/enable; no inserts
      verify(jdbc, never()).execute(anyString());
    }

    @Test
    void skipsWhenCountIsNull() throws Exception {
      when(jdbc.queryForObject("SELECT count(*) FROM archetype", Long.class)).thenReturn(null);
      // count == null → doSeed() called; need schemas
      stubSchemasAndInserts();

      runner.run(args);

      verify(jdbc, times(2)).execute(anyString()); // 1 disable + 1 enable sync trigger
    }
  }

  // ========================================================================
  // doSeed() — trigger management
  // ========================================================================

  @Nested
  class TriggerManagement {

    @Test
    void disablesAndReenablesTriggers() throws Exception {
      when(jdbc.queryForObject("SELECT count(*) FROM archetype", Long.class)).thenReturn(0L);
      stubSchemasAndInserts();

      runner.run(args);

      verify(jdbc)
          .execute(
              "ALTER TABLE ascription_status_transition DISABLE TRIGGER trg_ast_sync_ascription_status");
      verify(jdbc)
          .execute(
              "ALTER TABLE ascription_status_transition ENABLE TRIGGER trg_ast_sync_ascription_status");
    }
  }

  // ========================================================================
  // doSeedInternal() — seeding logic
  // ========================================================================

  @Nested
  class SeedInternal {

    @Test
    void seedsMetaArchetypeAndOthers() throws Exception {
      when(jdbc.queryForObject("SELECT count(*) FROM archetype", Long.class)).thenReturn(0L);

      // 2 schemas: Archetype (meta) + StructureArchetype
      ObjectMapper realMapper = new ObjectMapper();
      JsonNode metaNode = realMapper.readTree("{\"title\":\"Archetype\"}");
      JsonNode structNode = realMapper.readTree("{\"title\":\"StructureArchetype\"}");

      Resource metaResource = mockResource("Archetype.schema.json");
      Resource structResource = mockResource("StructureArchetype.schema.json");
      when(resolver.getResources(SCHEMA_PATTERN))
          .thenReturn(new Resource[] {metaResource, structResource});
      when(mapper.readTree(any(java.io.InputStream.class))).thenReturn(metaNode, structNode);

      UUID metaDefId = UUID.randomUUID();
      UUID metaArchId = UUID.randomUUID();
      UUID structDefId = UUID.randomUUID();
      UUID structArchId = UUID.randomUUID();

      // insertDefinition calls
      when(jdbc.queryForObject(
              eq(
                  "INSERT INTO definition (subject_type)"
                      + " VALUES ('ARCHETYPE'::definition_subject_type) RETURNING id"),
              eq(UUID.class)))
          .thenReturn(metaDefId, structDefId);

      // Meta archetype INSERT (self-referential CTE)
      when(jdbc.queryForObject(
              any(String.class), eq(UUID.class), eq(metaDefId.toString()), anyString()))
          .thenReturn(metaArchId);

      // Regular archetype INSERT
      when(jdbc.queryForObject(
              any(String.class),
              eq(UUID.class),
              eq(structDefId.toString()),
              eq(metaArchId.toString()),
              anyString()))
          .thenReturn(structArchId);

      runner.run(args);

      // 3 lifecycle transitions per archetype * 2 archetypes = 6 updates
      verify(jdbc, times(6)).update(anyString(), anyString());
    }

    @Test
    void throwsWhenMetaArchetypeNotFound() throws Exception {
      when(jdbc.queryForObject("SELECT count(*) FROM archetype", Long.class)).thenReturn(0L);

      // Only one schema, title != "Archetype"
      Resource resource = stubSchemaResource("{\"title\":\"SomethingElse\"}");
      when(resolver.getResources(SCHEMA_PATTERN)).thenReturn(new Resource[] {resource});

      assertThrows(IllegalStateException.class, () -> runner.run(args));
    }
  }

  // ========================================================================
  // loadSchemas()
  // ========================================================================

  @Nested
  class LoadSchemas {

    @Test
    void throwsOnEmptySchemas() throws Exception {
      when(jdbc.queryForObject("SELECT count(*) FROM archetype", Long.class)).thenReturn(0L);
      when(resolver.getResources(SCHEMA_PATTERN)).thenReturn(new Resource[] {});

      assertThrows(IllegalStateException.class, () -> runner.run(args));
    }

    @Test
    void throwsOnMissingTitle() throws Exception {
      when(jdbc.queryForObject("SELECT count(*) FROM archetype", Long.class)).thenReturn(0L);

      Resource resource = stubSchemaResource("{\"description\":\"no title\"}");
      when(resolver.getResources(SCHEMA_PATTERN)).thenReturn(new Resource[] {resource});

      assertThrows(IllegalStateException.class, () -> runner.run(args));
    }

    @Test
    void throwsOnBlankTitle() throws Exception {
      when(jdbc.queryForObject("SELECT count(*) FROM archetype", Long.class)).thenReturn(0L);

      Resource resource = stubSchemaResource("{\"title\":\"  \"}");
      when(resolver.getResources(SCHEMA_PATTERN)).thenReturn(new Resource[] {resource});

      assertThrows(IllegalStateException.class, () -> runner.run(args));
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private void stubSchemasAndInserts() throws Exception {
    Resource metaResource = stubSchemaResource("{\"title\":\"Archetype\"}");
    when(resolver.getResources(SCHEMA_PATTERN)).thenReturn(new Resource[] {metaResource});

    UUID defId = UUID.randomUUID();
    UUID archId = UUID.randomUUID();
    when(jdbc.queryForObject(
            eq(
                "INSERT INTO definition (subject_type)"
                    + " VALUES ('ARCHETYPE'::definition_subject_type) RETURNING id"),
            eq(UUID.class)))
        .thenReturn(defId);
    when(jdbc.queryForObject(any(String.class), eq(UUID.class), eq(defId.toString()), anyString()))
        .thenReturn(archId);
  }

  private Resource stubSchemaResource(String json) throws Exception {
    Resource resource = mock(Resource.class);
    when(resource.getInputStream())
        .thenReturn(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    when(resource.getFilename()).thenReturn("test.schema.json");

    // Parse the JSON into a real JsonNode so ObjectMapper.readTree works
    ObjectMapper realMapper = new ObjectMapper();
    JsonNode node = realMapper.readTree(json);
    when(mapper.readTree(any(java.io.InputStream.class))).thenReturn(node);

    return resource;
  }

  private Resource mockResource(String filename) throws Exception {
    Resource resource = mock(Resource.class);
    when(resource.getInputStream())
        .thenReturn(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
    when(resource.getFilename()).thenReturn(filename);
    return resource;
  }
}
