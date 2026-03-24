package cloud.poesis.sie.defman.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class InteractionEntityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void constructorSetsFields() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    EffectorEntity eff = mock(EffectorEntity.class);
    ReceptorEntity rec = mock(ReceptorEntity.class);

    InteractionEntity entity =
        new InteractionEntity(def, arch, MAPPER.createObjectNode(), eff, rec);
    assertEquals(eff, entity.getEffector());
    assertEquals(rec, entity.getReceptor());
  }

  @Test
  void constructorRejectsNullEffector() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    assertThrows(
        NullPointerException.class,
        () ->
            new InteractionEntity(
                def, arch, MAPPER.createObjectNode(), null, mock(ReceptorEntity.class)));
  }

  @Test
  void constructorRejectsNullReceptor() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    assertThrows(
        NullPointerException.class,
        () ->
            new InteractionEntity(
                def, arch, MAPPER.createObjectNode(), mock(EffectorEntity.class), null));
  }
}
