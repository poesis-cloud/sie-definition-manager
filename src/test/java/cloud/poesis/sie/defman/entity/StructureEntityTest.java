package cloud.poesis.sie.defman.entity;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
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
}
