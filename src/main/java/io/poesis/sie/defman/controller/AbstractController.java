package io.poesis.sie.defman.controller;

import java.util.Iterator;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import io.poesis.sie.defman.dto.AscriptionDto;
import io.poesis.sie.defman.dto.AscriptionStatusTransitionDto;
import io.poesis.sie.defman.entity.AscriptionEntity;
import io.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import io.poesis.sie.defman.type.DefinitionSubjectType;

public abstract class AbstractController {

    private static final Logger log = LoggerFactory.getLogger(AbstractController.class);
    private static final String REDACTED = "[REDACTED]";

    // ========================================================================
    // MAPPING
    // ========================================================================

    protected AscriptionDto toAscriptionDto(DefinitionSubjectType type, AscriptionEntity entity) {
        JsonNode statement = redactSensitiveFields(entity);
        return new AscriptionDto(
                entity.getId(),
                entity.getDefinition().getId(),
                entity.getArchetype().getId(),
                type.getValue(),
                statement,
                entity.getVersion(),
                entity.getStatus() != null ? entity.getStatus().name() : "DRAFT",
                entity.getTimestamp());
    }

    /**
     * GSM §8: $gsm:sensitive — redact sensitive properties from statement
     * before including in API responses.
     */
    private JsonNode redactSensitiveFields(AscriptionEntity entity) {
        JsonNode statement = entity.getStatement();
        if (statement == null || !statement.isObject()) {
            return statement;
        }

        JsonNode archetypeStatement = entity.getArchetype().getStatement();
        if (archetypeStatement == null) {
            return statement;
        }
        JsonNode schema = archetypeStatement.get("schema");
        if (schema == null) {
            return statement;
        }
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return statement;
        }

        // Find sensitive properties
        boolean hasSensitive = false;
        Iterator<String> fieldNames = properties.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            JsonNode propSchema = properties.get(fieldName);
            if (propSchema.has("$gsm:sensitive") && propSchema.get("$gsm:sensitive").asBoolean(false)) {
                if (statement.has(fieldName)) {
                    hasSensitive = true;
                    break;
                }
            }
        }

        if (!hasSensitive) {
            return statement;
        }

        // Deep-copy and redact
        ObjectNode redacted = statement.deepCopy();
        Iterator<String> redactFieldNames = properties.fieldNames();
        while (redactFieldNames.hasNext()) {
            String fieldName = redactFieldNames.next();
            JsonNode propSchema = properties.get(fieldName);
            if (propSchema.has("$gsm:sensitive") && propSchema.get("$gsm:sensitive").asBoolean(false)) {
                if (redacted.has(fieldName)) {
                    redacted.set(fieldName, TextNode.valueOf(REDACTED));
                }
            }
        }
        return redacted;
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
