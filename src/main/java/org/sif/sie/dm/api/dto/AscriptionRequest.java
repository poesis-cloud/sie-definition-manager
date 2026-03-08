package org.sif.sie.dm.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Unified creation request for any GSM ascription type.
 * Entity-specific FK fields are used only when relevant to the gsmType.
 */
public record AscriptionRequest(
        @NotNull String gsmType,
        UUID id,
        @NotNull UUID archetypeId,
        @NotNull JsonNode definition,
        // entity-specific FK references (nullable, used per gsmType)
        UUID structureId,
        UUID mechanismId,
        UUID portArchetypeId,
        UUID interfaceId,
        UUID qualifierId,
        UUID purposeId,
        UUID effectorId,
        UUID receptorId
) {}
