package cloud.poesis.sie.defman.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Request to transition an ascription to a new lifecycle status.
 *
 * @param targetStatus the target lifecycle status (e.g. {@code PROPOSED},
 *                     {@code APPROVED}, {@code ACTIVE})
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Schema(description = "Request to transition an ascription to a new lifecycle status")
public record AscriptionStatusTransitionCreationDto(
        @Schema(description = "Target lifecycle status (PROPOSED, APPROVED, ACTIVE, SUSPENDED, DEPRECATED, RETIRED, ABANDONED, REJECTED)") @NotNull String targetStatus) {
}
