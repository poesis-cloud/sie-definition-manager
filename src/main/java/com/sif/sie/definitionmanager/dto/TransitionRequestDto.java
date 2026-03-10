package com.sif.sie.definitionmanager.dto;

import jakarta.validation.constraints.NotNull;

/** Request to transition an ascription to a new lifecycle status. */
public record TransitionRequestDto(@NotNull String targetStatus) {}
