package org.sif.sie.dm.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request to transition an ascription to a new lifecycle status.
 */
public record TransitionRequest(
        @NotNull String targetStatus
) {}
