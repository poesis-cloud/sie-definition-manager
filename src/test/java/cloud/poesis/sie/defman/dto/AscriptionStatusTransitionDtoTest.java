package cloud.poesis.sie.defman.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cloud.poesis.sie.defman.type.AscriptionStatusType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AscriptionStatusTransitionDtoTest {

  @Test
  void constructorAndGetters() {
    UUID tid = UUID.randomUUID();
    UUID aid = UUID.randomUUID();
    Instant ts = Instant.now();
    AscriptionStatusTransitionDto dto =
        new AscriptionStatusTransitionDto(
            tid, aid, AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED, ts);

    assertEquals(tid, dto.getTransitionId());
    assertEquals(aid, dto.getAscriptionId());
    assertEquals(AscriptionStatusType.DRAFT, dto.getPreStatus());
    assertEquals(AscriptionStatusType.PROPOSED, dto.getPostStatus());
    assertEquals(ts, dto.getTimestamp());
  }
}
