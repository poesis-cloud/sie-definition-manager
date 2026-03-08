package org.sif.sie.dm.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Unified response for any GSM ascription.
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
        // archetype-specific
        String schemaUri,
        // entity-specific FK references (null when not applicable)
        UUID structureId,
        UUID mechanismId,
        UUID portArchetypeId,
        UUID interfaceId,
        UUID qualifierId,
        UUID purposeId,
        UUID effectorId,
        UUID receptorId
) {}
