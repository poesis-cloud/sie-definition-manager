package com.sif.sie.definitionmanager.controller.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
  * Unified creation request for any GSM ascription type.
  *
  * <p>The archetype (via archetypeId) determines the GSM type and the expected definition schema. FK
  * references (structureId, mechanismId, etc.) are part of the {@code definition} payload and
  * validated against the archetype's JSON Schema.
  */
public record AscriptionRequest(
        @NotNull UUID archetypeId,
        @NotNull JsonNode definition,
        UUID id // optional: for new revision of existing lineage
        ) {}
