package cloud.poesis.sie.defman.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

/**
 * Unified creation request for any GSM ascription type.
 *
 * <p>
 * The Archetype URI determines the GSM type and the expected statement schema.
 * FK references
 * (structureId, mechanismId, etc.) are part of the {@code statement} payload
 * and validated against
 * the archetype's JSON Schema.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Schema(name = "AscriptionCreation", description = "Creation request for a GSM ascription")
public class AscriptionCreationDto {

  @Schema(description = "Full $id URI of the typing Archetype")
  @NotNull
  private final String archetypeUri;

  @Schema(description = "JSON payload conforming to the Archetype's schema. "
      + "Query the Archetype ascription to discover the expected structure.", implementation = Map.class)
  @NotNull
  private final JsonNode statement;

  @Schema(description = "Optional: set for new ascription of existing definition")
  private final UUID definitionId;

  public AscriptionCreationDto(String archetypeUri, JsonNode statement, UUID definitionId) {
    this.archetypeUri = archetypeUri;
    this.statement = statement;
    this.definitionId = definitionId;
  }

  public String getArchetypeUri() {
    return archetypeUri;
  }

  public JsonNode getStatement() {
    return statement;
  }

  public UUID getDefinitionId() {
    return definitionId;
  }

  @JsonAnySetter
  public void rejectUnknownProperty(String name, JsonNode value) {
    throw new IllegalArgumentException("Unknown ascription creation property: " + name);
  }
}
