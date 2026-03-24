package cloud.poesis.sie.defman.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class EffectorEntityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void constructorSetsFields() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    MechanismEntity mech = mock(MechanismEntity.class);
    ArchetypeEntity outputArch = mock(ArchetypeEntity.class);

    EffectorEntity entity =
        new EffectorEntity(def, arch, MAPPER.createObjectNode(), mech, outputArch);
    assertEquals(mech, entity.getMechanism());
    assertEquals(outputArch, entity.getOutputArchetype());
  }

  @Test
  void constructorRejectsNullMechanism() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    assertThrows(
        NullPointerException.class,
        () ->
            new EffectorEntity(
                def, arch, MAPPER.createObjectNode(), null, mock(ArchetypeEntity.class)));
  }

  @Test
  void constructorRejectsNullOutputArchetype() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    assertThrows(
        NullPointerException.class,
        () ->
            new EffectorEntity(
                def, arch, MAPPER.createObjectNode(), mock(MechanismEntity.class), null));
  }
}
