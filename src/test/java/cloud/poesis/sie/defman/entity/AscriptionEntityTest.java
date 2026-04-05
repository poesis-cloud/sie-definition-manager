package cloud.poesis.sie.defman.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AscriptionEntityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void constructorRejectsNullDefinition() {
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    assertThrows(
        NullPointerException.class,
        () -> new ArchetypeEntity(null, arch, MAPPER.createObjectNode()));
  }

  @Test
  void constructorRejectsNullArchetype() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    assertThrows(
        NullPointerException.class,
        () -> new ArchetypeEntity(def, null, MAPPER.createObjectNode()));
  }

  @Test
  void constructorRejectsNullStatement() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    assertThrows(NullPointerException.class, () -> new ArchetypeEntity(def, arch, null));
  }

  @Test
  void gettersReturnConstructedValues() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    ObjectNode stmt = MAPPER.createObjectNode().put("title", "Test");

    ArchetypeEntity entity = new ArchetypeEntity(def, arch, stmt);
    assertEquals(def, entity.getDefinition());
    assertEquals(arch, entity.getArchetype());
    assertEquals("Test", entity.getStatement().get("title").asText());
    // Id, status, timestamp are DB-generated → null/default
    assertNull(entity.getId());
    assertNull(entity.getStatus());
    assertNull(entity.getTimestamp());
  }

  @Test
  void equalsAndHashCode_sameId() throws Exception {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    ArchetypeEntity a = new ArchetypeEntity(def, arch, MAPPER.createObjectNode());
    ArchetypeEntity b = new ArchetypeEntity(def, arch, MAPPER.createObjectNode());
    UUID id = UUID.randomUUID();
    Field idField = AscriptionEntity.class.getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(a, id);
    idField.set(b, id);

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void equals_bothNullId_notEqual() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    ArchetypeEntity a = new ArchetypeEntity(def, arch, MAPPER.createObjectNode());
    ArchetypeEntity b = new ArchetypeEntity(def, arch, MAPPER.createObjectNode());
    assertFalse(a.equals(b));
  }

  @Test
  void equals_sameInstance() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    ArchetypeEntity a = new ArchetypeEntity(def, arch, MAPPER.createObjectNode());
    assertTrue(a.equals(a));
  }

  @Test
  void equals_null() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    ArchetypeEntity a = new ArchetypeEntity(def, arch, MAPPER.createObjectNode());
    assertFalse(a.equals(null));
  }

  @Test
  void equals_differentClass() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    ArchetypeEntity a = new ArchetypeEntity(def, arch, MAPPER.createObjectNode());
    assertNotEquals("string", a);
  }
}
