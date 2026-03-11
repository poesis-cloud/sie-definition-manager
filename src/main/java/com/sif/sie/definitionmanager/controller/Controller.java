package com.sif.sie.definitionmanager.controller;

import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.sif.sie.definitionmanager.dto.AscriptionDto;
import com.sif.sie.definitionmanager.dto.AscriptionStatusTransitionDto;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
import com.sif.sie.definitionmanager.entity.AscriptionEntity;
import com.sif.sie.definitionmanager.entity.AscriptionStatusTransitionEntity;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;

public abstract class Controller {

    private static final Logger log = LoggerFactory.getLogger(Controller.class);

    // ========================================================================
    // MAPPING
    // ========================================================================

    protected AscriptionDto toAscriptionDto(DefinitionSubjectType type, AscriptionEntity entity) {
        String schemaUri = null;
        if (type == DefinitionSubjectType.ARCHETYPE) {
            schemaUri = ((ArchetypeEntity) entity).getSchemaUri();
        }
        return new AscriptionDto(
                type.getValue(),
                entity.getId(),
                entity.getDefinition().getId(),
                entity.getArchetype().getId(),
                entity.getCompilation(),
                entity.getVersion(),
                entity.getStatus() != null ? entity.getStatus().name() : "DRAFT",
                schemaUri,
                entity.getTimestamp());
    }

    protected AscriptionStatusTransitionDto toTransitionDto(AscriptionStatusTransitionEntity t) {
        return new AscriptionStatusTransitionDto(
                t.getId(),
                t.getAscription().getId(),
                t.getPreStatus() != null ? t.getPreStatus().name() : null,
                t.getPostStatus().name(),
                t.getTimestamp());
    }

    // ========================================================================
    // EXCEPTION HANDLERS
    // ========================================================================

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Invalid request");
        return pd;
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail handleNotFound(NoSuchElementException ex) {
        log.warn("Not found: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Not found");
        return pd;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException ex) {
        log.warn("Conflict: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Conflict");
        return pd;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error");
        pd.setTitle("Internal server error");
        return pd;
    }
}
