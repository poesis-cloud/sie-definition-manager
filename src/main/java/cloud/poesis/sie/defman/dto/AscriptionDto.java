package cloud.poesis.sie.defman.dto;

import java.time.Instant;
import java.util.UUID;

import org.springframework.hateoas.server.core.Relation;

import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Unified response for any GSM ascription.
 */
@Relation(collectionRelation = "ascriptionListResponse")
@Schema(description = "Governed normative snapshot of a Definition")
public record AscriptionDto(
        @Schema(description = "Ascription ID (UUIDv7, time-sortable)") UUID id,
        @Schema(description = "Definition ID — the stable identity this ascription is ascribed to") UUID definitionId,
        @Schema(description = "Definition ID of the typing Archetype") UUID archetypeId,
        @Schema(description = "Statement payload — typed by the embedded archetype's JSON Schema. "
                + "Follow the 'describedby' link for the full Archetype resource whose statement IS the schema, "
                + "or inspect _embedded.archetype.title or _embedded.definition.subjectType "
                + "for quick type discrimination.", implementation = Object.class) JsonNode statement,
        @Schema(description = "Authoritative creation timestamp (ISO 8601)") Instant timestamp,
        @Schema(description = "Governance lineage version (0 = not yet approved, 1+ = approved)") int version,
        @Schema(description = "Lifecycle status (DRAFT, PROPOSED, APPROVED, ACTIVE, SUSPENDED, DEPRECATED, RETIRED, ABANDONED, REJECTED)") String status) {
}
