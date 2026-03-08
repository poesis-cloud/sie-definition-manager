package org.sif.sie.dm.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response for a lifecycle transition.
 */
public record TransitionResponse(
        UUID transitionId,
        UUID revisionId,
        String preStatus,
        String postStatus,
        Instant timestamp
) {}
