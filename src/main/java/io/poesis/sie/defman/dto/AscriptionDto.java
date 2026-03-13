package io.poesis.sie.defman.dto;

import java.time.Instant;
import java.util.UUID;

import org.springframework.hateoas.server.core.Relation;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Unified response for any GSM ascription.
 *
 * <p>
 * FK references are part of the {@code statement} payload — not flattened at
 * the envelope level. {@code subjectType} is derived server-side from the
 * archetype's schema title.
 */
@Relation(collectionRelation = "ascriptionResponseList")
public record AscriptionDto(
        UUID id,
        UUID definitionId,
        UUID archetypeId,
        String subjectType,
        JsonNode statement,
        int version,
        String status,
        Instant timestamp) {
}
