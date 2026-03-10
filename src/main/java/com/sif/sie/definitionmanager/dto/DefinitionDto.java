package com.sif.sie.definitionmanager.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response for a GSM Definition (stable identity of a governed subject).
 */
public record DefinitionDto(UUID id, String subjectType, List<AscriptionDto> ascriptions) {
}
