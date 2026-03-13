package io.poesis.sie.defman.dto;

import jakarta.validation.constraints.NotNull;

/** Request to transition an ascription to a new lifecycle status. */
public record AscriptionStatusTransitionRequestDto(@NotNull String targetStatus) {}
