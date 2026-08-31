package cloud.poesis.sie.defman.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AscriptionCreationDtoTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void constructorAndGetters() {
    String archetypeUri = "gsmarc://gsm/Structure/v1";
    UUID definitionId = UUID.randomUUID();
    ObjectNode stmt = MAPPER.createObjectNode().put("purpose", "test");
    AscriptionCreationDto dto = new AscriptionCreationDto(archetypeUri, stmt, definitionId);

    assertEquals(archetypeUri, dto.getArchetypeUri());
    assertEquals("test", dto.getStatement().get("purpose").asText());
    assertEquals(definitionId, dto.getDefinitionId());
  }

  @Test
  void nullDefinitionId() {
    String archetypeUri = "gsmarc://gsm/Structure/v1";
    ObjectNode stmt = MAPPER.createObjectNode();
    AscriptionCreationDto dto = new AscriptionCreationDto(archetypeUri, stmt, null);

    assertNull(dto.getDefinitionId());
    assertEquals(archetypeUri, dto.getArchetypeUri());
  }
}
