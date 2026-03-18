package cloud.poesis.sie.defman.dto;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response for a GSM Definition (stable identity of a governed subject).
 */
@Schema(description = "Stable identity of a governed subject")
public record DefinitionDto(
        @Schema(description = "Definition ID (UUIDv7)") UUID id,
        @Schema(description = "GSM structural role (STRUCTURE, MECHANISM, EFFECTOR, RECEPTOR, INTERACTION, ARCHETYPE, NORM, DIRECTIVE)") String subjectType,
        @Schema(description = "Ordered ascription history for this definition") List<AscriptionDto> ascriptions) {
}
