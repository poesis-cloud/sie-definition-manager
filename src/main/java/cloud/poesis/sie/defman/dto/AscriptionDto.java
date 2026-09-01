package cloud.poesis.sie.defman.dto;

import cloud.poesis.sie.defman.type.AscriptionStatusType;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.hateoas.server.core.Relation;

/**
 * Unified response for any GSM ascription.
 *
 * <p>Definition and Archetype references are conveyed via HAL links rather than body fields.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Relation(collectionRelation = "ascriptions")
@Schema(name = "Ascription", description = "Governed normative snapshot of a Definition")
public class AscriptionDto {

  @Schema(description = "Ascription ID (UUIDv7, time-sortable)")
  private final UUID id;

  @Schema(
      description =
          "Statement payload — typed by the Archetype's JSON Schema. "
              + "Follow 'describedby' for the composed envelope schema with the Archetype schema inlined. "
              + "Follow 'type' link for the typing Archetype. "
              + "Follow 'up' link for the parent Definition.",
      implementation = Map.class)
  private final JsonNode statement;

  @Schema(description = "Authoritative creation timestamp (ISO 8601)")
  private final Instant timestamp;

  @Schema(description = "Lifecycle status")
  private final AscriptionStatusType status;

  @Schema(description = "Definition-scoped governance version; 0 before approval", minimum = "0")
  private final int version;

  public AscriptionDto(
      UUID id, JsonNode statement, Instant timestamp, AscriptionStatusType status, int version) {
    this.id = id;
    this.statement = statement;
    this.timestamp = timestamp;
    this.status = status;
    this.version = version;
  }

  public UUID getId() {
    return id;
  }

  public JsonNode getStatement() {
    return statement;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public AscriptionStatusType getStatus() {
    return status;
  }

  public int getVersion() {
    return version;
  }
}
