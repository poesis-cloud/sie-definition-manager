package cloud.poesis.sie.defman.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response for a lifecycle transition. */
@Schema(description = "Governance audit record of a lifecycle state change")
public record AscriptionStatusTransitionDto(
        @Schema(description = "Transition record ID (UUIDv7)") UUID transitionId,
        @Schema(description = "Ascription ID this transition belongs to") UUID ascriptionId,
        @Schema(description = "Status before the transition (null for initial creation)") String preStatus,
        @Schema(description = "Status after the transition") String postStatus,
        @Schema(description = "Timestamp of the transition (ISO 8601)") Instant timestamp) {
}
