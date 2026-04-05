package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchetypeSchemaPropertyIndexationServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private JdbcTemplate jdbcTemplate;

  private ArchetypeSchemaPropertyIndexationService service;

  @BeforeEach
  void setUp() {
    service = new ArchetypeSchemaPropertyIndexationService(jdbcTemplate);
  }

  // ========================================================================
  // Provision indexes
  // ========================================================================

  @Nested
  class ProvisionIndexes {

    @Test
    void queryableIndex_createsBtreeByDefault() {
      ArchetypeEntity entity = archetypeWithQueryableProps("TestArch", "env", "string");

      service.provisionIndexes(entity, () -> "structure");

      ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
      verify(jdbcTemplate).execute(captor.capture());
      String ddl = captor.getValue();
      assertTrue(ddl.contains("CREATE INDEX IF NOT EXISTS"), ddl);
      assertTrue(ddl.contains("idx_gsm_q_"), ddl);
      assertTrue(ddl.contains("statement->>'env'"), ddl);
      assertTrue(ddl.contains("ON structure"), ddl);
    }

    @Test
    void uniqueIndex_createsUniqueWithStatusFilter() {
      ArchetypeEntity entity = archetypeWithUniqueProps("TestArch", "name", "string");

      service.provisionIndexes(entity, () -> "structure");

      ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
      verify(jdbcTemplate).execute(captor.capture());
      String ddl = captor.getValue();
      assertTrue(ddl.contains("CREATE UNIQUE INDEX IF NOT EXISTS"), ddl);
      assertTrue(ddl.contains("idx_gsm_u_"), ddl);
      assertTrue(ddl.contains("ACTIVE"), ddl);
    }

    @Test
    void queryableAndUnique_provisionsBoth() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity def = mock(DefinitionEntity.class);
      when(def.getId()).thenReturn(defId);

      ObjectNode stmt = MAPPER.createObjectNode().put("title", "TestArch");
      ObjectNode props = stmt.putObject("properties");
      ObjectNode envProp = props.putObject("env");
      envProp.put("type", "string");
      envProp.put("$gsm:queryable", true);
      envProp.put("$gsm:unique", true);

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(stmt);
      when(entity.getDefinition()).thenReturn(def);

      service.provisionIndexes(entity, () -> "structure");

      verify(jdbcTemplate, times(2)).execute(anyString());
    }

    @Test
    void ginForArrayType() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity def = mock(DefinitionEntity.class);
      when(def.getId()).thenReturn(defId);

      ObjectNode stmt = MAPPER.createObjectNode().put("title", "TestArch");
      ObjectNode props = stmt.putObject("properties");
      ObjectNode tagsProp = props.putObject("tags");
      tagsProp.put("type", "array");
      tagsProp.putObject("items").put("type", "string");
      tagsProp.put("$gsm:queryable", true);

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(stmt);
      when(entity.getDefinition()).thenReturn(def);

      service.provisionIndexes(entity, () -> "structure");

      ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
      verify(jdbcTemplate).execute(captor.capture());
      assertTrue(captor.getValue().contains("USING GIN"));
    }

    @Test
    void noProperties_noIndexes() {
      DefinitionEntity def = defWithId();
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(MAPPER.createObjectNode().put("title", "TestArch"));
      when(entity.getDefinition()).thenReturn(def);

      service.provisionIndexes(entity, () -> "structure");

      verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void nullStatement_noOp() {
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(null);

      service.provisionIndexes(entity, () -> "structure");

      verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void indexCreationFailure_doesNotThrow() {
      ArchetypeEntity entity = archetypeWithQueryableProps("TestArch", "env", "string");
      doThrow(new RuntimeException("DB error")).when(jdbcTemplate).execute(anyString());

      assertDoesNotThrow(() -> service.provisionIndexes(entity, () -> "structure"));
    }

    @Test
    void usesSchemaTitle_notId() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity def = mock(DefinitionEntity.class);
      when(def.getId()).thenReturn(defId);

      ObjectNode stmt = MAPPER.createObjectNode().put("title", "MyCustomArchetype");
      ObjectNode props = stmt.putObject("properties");
      props.putObject("x").put("type", "string").put("$gsm:queryable", true);

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(stmt);
      when(entity.getDefinition()).thenReturn(def);

      service.provisionIndexes(entity, () -> "structure");

      ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
      verify(jdbcTemplate).execute(captor.capture());
      assertTrue(captor.getValue().contains("mycustomarchetype"), captor.getValue());
    }
  }

  // ========================================================================
  // Deprovision indexes
  // ========================================================================

  @Nested
  class DeprovisionIndexes {

    @Test
    void dropsIndexes() {
      ArchetypeEntity entity = archetypeWithQueryableProps("TestArch", "env", "string");

      service.deprovisionIndexes(entity, () -> "structure");

      ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
      verify(jdbcTemplate).execute(captor.capture());
      assertTrue(captor.getValue().contains("DROP INDEX IF EXISTS"));
    }

    @Test
    void nullStatement_noOp() {
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(null);

      service.deprovisionIndexes(entity, () -> "structure");

      verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void noProperties_noOp() {
      DefinitionEntity def = defWithId();
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(MAPPER.createObjectNode().put("title", "TestArch"));
      when(entity.getDefinition()).thenReturn(def);

      service.deprovisionIndexes(entity, () -> "structure");

      verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void dropFailure_doesNotThrow() {
      ArchetypeEntity entity = archetypeWithQueryableProps("TestArch", "env", "string");
      doThrow(new RuntimeException("DB error")).when(jdbcTemplate).execute(anyString());

      assertDoesNotThrow(() -> service.deprovisionIndexes(entity, () -> "structure"));
    }
  }

  // ========================================================================
  // Utility methods
  // ========================================================================

  @Nested
  class UtilityMethods {

    @Test
    void sanitizeIdentifier_stripsSpecialChars() {
      assertEquals(
          "hello_world",
          ArchetypeSchemaPropertyIndexationService.sanitizeIdentifier("Hello-World"));
    }

    @Test
    void sanitizeIdentifier_truncatesTo30() {
      String longName = "a".repeat(50);
      assertEquals(
          30, ArchetypeSchemaPropertyIndexationService.sanitizeIdentifier(longName).length());
    }

    @Test
    void escapeJsonbKey_escapesSingleQuotes() {
      assertEquals("it''s", ArchetypeSchemaPropertyIndexationService.escapeJsonbKey("it's"));
    }

    @Test
    void escapeJsonbKey_escapesBackslashes() {
      assertEquals("a\\\\b", ArchetypeSchemaPropertyIndexationService.escapeJsonbKey("a\\b"));
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private ArchetypeEntity archetypeWithQueryableProps(
      String title, String propName, String propType) {
    UUID defId = UUID.randomUUID();
    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(defId);

    ObjectNode stmt = MAPPER.createObjectNode().put("title", title);
    ObjectNode props = stmt.putObject("properties");
    ObjectNode propNode = props.putObject(propName);
    propNode.put("type", propType);
    propNode.put("$gsm:queryable", true);

    ArchetypeEntity entity = mock(ArchetypeEntity.class);
    when(entity.getStatement()).thenReturn(stmt);
    when(entity.getDefinition()).thenReturn(def);

    return entity;
  }

  private ArchetypeEntity archetypeWithUniqueProps(String title, String propName, String propType) {
    UUID defId = UUID.randomUUID();
    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(defId);

    ObjectNode stmt = MAPPER.createObjectNode().put("title", title);
    ObjectNode props = stmt.putObject("properties");
    ObjectNode propNode = props.putObject(propName);
    propNode.put("type", propType);
    propNode.put("$gsm:unique", true);

    ArchetypeEntity entity = mock(ArchetypeEntity.class);
    when(entity.getStatement()).thenReturn(stmt);
    when(entity.getDefinition()).thenReturn(def);

    return entity;
  }

  private DefinitionEntity defWithId() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(UUID.randomUUID());
    return def;
  }
}
