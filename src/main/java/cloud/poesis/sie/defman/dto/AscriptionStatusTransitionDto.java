package cloud.poesis.sie.defman.dto;

import cloud.poesis.sie.defman.type.AscriptionStatusType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.springframework.hateoas.server.core.Relation;

/**
 * Response for a lifecycle transition audit record.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Relation(collectionRelation = "ascriptionStatusTransitions")
@Schema(
    name = "AscriptionStatusTransition",
    description = "Governance audit record of a lifecycle state change of an Ascription")
public class AscriptionStatusTransitionDto {

  @Schema(description = "Transition record ID (UUIDv7)")
  private final UUID transitionId;

  @Schema(description = "Ascription ID this transition belongs to")
  private final UUID ascriptionId;

  @Schema(description = "Status before the transition")
  private final AscriptionStatusType preStatus;

  @Schema(description = "Status after the transition")
  private final AscriptionStatusType postStatus;

  @Schema(description = "Timestamp of the transition (ISO 8601)")
  private final Instant timestamp;

  public AscriptionStatusTransitionDto(
      UUID transitionId,
      UUID ascriptionId,
      AscriptionStatusType preStatus,
      AscriptionStatusType postStatus,
      Instant timestamp) {
    this.transitionId = transitionId;
    this.ascriptionId = ascriptionId;
    this.preStatus = preStatus;
    this.postStatus = postStatus;
    this.timestamp = timestamp;
  }

  public UUID getTransitionId() {
    return transitionId;
  }

  public UUID getAscriptionId() {
    return ascriptionId;
  }

  public AscriptionStatusType getPreStatus() {
    return preStatus;
  }

  public AscriptionStatusType getPostStatus() {
    return postStatus;
  }

  public Instant getTimestamp() {
    return timestamp;
  }
}
