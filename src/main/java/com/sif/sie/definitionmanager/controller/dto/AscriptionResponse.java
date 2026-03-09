package com.sif.sie.definitionmanager.controller.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Unified response for any GSM ascription.
 *
 * <p>
 * FK references are part of the {@code statement} payload — not flattened at
 * the envelope
 * level. {@code subjectType} is derived server-side from the archetype's schema
 * URI.
 */
public record AscriptionResponse(
    String subjectType,
    UUID definitionId,
    UUID id,
    Instant timestamp,
    UUID archetypeId,
    JsonNode statement,
    int version,
    String status,
    String schemaUri) {
}
