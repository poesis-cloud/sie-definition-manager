package cloud.poesis.sie.defman.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.poesis.sie.defman.type.AscriptionStatusType;

class AscriptionDtoTest {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  @Test
  void constructorAndGetters() {
    UUID id = UUID.randomUUID();
    ObjectNode stmt = MAPPER.createObjectNode().put("key", "val");
    Instant ts = Instant.now();
    AscriptionDto dto = new AscriptionDto(id, stmt, ts, AscriptionStatusType.ACTIVE, 3);

    assertEquals(id, dto.getId());
    assertEquals("val", dto.getStatement().get("key").asText());
    assertEquals(ts, dto.getTimestamp());
    assertEquals(AscriptionStatusType.ACTIVE, dto.getStatus());
    assertEquals(3, dto.getVersion());
    ObjectNode json = MAPPER.valueToTree(dto);
    assertFalse(json.has("archetypeSlug"));
    assertFalse(json.has("archetypeUri"));
  }
}
