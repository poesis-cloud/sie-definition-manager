package cloud.poesis.sie.defman.dto;

import java.time.Instant;
import java.util.UUID;

import org.springframework.hateoas.server.core.Relation;

import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Unified response for any GSM ascription.
 *
 * <p>
 * FK references are part of the {@code statement} payload — not flattened at
 * the envelope level. {@code subjectType} is derived server-side from the
 * archetype's schema title.
 */
@Relation(collectionRelation = "ascriptionResponseList")
@Schema(description = "Governed normative snapshot of a Definition")
public record AscriptionDto(
        UUID id,
        UUID definitionId,
        UUID archetypeId,
        String subjectType,
        @Schema(description = "Statement payload — structure depends on the referenced Archetype's JSON Schema. Query active Archetype ascriptions to discover the schema.", implementation = Object.class) JsonNode statement,
        int version,
        String status,
        Instant timestamp) {
}
