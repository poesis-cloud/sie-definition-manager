package cloud.poesis.sie.defman.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MechanismEntityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void constructorSetsFields() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    StructureEntity struct = mock(StructureEntity.class);

    MechanismEntity entity =
        new MechanismEntity(
            def, arch, MAPPER.createObjectNode().put("function", "validate"), struct);
    assertEquals(struct, entity.getStructure());
  }

  @Test
  void constructorRejectsNullStructure() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    assertThrows(
        NullPointerException.class,
        () -> new MechanismEntity(def, arch, MAPPER.createObjectNode(), null));
  }
}
