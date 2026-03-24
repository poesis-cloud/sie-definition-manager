package cloud.poesis.sie.defman.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cloud.poesis.sie.defman.type.AscriptionStatusType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AscriptionDtoTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void constructorAndGetters() {
    UUID id = UUID.randomUUID();
    ObjectNode stmt = MAPPER.createObjectNode().put("key", "val");
    Instant ts = Instant.now();
    AscriptionDto dto = new AscriptionDto(id, stmt, ts, 3, AscriptionStatusType.ACTIVE);

    assertEquals(id, dto.getId());
    assertEquals("val", dto.getStatement().get("key").asText());
    assertEquals(ts, dto.getTimestamp());
    assertEquals(3, dto.getVersion());
    assertEquals(AscriptionStatusType.ACTIVE, dto.getStatus());
  }
}
