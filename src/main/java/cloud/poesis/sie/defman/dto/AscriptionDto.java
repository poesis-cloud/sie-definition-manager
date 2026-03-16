package cloud.poesis.sie.defman.dto;

import java.time.Instant;
import java.util.UUID;

import org.springframework.hateoas.server.core.Relation;

import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Unified response for any GSM ascription.
 */
@Relation(collectionRelation = "ascriptionResponseList")
@Schema(description = "Governed normative snapshot of a Definition")
public record AscriptionDto(
        UUID id,
        UUID definitionId,
        UUID archetypeId,
        @Schema(description = "Statement payload — typed by the embedded archetype's JSON Schema. "
                + "Follow the 'describedby' link for the full Archetype resource whose statement IS the schema, "
                + "or inspect _embedded.archetype.title or _embedded.definition.subjectType "
                + "for quick type discrimination.", implementation = Object.class) JsonNode statement,
        Instant timestamp,
        int version,
        String status) {
}
