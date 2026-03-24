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
 * <p>Definition and Archetype references are conveyed via HAL {@code _links} ({@code up} for
 * definition, {@code type} for archetype) rather than body fields — the link relation carries the
 * domain semantic.
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

  @Schema(description = "Governance lineage version (0 = not yet approved, 1+ = approved)")
  private final int version;

  @Schema(description = "Lifecycle status")
  private final AscriptionStatusType status;

  public AscriptionDto(
      UUID id, JsonNode statement, Instant timestamp, int version, AscriptionStatusType status) {
    this.id = id;
    this.statement = statement;
    this.timestamp = timestamp;
    this.version = version;
    this.status = status;
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

  public int getVersion() {
    return version;
  }

  public AscriptionStatusType getStatus() {
    return status;
  }
}
