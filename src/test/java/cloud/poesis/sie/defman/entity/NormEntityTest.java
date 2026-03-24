package cloud.poesis.sie.defman.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class NormEntityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void constructorSetsFields() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    StructureEntity struct = mock(StructureEntity.class);
    ArchetypeEntity qualifier = mock(ArchetypeEntity.class);

    NormEntity entity =
        new NormEntity(def, arch, MAPPER.createObjectNode(), struct, qualifier);
    assertEquals(struct, entity.getStructure());
    assertEquals(qualifier, entity.getQualifier());
  }

  @Test
  void constructorRejectsNullStructure() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    assertThrows(
        NullPointerException.class,
        () ->
            new NormEntity(
                def, arch, MAPPER.createObjectNode(), null, mock(ArchetypeEntity.class)));
  }

  @Test
  void constructorRejectsNullQualifier() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    assertThrows(
        NullPointerException.class,
        () ->
            new NormEntity(
                def, arch, MAPPER.createObjectNode(), mock(StructureEntity.class), null));
  }
}
