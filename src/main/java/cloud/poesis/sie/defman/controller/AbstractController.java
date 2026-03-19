package cloud.poesis.sie.defman.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.fasterxml.jackson.databind.JsonNode;

import cloud.poesis.sie.defman.dto.AscriptionDto;
import cloud.poesis.sie.defman.dto.AscriptionStatusTransitionDto;
import cloud.poesis.sie.defman.dto.EmbeddedArchetypeDto;
import cloud.poesis.sie.defman.dto.EmbeddedDefinitionDto;
import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.GsmInternalException;
import cloud.poesis.sie.defman.exception.GsmResourceNotFoundException;
import cloud.poesis.sie.defman.exception.GsmRuleViolationException;
import cloud.poesis.sie.defman.service.DataProtectionService;

public abstract class AbstractController {

    private static final Logger log = LoggerFactory.getLogger(AbstractController.class);

    private final DataProtectionService dataProtectionService;

    protected AbstractController(DataProtectionService dataProtectionService) {
        this.dataProtectionService = dataProtectionService;
    }

    // ========================================================================
    // ENTITY -> DTO MAPPING
    // ========================================================================

    /**
     * Maps an ascription entity to its DTO, applying in-transit data
     * protection.
     *
     * <p>
     * Explicit-fetch design — see README.md § "Explicit-fetch design
     * for lazy associations".
     * The archetype is passed explicitly (already fetched by the caller)
     * rather than navigated via {@code ascription.getArchetype()}, which would
     * trigger a lazy-loading proxy exception.
     */
    protected AscriptionDto toAscriptionDto(AscriptionEntity ascription, ArchetypeEntity archetype) {
        JsonNode statement = dataProtectionService.applyInTransitProtection(
                ascription.getStatement(), archetype.getStatement());
        return new AscriptionDto(
                ascription.getId(),
                ascription.getDefinition().getId(),
                ascription.getArchetype().getId(),
                statement,
                ascription.getTimestamp(),
                ascription.getVersion(),
                ascription.getStatus().name());
    }

