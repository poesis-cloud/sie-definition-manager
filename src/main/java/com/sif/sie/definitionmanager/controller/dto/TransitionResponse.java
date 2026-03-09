package com.sif.sie.definitionmanager.controller.dto;

import java.time.Instant;
import java.util.UUID;

/** Response for a lifecycle transition. */
public record TransitionResponse(
                UUID transitionId, UUID ascriptionId, String preStatus, String postStatus, Instant timestamp) {
}
