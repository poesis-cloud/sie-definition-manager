package com.sif.sie.definitionmanager.controller.dto;

import jakarta.validation.constraints.NotNull;

/** Request to transition an ascription to a new lifecycle status. */
public record TransitionRequest(@NotNull String targetStatus) {}
