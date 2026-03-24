package cloud.poesis.sie.defman.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cloud.poesis.sie.defman.type.AscriptionStatusType;
import org.junit.jupiter.api.Test;

class AscriptionStatusTransitionCreationDtoTest {

  @Test
  void constructorAndGetter() {
    AscriptionStatusTransitionCreationDto dto =
        new AscriptionStatusTransitionCreationDto(AscriptionStatusType.PROPOSED);
    assertEquals(AscriptionStatusType.PROPOSED, dto.getTargetStatus());
  }
}
