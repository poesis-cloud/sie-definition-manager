package cloud.poesis.sie.defman.dto;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Unified creation request for any GSM ascription type.
 *
 * <p>
 * The archetype (via archetypeId) determines the GSM type and the expected
 * statement schema. FK references (structureId, mechanismId, etc.) are part
 * of the {@code statement} payload and validated against the archetype's
 * JSON Schema.
 *
 * @param archetypeId  Definition ID of the typing Archetype
 * @param statement    JSON payload conforming to the Archetype's schema
 * @param definitionId optional Definition ID when creating a new Ascription
 *                     for an existing Definition
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Schema(description = "Creation request for a GSM ascription")
public record AscriptionCreationDto(
        @Schema(description = "Definition ID of the typing Archetype") @NotNull UUID archetypeId,
        @Schema(description = "JSON payload conforming to the Archetype's schema. Query the Archetype ascription to discover the expected structure.", implementation = Object.class) @NotNull JsonNode statement,
        @Schema(description = "Optional: set for new ascription of existing definition") UUID definitionId) {
}
