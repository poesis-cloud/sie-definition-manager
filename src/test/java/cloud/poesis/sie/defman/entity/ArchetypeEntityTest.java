package cloud.poesis.sie.defman.entity;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ArchetypeEntityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void constructorSetsFields() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);

    ArchetypeEntity entity = new ArchetypeEntity(def, arch, MAPPER.createObjectNode().put("title", "TestArchetype"));
    assertNotNull(entity);
  }

  @Test
  void constructorRejectsNullDefinition() {
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    assertThrows(
        NullPointerException.class,
        () -> new ArchetypeEntity(null, arch, MAPPER.createObjectNode()));
  }
}
