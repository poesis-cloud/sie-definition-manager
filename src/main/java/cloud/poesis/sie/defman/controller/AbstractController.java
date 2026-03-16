package cloud.poesis.sie.defman.controller;

import java.util.Iterator;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.poesis.sie.defman.dto.EmbeddedArchetypeDto;
import cloud.poesis.sie.defman.dto.AscriptionDto;
import cloud.poesis.sie.defman.dto.AscriptionStatusTransitionDto;
import cloud.poesis.sie.defman.dto.EmbeddedDefinitionDto;
import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import cloud.poesis.sie.defman.service.AbstractAscriptionService;

public abstract class AbstractController {

    private static final Logger log = LoggerFactory.getLogger(AbstractController.class);

    // ========================================================================
    // MAPPING
    // ========================================================================

    protected AscriptionDto toAscriptionDto(AscriptionEntity entity) {
        JsonNode statement = applyDataProtectionInTransit(entity);
        return new AscriptionDto(
                entity.getId(),
                entity.getDefinition().getId(),
                entity.getArchetype().getId(),
                statement,
                entity.getTimestamp(),
                entity.getVersion(),
                entity.getStatus().name());
    }

    protected EmbeddedDefinitionDto toEmbeddedDefinition(AscriptionEntity entity) {
        return new EmbeddedDefinitionDto(
                entity.getDefinition().getId(),
                entity.getDefinition().getSubjectType().getValue());
    }

    protected EmbeddedArchetypeDto toEmbeddedArchetype(AscriptionEntity entity) {
        ArchetypeEntity arch = entity.getArchetype();
        JsonNode archStatement = arch.getStatement();
        String title = null;
        if (archStatement != null && archStatement.has("title")) {
            title = archStatement.get("title").asText();
        } else {
            log.warn("Archetype {} has no title in statement — possible seed/migration issue",
                    arch.getId());
        }
        return new EmbeddedArchetypeDto(
                arch.getId(),
                arch.getDefinition().getId(),
                title);
    }

    /**
     * GSM §8: $gsm:dataProtection — apply inTransit measures to statement
     * properties before including in API responses.
     */
    private JsonNode applyDataProtectionInTransit(AscriptionEntity entity) {
        JsonNode statement = entity.getStatement();

        JsonNode schema = entity.getArchetype().getStatement();
        if (schema == null) {
            return statement;
        }
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return statement;
        }

        // Collect properties that declare inTransit protection
        boolean needsCopy = false;
        Iterator<String> fieldNames = properties.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            JsonNode propSchema = properties.get(fieldName);
            if (propSchema.has("$gsm:dataProtection") && statement.has(fieldName)) {
                JsonNode dp = propSchema.get("$gsm:dataProtection");
                if (dp.has("inTransit")) {
                    needsCopy = true;
                    break;
                }
            }
        }

        if (!needsCopy) {
            return statement;
        }

        // Deep-copy only when transformation is needed
        ObjectNode result = statement.deepCopy();
        fieldNames = properties.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            JsonNode propSchema = properties.get(fieldName);
            if (!propSchema.has("$gsm:dataProtection") || !result.has(fieldName)) {
                continue;
            }
            JsonNode dp = propSchema.get("$gsm:dataProtection");
            if (!dp.has("inTransit")) {
                continue;
            }

            JsonNode inTransit = dp.get("inTransit");
            JsonNode value = result.get(fieldName);
            String textValue = value.isTextual() ? value.asText() : value.toString();

            if (inTransit.has("encryption")) {
                throw new UnsupportedOperationException(
                        "$gsm:dataProtection inTransit.encryption is not yet supported");
            }

            if (inTransit.has("hash")) {
                String algorithm = "SHA-256";
                if (inTransit.get("hash").has("algorithm")) {
                    algorithm = inTransit.get("hash").get("algorithm").asText();
                }
                result.put(fieldName,
                        AbstractAscriptionService.computeHash(textValue, algorithm));
            }

            if (inTransit.has("mask")) {
                result.put(fieldName,
                        AbstractAscriptionService.applyMask(textValue, inTransit.get("mask")));
            }

            if (inTransit.has("suppression")) {
                result.remove(fieldName);
            }
        }
        return result;
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
