package cloud.poesis.sie.defman.dto;

import java.time.Instant;
import java.util.UUID;

/** Response for a lifecycle transition. */
public record AscriptionStatusTransitionDto(
                UUID transitionId, UUID ascriptionId, String preStatus, String postStatus, Instant timestamp) {
}
