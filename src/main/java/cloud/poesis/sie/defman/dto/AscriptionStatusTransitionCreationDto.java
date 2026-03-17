package cloud.poesis.sie.defman.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Request to transition an ascription to a new lifecycle status. */
@Schema(description = "Request to transition an ascription to a new lifecycle status")
public record AscriptionStatusTransitionCreationDto(
        @Schema(description = "Target lifecycle status (PROPOSED, APPROVED, ACTIVE, SUSPENDED, DEPRECATED, RETIRED, ABANDONED, REJECTED)") @NotNull String targetStatus) {
}
