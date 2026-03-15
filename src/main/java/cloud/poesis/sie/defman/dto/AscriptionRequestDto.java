package cloud.poesis.sie.defman.dto;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotNull;

/**
 * Unified creation request for any GSM ascription type.
 *
 * <p>
 * The archetype (via archetypeId) determines the GSM type and the expected
 * statement schema. FK references (structureId, mechanismId, etc.) are part
 * of the {@code statement} payload and validated against the archetype's
 * JSON Schema.
 */
public record AscriptionRequestDto(
                @NotNull UUID archetypeId,
                @NotNull JsonNode statement,
                UUID definitionId) // optional: for new ascription of existing definition
{
}
