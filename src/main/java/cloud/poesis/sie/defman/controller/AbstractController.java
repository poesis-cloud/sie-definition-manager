package cloud.poesis.sie.defman.controller;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.poesis.sie.defman.dto.AscriptionDto;
import cloud.poesis.sie.defman.dto.AscriptionStatusTransitionDto;
import cloud.poesis.sie.defman.dto.EmbeddedArchetypeDto;
import cloud.poesis.sie.defman.dto.EmbeddedDefinitionDto;
import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.GsmRuleViolationException;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.service.AbstractAscriptionService;
import cloud.poesis.sie.defman.type.GsmRuleType;

public abstract class AbstractController {

    private static final Logger log = LoggerFactory.getLogger(AbstractController.class);

    // ========================================================================
    // MAPPING
    // ========================================================================

    /**
     * Maps an ascription entity to its DTO, applying in-transit data protection.
     *
     * <p>
     * Explicit-fetch design — see README.md § "Explicit-fetch design
     * for lazy associations".
     * The archetype is passed explicitly (already fetched by the caller)
     * rather than navigated via {@code entity.getArchetype()}, which would
     * trigger a lazy-loading proxy exception.
     */
    protected AscriptionDto toAscriptionDto(AscriptionEntity entity, ArchetypeEntity archetype) {
        JsonNode statement = applyInTransitDataProtection(entity, archetype);
        return new AscriptionDto(
                entity.getId(),
                entity.getDefinition().getId(),
                entity.getArchetype().getId(),
                statement,
                entity.getTimestamp(),
                entity.getVersion(),
                entity.getStatus().name());
    }

    protected EmbeddedDefinitionDto toEmbeddedDefinitionDto(DefinitionEntity definition) {
        return new EmbeddedDefinitionDto(
                definition.getId(),
                definition.getSubjectType().getValue());
    }

    protected EmbeddedArchetypeDto toEmbeddedArchetypeDto(ArchetypeEntity archetype) {
        return new EmbeddedArchetypeDto(
                archetype.getId(),
                archetype.getDefinition().getId(),
                archetype.getStatement().get("title").asText());
    }

