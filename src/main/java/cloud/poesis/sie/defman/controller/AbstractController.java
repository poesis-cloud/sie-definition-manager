package cloud.poesis.sie.defman.controller;

import cloud.poesis.sie.defman.dto.AscriptionDto;
import cloud.poesis.sie.defman.dto.AscriptionStatusTransitionDto;
import cloud.poesis.sie.defman.dto.DefinitionDto;
import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.service.DataProtectionService;
import cloud.poesis.sie.defman.type.AppraisalRuleType;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionRuleType;
import cloud.poesis.sie.defman.type.RuleType;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

/**
 * Base controller providing entity-to-DTO mapping and RFC 9457 Problem Detail exception handling
 * for all GSM REST endpoints.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public abstract class AbstractController {

  private static final Logger log = LoggerFactory.getLogger(AbstractController.class);

  private final DataProtectionService dataProtectionService;

  /**
   * Constructs the abstract controller with shared services.
   *
   * @param dataProtectionService the data protection service for in-transit protection
   */
  protected AbstractController(DataProtectionService dataProtectionService) {
    this.dataProtectionService = dataProtectionService;
  }

  // ========================================================================
  // ENTITY -> DTO MAPPING
  // ========================================================================

  /**
   * Maps an ascription entity to its DTO, applying in-transit data protection.
   *
   * <p>Explicit-fetch design — see README.md § "Explicit-fetch design for lazy associations". The
   * archetype is passed explicitly (already fetched by the caller) rather than navigated via {@code
   * ascription.getArchetype()}, which would trigger a lazy-loading proxy exception.
   *
   * @param ascription the ascription entity
   * @param archetype the resolved archetype entity
   * @return the DTO with in-transit data protection applied
   */
  protected AscriptionDto mapEntityToAscriptionDto(
      AscriptionEntity ascription, ArchetypeEntity archetype) {
    JsonNode statement =
        dataProtectionService.applyInTransitProtection(
            ascription.getStatement(), archetype.getStatement());
    return new AscriptionDto(
        ascription.getId(),
        statement,
        ascription.getTimestamp(),
        ascription.getVersion(),
        ascription.getStatus());
  }

  /**
   * Maps a status transition entity to its DTO.
   *
   * @param ascriptionStatusTransition the transition entity
   * @return the transition DTO
   */
  protected AscriptionStatusTransitionDto mapEntityToAscriptionStatusTransitionDto(
      AscriptionStatusTransitionEntity ascriptionStatusTransition) {
    return new AscriptionStatusTransitionDto(
        ascriptionStatusTransition.getId(),
        ascriptionStatusTransition.getAscription().getId(),
        ascriptionStatusTransition.getPreStatus(),
        ascriptionStatusTransition.getPostStatus(),
        ascriptionStatusTransition.getTimestamp());
  }

  /**
   * Maps a definition entity to a DTO for HAL responses.
   *
   * @param definition the definition entity
   * @return the definition DTO
   */
  protected DefinitionDto mapEntityToDefinitionDto(DefinitionEntity definition) {
    return new DefinitionDto(definition.getId(), definition.getSubjectType());
  }

  // ========================================================================
  // EXCEPTION -> PROBLEM DETAIL MAPPING
  // ========================================================================

  @ExceptionHandler(RuleViolationException.class)
  ProblemDetail mapRuleViolationExceptionToProblemDetail(RuleViolationException exception) {
    HttpStatus status = mapRuleViolationTypeToHttpStatus(exception);
    if (status.is5xxServerError()) {
      return mapExceptionToProblemDetail(exception);
    }
    return constructProblemDetail(
        status,
        exception.getMessage(),
        exception.getTitle(),
        exception.getType(),
        exception.getExtensions());
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  ProblemDetail mapResourceNotFoundExceptionToProblemDetail(ResourceNotFoundException exception) {
    return constructProblemDetail(
        HttpStatus.NOT_FOUND,
        exception.getMessage(),
        exception.getTitle(),
        exception.getType(),
        exception.getExtensions());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail mapIllegalArgumentExceptionToProblemDetail(IllegalArgumentException exception) {
    ProblemDetail problemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    problemDetail.setTitle("Invalid request parameter");
    return problemDetail;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail mapMethodArgumentNotValidExceptionToProblemDetail(
      MethodArgumentNotValidException exception) {
    ProblemDetail problemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    problemDetail.setTitle("Validation failed");
    return problemDetail;
  }

  @ExceptionHandler(ResponseStatusException.class)
  ProblemDetail mapResponseStatusExceptionToProblemDetail(ResponseStatusException exception) {
    ProblemDetail problemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.valueOf(exception.getStatusCode().value()), exception.getReason());
    problemDetail.setTitle(exception.getStatusCode().toString());
    return problemDetail;
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail mapExceptionToProblemDetail(Exception exception) {
    log.error("Unexpected error", exception);
    ProblemDetail problemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error");
    problemDetail.setTitle("Internal server error");
    return problemDetail;
  }

  private static ProblemDetail constructProblemDetail(
      HttpStatus status, String detail, String title, String type, Map<String, Object> extensions) {
    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
    problemDetail.setTitle(title);
    problemDetail.setType(URI.create(type));
    extensions.forEach(problemDetail::setProperty);
    return problemDetail;
  }

  /**
   * Maps a {@link RuleViolationException} to an HTTP status code.
   *
   * @param exception the rule violation exception
   * @return the corresponding HTTP status
   */
  private static HttpStatus mapRuleViolationTypeToHttpStatus(RuleViolationException exception) {
    RuleType rule = exception.getRuleType();
    if (rule instanceof AscriptionConsistencyRuleType rt) {
      return mapRuleTypeToHttpStatus(rt);
    }
    if (rule instanceof AscriptionStatusTransitionRuleType) {
      return HttpStatus.CONFLICT;
    }
    if (rule instanceof AppraisalRuleType) {
      return HttpStatus.CONFLICT;
    }
    throw new IllegalStateException("Unhandled RuleType: " + rule);
  }

  private static HttpStatus mapRuleTypeToHttpStatus(AscriptionConsistencyRuleType rt) {
    return switch (rt) {
      case ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ASCRIPTION_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE,
          MECHANISM_RULE_STARLARK_PARSING,
          MECHANISM_RULE_STARLARK_BUDGET,
          MECHANISM_RULE_STARLARK_CONSTRUCT_BLACKLIST,
          MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST,
          MECHANISM_RULE_TRIGGER_AS_FIRST_STATEMENT,
          MECHANISM_RULE_TRIGGER_AS_UNIQUE_STATEMENT,
          MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE,
          MECHANISM_RULE_SYS_FLUENT_API_ARITY,
          MECHANISM_RULE_SYS_FLUENT_API,
          EFFECTOR_MECHANISM_REFERENCE_INTEGRITY,
          EFFECTOR_ARCHETYPE_REFERENCE_INTEGRITY,
          RECEPTOR_MECHANISM_REFERENCE_INTEGRITY,
          RECEPTOR_ARCHETYPE_REFERENCE_INTEGRITY,
          MECHANISM_STRUCTURE_REFERENCE_INTEGRITY,
          INTERACTION_EFFECTOR_REFERENCE_INTEGRITY,
          INTERACTION_RECEPTOR_REFERENCE_INTEGRITY,
          INTERACTION_EFFECTOR_RECEPTOR_COMPATIBILITY,
          ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          ASCRIPTION_ARCHETYPE_REFERENCE_INTEGRITY,
          DIRECTIVE_STRUCTURE_REFERENCE_INTEGRITY,
          DIRECTIVE_PURPOSE_REFERENCE_INTEGRITY,
          DIRECTIVE_QUALIFIER_REFERENCE_INTEGRITY,
          NORM_STRUCTURE_REFERENCE_INTEGRITY,
          NORM_QUALIFIER_REFERENCE_INTEGRITY,
          NORM_APPLICABILITY_CEL_PARSING,
          NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
          NORM_APPLICABILITY_ARCHETYPE_REFERENCE_RESOLUTION,
          NORM_APPLICABILITY_PROPERTY_PATH_RESOLUTION,
          NORM_ASSERTION_CEL_PARSING,
          NORM_ASSERTION_AS_DETERMINISTIC_EXPRESSION,
          NORM_ASSERTION_AS_ARCHETYPE_BOUND_EXPRESSION,
          NORM_ASSERTION_AS_BOOLEAN_RESULT,
          NORM_ASSERTION_PROPERTY_PATH_RESOLUTION,
          NORM_ASSERTION_TOLERANCE_MODE_CONSISTENCY,
          ARCHETYPE_ALLOF_EXCLUSIVE_BASE_CONVERGENCE,
          ARCHETYPE_ALLOF_ACYCLICITY,
          ARCHETYPE_ALLOF_NON_SEALED,
          ARCHETYPE_REF_NORM,
          ARCHETYPE_IDENTITY_BOUND_PROPERTY_IMMUTABILITY ->
          HttpStatus.BAD_REQUEST;
      case ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
          ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION ->
          HttpStatus.CONFLICT;
    };
  }
}
