package cloud.poesis.sie.defman.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructureEntityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void constructorSetsFields() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);

    StructureEntity entity =
        new StructureEntity(
            def, arch, MAPPER.createObjectNode().put("purpose", "order-processing"));
    assertNotNull(entity);
  }

  @Test
  void getMechanisms_nullFieldOutsideJpa() {
    DefinitionEntity def = mock(DefinitionEntity.class);
    ArchetypeEntity arch = mock(ArchetypeEntity.class);
    StructureEntity entity = new StructureEntity(def, arch, MAPPER.createObjectNode());

    // Outside JPA persistence context, the mechanisms field is null
    assertThrows(NullPointerException.class, () -> entity.getMechanisms());
  }

  @Test
  void getMechanisms_returnsUnmodifiableMechanismList() throws Exception {
    StructureEntity structure = new StructureEntity();
    List<MechanismEntity> mechanisms = new ArrayList<>();
    mechanisms.add(mock(MechanismEntity.class));

    Field mechanismsField = StructureEntity.class.getDeclaredField("mechanisms");
    mechanismsField.setAccessible(true);
    mechanismsField.set(structure, mechanisms);

    assertEquals(mechanisms, structure.getMechanisms());
    assertNotSame(mechanisms, structure.getMechanisms());
    assertThrows(UnsupportedOperationException.class, () -> structure.getMechanisms().add(null));
  }
}
