package cloud.poesis.sie.defman.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefinitionEntityTest {

  @Test
  void constructorSetsSubjectType() {
    DefinitionEntity def = new DefinitionEntity(DefinitionSubjectType.STRUCTURE);
    assertEquals(DefinitionSubjectType.STRUCTURE, def.getSubjectType());
    assertNull(def.getId());
  }

  @Test
  void constructorRejectsNull() {
    assertThrows(NullPointerException.class, () -> new DefinitionEntity(null));
  }

  @Test
  void equalsAndHashCode_sameId() throws Exception {
    DefinitionEntity a = new DefinitionEntity(DefinitionSubjectType.STRUCTURE);
    DefinitionEntity b = new DefinitionEntity(DefinitionSubjectType.STRUCTURE);
    UUID id = UUID.randomUUID();
    Field idField = DefinitionEntity.class.getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(a, id);
    idField.set(b, id);

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void equals_bothNullId_notEqual() {
    DefinitionEntity a = new DefinitionEntity(DefinitionSubjectType.STRUCTURE);
    DefinitionEntity b = new DefinitionEntity(DefinitionSubjectType.STRUCTURE);
    assertFalse(a.equals(b));
  }

  @Test
  void equals_sameInstance() {
    DefinitionEntity a = new DefinitionEntity(DefinitionSubjectType.STRUCTURE);
    assertTrue(a.equals(a));
  }

  @Test
  void equals_null() {
    DefinitionEntity a = new DefinitionEntity(DefinitionSubjectType.STRUCTURE);
    assertFalse(a.equals(null));
  }

  @Test
  void equals_differentClass() {
    DefinitionEntity a = new DefinitionEntity(DefinitionSubjectType.STRUCTURE);
    assertNotEquals("string", a);
  }

  @Test
  void getAscriptions_returnsRawList() {
    DefinitionEntity def = new DefinitionEntity(DefinitionSubjectType.ARCHETYPE);
    // Outside persistence context, ascriptions field may be null
    List<AscriptionEntity> list = def.getAscriptions();
    // Just verify no exception; may be null
    assertNull(list);
  }
}