    protected AscriptionStatusTransitionDto toAscriptionStatusTransitionDto(
            AscriptionStatusTransitionEntity ascriptionStatusTransition) {
        return new AscriptionStatusTransitionDto(
                ascriptionStatusTransition.getId(),
                ascriptionStatusTransition.getAscription().getId(),
                ascriptionStatusTransition.getPreStatus() != null
                        ? ascriptionStatusTransition.getPreStatus().name()
                        : null,
                ascriptionStatusTransition.getPostStatus().name(),
                ascriptionStatusTransition.getTimestamp());
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

    // ========================================================================
    // EXCEPTION -> PROBLEM DETAIL MAPPING
    // ========================================================================

    private static HttpStatus resolveHttpStatus(GsmRuleViolationException exception) {
        return switch (exception.getRuleType()) {
            case STRUCTURE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                    STRUCTURE_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE,
                    MECHANISM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                    MECHANISM_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE,
                    MECHANISM_RULE_STARLARK_PARSING,
                    MECHANISM_RULE_STARLARK_BUDGET,
                    MECHANISM_RULE_STARLARK_CONSTRUCT_BLACKLIST,
                    MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST,
                    MECHANISM_RULE_TRIGGER_AS_FIRST_STATEMENT,
                    MECHANISM_RULE_TRIGGER_AS_UNIQUE_STATEMENT,
                    MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE,
                    MECHANISM_RULE_SYS_EMIT_METHOD_ARITY,
                    MECHANISM_RULE_SYS_EMIT_METHOD_RESPONSE,
                    MECHANISM_RULE_SYS_CREATE_METHOD_ARITY,
                    MECHANISM_RULE_SYS_MODIFY_METHOD_ARITY,
                    MECHANISM_RULE_SYS_DELETE_METHOD_ARITY,
                    MECHANISM_RULE_SYS_ACQUIRE_METHOD_ARITY,
                    EFFECTOR_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                    EFFECTOR_MECHANISM_REFERENCE_INTEGRITY,
                    RECEPTOR_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                    RECEPTOR_MECHANISM_REFERENCE_INTEGRITY,
                    INTERACTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                    INTERACTION_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE,
                    INTERACTION_EFFECTOR_REFERENCE_INTEGRITY,
                    INTERACTION_RECEPTOR_REFERENCE_INTEGRITY,
                    INTERACTION_EFFECTOR_RECEPTOR_COMPATIBILITY,
                    ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
                    ASCRIPTION_ARCHETYPE_REFERENCE_INTEGRITY,
                    DIRECTIVE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                    DIRECTIVE_STRUCTURE_REFERENCE_INTEGRITY,
                    DIRECTIVE_PURPOSE_REFERENCE_INTEGRITY,
                    DIRECTIVE_QUALIFIER_REFERENCE_INTEGRITY,
                    NORM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                    NORM_STRUCTURE_REFERENCE_INTEGRITY,
                    NORM_QUALIFIER_REFERENCE_INTEGRITY,
                    NORM_GUARD_CEL_PARSING,
                    NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM,
                    NORM_GUARD_COMPARISON_CONSISTENCY,
                    NORM_GUARD_ARCHETYPE_REFERENCE_RESOLUTION,
                    NORM_GUARD_PROPERTY_PATH_RESOLUTION,
                    NORM_PREDICATE_CEL_PARSING,
                    NORM_PREDICATE_AS_DETERMINISTIC_EXPRESSION,
                    NORM_PREDICATE_AS_ARCHETYPE_BOUND_EXPRESSION,
                    NORM_PREDICATE_AS_BOOLEAN_RESULT,
                    NORM_PREDICATE_PROPERTY_PATH_RESOLUTION,
                    NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY,
                    ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
                    ARCHETYPE_ALLOF_CHAIN_EXCLUSIVE_BASE_CONVERGENCE,
                    ARCHETYPE_ALLOF_CHAIN_ACYCLICITY,
                    ARCHETYPE_ALLOF_SEAL,
                    ARCHETYPE_ANNOTATION_QUERYABLE,
                    ARCHETYPE_ANNOTATION_DATA_PROTECTION,
                    ARCHETYPE_ANNOTATION_IDENTITY_BOUND_SET_IMMUTABILITY,
                    ARCHETYPE_VALIDATION_CEL_PARSING,
                    ARCHETYPE_VALIDATION_CEL_CONSTRUCT_BLACKLIST,
                    ARCHETYPE_VALIDATION_CEL_THIS_ROOT_BINDING,
                    ARCHETYPE_VALIDATION_CEL_BOOLEAN_RESULT ->
                HttpStatus.BAD_REQUEST;
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
        };
    }

    @ExceptionHandler(GsmRuleViolationException.class)
    ProblemDetail handleGsmRuleViolationException(GsmRuleViolationException exception) {
        HttpStatus status = resolveHttpStatus(exception);
        if (status.is5xxServerError()) {
            log.error("{}: {}", exception.getTitle(), exception.getMessage(), exception);
        } else {
            log.warn("{}: {}", exception.getTitle(), exception.getMessage());
        }
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problemDetail.setTitle(exception.getTitle());
        problemDetail.setType(java.net.URI.create(exception.getType()));
        exception.getExtensions().forEach(problemDetail::setProperty);
        return problemDetail;
    }

    @ExceptionHandler(GsmResourceNotFoundException.class)
    ProblemDetail handleResourceNotFound(GsmResourceNotFoundException exception) {
        log.warn("{}: {}", exception.getTitle(), exception.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problemDetail.setTitle(exception.getTitle());
        problemDetail.setType(java.net.URI.create(exception.getType()));
        exception.getExtensions().forEach(problemDetail::setProperty);
        return problemDetail;
    }

    @ExceptionHandler(GsmInternalException.class)
    ProblemDetail handleGsmInternal(GsmInternalException exception) {
        log.error("{}: {}", exception.getTitle(), exception.getMessage(), exception);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        problemDetail.setTitle(exception.getTitle());
        problemDetail.setType(java.net.URI.create(exception.getType()));
        exception.getExtensions().forEach(problemDetail::setProperty);
        return problemDetail;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleGeneric(Exception exception) {
        log.error("Unexpected error", exception);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error");
        problemDetail.setTitle("Internal server error");
        return problemDetail;
    }
}