    /**
     * GSM §8: $gsm:dataProtection — apply inTransit measures to statement
     * properties before including in API responses.
     */
    private JsonNode applyInTransitDataProtection(AscriptionEntity entity, ArchetypeEntity archetype) {
        JsonNode statement = entity.getStatement();

        JsonNode schema = archetype.getStatement();
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
                // Encryption not yet implemented — silently skip
                continue;
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

    @ExceptionHandler(GsmRuleViolationException.class)
    ProblemDetail handleGsmRuleViolationException(GsmRuleViolationException ex) {
        HttpStatus status = resolveHttpStatus(ex);
        if (status.is5xxServerError()) {
            log.error("{}: {}", ex.getTitle(), ex.getMessage());
        } else {
            log.warn("{}: {}", ex.getTitle(), ex.getMessage());
        }
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        pd.setTitle(ex.getTitle());
        pd.setType(java.net.URI.create(ex.getType()));
        ex.getExtensions().forEach(pd::setProperty);
        return pd;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("{}: {}", ex.getTitle(), ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle(ex.getTitle());
        pd.setType(java.net.URI.create(ex.getType()));
        ex.getExtensions().forEach(pd::setProperty);
        return pd;
    }

    // Known FK constraint → GsmRuleType mapping (PostgreSQL auto-generated names)
    private static final Map<String, GsmRuleType> CONSTRAINT_TO_RULE = Map.ofEntries(
            // Directive reference FKs
            Map.entry("directive_structure_id_fkey", GsmRuleType.DIRECTIVE_STRUCTURE_REFERENCE_INTEGRITY),
            Map.entry("directive_qualifier_id_fkey", GsmRuleType.DIRECTIVE_QUALIFIER_REFERENCE_INTEGRITY),
            Map.entry("directive_purpose_id_fkey", GsmRuleType.DIRECTIVE_PURPOSE_REFERENCE_INTEGRITY),
            // Norm reference FKs
            Map.entry("norm_structure_id_fkey", GsmRuleType.NORM_STRUCTURE_REFERENCE_INTEGRITY),
            Map.entry("norm_qualifier_id_fkey", GsmRuleType.NORM_QUALIFIER_REFERENCE_INTEGRITY),
            // Effector / Receptor reference FKs
            Map.entry("effector_mechanism_id_fkey", GsmRuleType.EFFECTOR_MECHANISM_REFERENCE_INTEGRITY),
            Map.entry("receptor_mechanism_id_fkey", GsmRuleType.RECEPTOR_MECHANISM_REFERENCE_INTEGRITY),
            // Interaction reference FKs
            Map.entry("interaction_effector_id_fkey", GsmRuleType.INTERACTION_EFFECTOR_REFERENCE_INTEGRITY),
            Map.entry("interaction_receptor_id_fkey", GsmRuleType.INTERACTION_RECEPTOR_REFERENCE_INTEGRITY),
            // Archetype self-typing FK
            Map.entry("archetype_typed_by_fk", GsmRuleType.ASCRIPTION_ARCHETYPE_REFERENCE_INTEGRITY),
            // Identity uniqueness indexes
            Map.entry("uq_structure_purpose", GsmRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS),
            Map.entry("uq_mechanism_function", GsmRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS),
            Map.entry("uq_archetype_title", GsmRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS));

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String constraintName = extractConstraintName(ex);
        if (constraintName != null) {
            GsmRuleType ruleType = CONSTRAINT_TO_RULE.get(constraintName);
            if (ruleType == null && constraintName.endsWith("_archetype_id_fkey")) {
                ruleType = GsmRuleType.ASCRIPTION_ARCHETYPE_REFERENCE_INTEGRITY;
            }
            if (ruleType == null && (constraintName.endsWith("_output_archetype_id_fkey")
                    || constraintName.endsWith("_input_archetype_id_fkey"))) {
                ruleType = GsmRuleType.ASCRIPTION_ARCHETYPE_REFERENCE_INTEGRITY;
            }
            if (ruleType != null) {
                var mapped = GsmRuleViolationException.of(ruleType,
                        "Database constraint violation: " + constraintName,
                        "constraint", constraintName);
                return handleGsmRuleViolationException(mapped);
            }
        }
        log.error("Unmapped DB constraint violation (constraint={})", constraintName, ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Database constraint violation");
        pd.setTitle("Data integrity violation");
        return pd;
    }

    private static String extractConstraintName(DataIntegrityViolationException ex) {
        Throwable cause = ex;
        while (cause != null) {
            String msg = cause.getMessage();
            if (msg != null) {
                int idx = msg.indexOf("constraint \"");
                if (idx >= 0) {
                    int start = idx + "constraint \"".length();
                    int end = msg.indexOf('"', start);
                    if (end > start) {
                        return msg.substring(start, end);
                    }
                }
            }
            cause = cause.getCause();
        }
        return null;
    }

    private static HttpStatus resolveHttpStatus(GsmRuleViolationException ex) {
        return switch (ex.getRuleType()) {
            case ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
                    ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION,
                    DIRECTIVE_VERB_COMPATIBILITY,
                    DIRECTIVE_MODAL_COMPATIBILITY,
                    ASCRIPTION_STATUS_TRANSITION_PATH,
                    ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
                    ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_CONSTITUENTS,
                    ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_SUBJECTS,
                    ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_DEPENDENTS,
                    ASCRIPTION_STATUS_TRANSITION_APPROVAL_CONVERGENCE,
                    ASCRIPTION_STATUS_TRANSITION_ACTIVATION_HANDOFF,
                    ASCRIPTION_STATUS_TRANSITION_TERMINAL_IMMUTABILITY ->
                HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

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
