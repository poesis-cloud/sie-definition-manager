package com.sif.sie.definitionmanager.controller.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
  * Unified response for any GSM ascription.
  *
  * <p>FK references are part of the {@code definition} payload — not flattened at the envelope
  * level. {@code gsmType} is derived server-side from the archetype's schema URI.
  */
public record AscriptionResponse(
        String gsmType,
        UUID id,
        UUID revisionId,
        Instant revisionTimestamp,
        UUID archetypeId,
        JsonNode definition,
        Integer version,
        String status,
        String schemaUri) {}
