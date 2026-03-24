package cloud.poesis.sie.defman.dto;

import cloud.poesis.sie.defman.type.AscriptionStatusType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Request to transition an ascription to a new lifecycle status.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Schema(name = "AscriptionStatusTransitionCreation", description = "Request to transition an ascription to a new lifecycle status")
public class AscriptionStatusTransitionCreationDto {

    @Schema(description = "Target lifecycle status")
    @NotNull
    private final AscriptionStatusType targetStatus;

    public AscriptionStatusTransitionCreationDto(AscriptionStatusType targetStatus) {
        this.targetStatus = targetStatus;
    }

    public AscriptionStatusType getTargetStatus() {
        return targetStatus;
    }
}
