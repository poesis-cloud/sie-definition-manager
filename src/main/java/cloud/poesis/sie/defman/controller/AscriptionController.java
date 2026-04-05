package cloud.poesis.sie.defman.controller;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.afford;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

import cloud.poesis.sie.defman.dto.AscriptionCreationDto;
import cloud.poesis.sie.defman.dto.AscriptionDto;
import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.service.AscriptionService;
import cloud.poesis.sie.defman.service.AscriptionStatementProtectionService;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified REST controller for all GSM ascription types.
 *
 * <p>Single resource endpoint {@code /api/v1/ascriptions} — the archetype (via archetypeId)
 * discriminates the entity type, not the URL path.
 *
 * <p>HAL link relations on every ascription:
 *
 * <ul>
 *   <li>{@code self} — this ascription resource (RFC 4287)
 *   <li>{@code describedby} (RFC 6892) — composed JSON Schema: static Ascription envelope with the
 *       per-instance Archetype schema inlined as the {@code statement} property ({@code
 *       application/schema+json})
 *   <li>{@code type} (RFC 6903 §6) — the typing Archetype Definition
 *   <li>{@code collection} (RFC 6573) — the ascription collection for this ascription's parent
 *       Definition
 *   <li>{@code create-form} (RFC 6861) — the endpoint that accepts new ascriptions
 * </ul>
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@RestController
@RequestMapping(
    value = "/api/v1/ascriptions",
    produces = {
      MediaTypes.HAL_JSON_VALUE,
      MediaTypes.HAL_FORMS_JSON_VALUE,
      MediaType.APPLICATION_JSON_VALUE
    })
@Tag(name = "Ascriptions", description = "CRUD for GSM ascriptions")
public class AscriptionController extends AbstractController {

  private final AscriptionService ascriptionService;
  private final ObjectMapper objectMapper;

  public AscriptionController(
      AscriptionService ascriptionService,
      AscriptionStatementProtectionService statementProtection,
      ObjectMapper objectMapper) {
    super(statementProtection);
    this.ascriptionService = ascriptionService;
    this.objectMapper = objectMapper;
  }

  // ========================================================================
  // CREATE
  // ========================================================================

  /** Explicit-fetch design — see README.md § "Single-item fetch pattern". */
  @PostMapping
  @Operation(
      summary = "Create an ascription",
      description =
          "Creates a new ascription. The archetypeId determines the GSM type; statement must conform to the archetype's JSON Schema.")
  @ApiResponse(responseCode = "201", description = "Ascription created")
  @ApiResponse(
      responseCode = "400",
      description = "Validation error (schema, identity-bound, referee precondition)",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Conflict (duplicate identity)",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public ResponseEntity<EntityModel<AscriptionDto>> create(
      @Valid @RequestBody AscriptionCreationDto request) {
    AscriptionEntity entity =
        ascriptionService.create(
            request.getArchetypeId(), request.getStatement(), request.getDefinitionId());
    EntityModel<AscriptionDto> model = wrapWithLinks(entity);
    URI location = linkTo(AscriptionController.class).slash(entity.getId()).toUri();
    return ResponseEntity.created(location).body(model);
  }

  // ========================================================================
  // READ
  // ========================================================================

  /** Explicit-fetch design — see README.md § "Single-item fetch pattern". */
  @GetMapping("/{id}")
  @Operation(summary = "Get ascription by ID")
  @ApiResponse(responseCode = "200", description = "Ascription found")
  @ApiResponse(
      responseCode = "404",
      description = "Ascription not found",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public EntityModel<AscriptionDto> getById(
      @Parameter(description = "Ascription ID") @PathVariable UUID id) {
    AscriptionEntity entity = ascriptionService.getById(id);
    return wrapWithLinks(entity);
  }

  /** Explicit-fetch design — see README.md § "Batch fetch pattern". */
  @GetMapping
  @Operation(
      summary = "List ascriptions (paginated)",
      description =
          "Filter by subject type and optionally by status, archetype, "
              + "and queryable statement properties (statement.{prop}=value).")
  @ApiResponse(responseCode = "200", description = "Paged list of ascriptions")
  @ApiResponse(
      responseCode = "400",
      description = "Invalid type, status, or query filter",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public PagedModel<EntityModel<AscriptionDto>> list(
      @Parameter(
              description =
                  "GSM subject type (STRUCTURE, MECHANISM, EFFECTOR, RECEPTOR, INTERACTION, ARCHETYPE, NORM, DIRECTIVE)")
          @RequestParam
          String type,
      @Parameter(description = "Optional lifecycle status filter") @RequestParam(required = false)
          AscriptionStatusType status,
      @Parameter(description = "Optional archetype filter (UUID or title)")
          @RequestParam(required = false)
          String archetype,
      @ParameterObject @PageableDefault(size = 20) Pageable pageable,
      HttpServletRequest request) {
    DefinitionSubjectType subjectType = DefinitionSubjectType.fromValue(type);

    // Extract statement.* filter params
    Map<String, String> statementFilters = extractStatementFilters(request);

    Page<? extends AscriptionEntity> page =
        ascriptionService.findAllFiltered(
            subjectType, archetype, statementFilters, status, pageable);

    String typeName = subjectType.name();
    String statusName = (status != null) ? status.name() : null;
    return toPagedModel(page, typeName, statusName, archetype, statementFilters);
  }

  /**
   * Composed JSON Schema for this ascription: Ascription envelope with the per-instance Archetype
   * schema inlined as the {@code statement} property. IANA {@code describedby} (RFC 6892) link
   * target.
   */
  @GetMapping(value = "/{id}/schema", produces = "application/schema+json")
  @Operation(
      summary = "Get the composed schema for this ascription",
      description =
          "Returns the Ascription envelope schema with the typing Archetype's JSON Schema "
              + "inlined as the statement property.")
  @ApiResponse(
      responseCode = "200",
      description = "JSON Schema document",
      content =
          @Content(
              mediaType = "application/schema+json",
              schema =
                  @Schema(type = "object", description = "JSON Schema document (draft 2020-12)")))
  @ApiResponse(
      responseCode = "404",
      description = "Ascription not found",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public JsonNode getSchema(@Parameter(description = "Ascription ID") @PathVariable UUID id) {
    AscriptionEntity entity = ascriptionService.getById(id);
    ObjectNode envelope = buildAscriptionEnvelope();
    ((ObjectNode) envelope.get("properties"))
        .set("statement", entity.getArchetype().getStatement());
    return envelope;
  }

  // ========================================================================
  // QUERY FILTER HELPERS
  // ========================================================================

  private static final String STATEMENT_PARAM_PREFIX = "statement.";

  private static Map<String, String> extractStatementFilters(HttpServletRequest request) {
    Map<String, String> filters = new LinkedHashMap<>();
    request
        .getParameterMap()
        .forEach(
            (key, values) -> {
              if (key.startsWith(STATEMENT_PARAM_PREFIX)
                  && key.length() > STATEMENT_PARAM_PREFIX.length()) {
                filters.put(key.substring(STATEMENT_PARAM_PREFIX.length()), values[0]);
              }
            });
    return filters;
  }

  // ========================================================================
  // HATEOAS — single-item and list-item link wrapping
  // ========================================================================

  private EntityModel<AscriptionDto> wrapWithLinks(AscriptionEntity entity) {
    ArchetypeEntity archetype = entity.getArchetype();
    AscriptionDto dto = mapEntityToAscriptionDto(entity, archetype);
    UUID ascriptionId = entity.getId();
    UUID definitionId = entity.getDefinition().getId();
    UUID archetypeDefinitionId = archetype.getDefinition().getId();
    EntityModel<AscriptionDto> model = EntityModel.of(dto);
    model.add(selfLinkFor(ascriptionId));
    model.add(describedbyLinkFor(ascriptionId));
    model.add(typeLinkFor(archetypeDefinitionId));
    model.add(collectionLinkFor(definitionId));
    model.add(createFormLinkFor());
    return model;
  }

  private PagedModel<EntityModel<AscriptionDto>> toPagedModel(
      Page<? extends AscriptionEntity> page,
      String type,
      String status,
      String archetype,
      Map<String, String> statementFilters) {
    List<EntityModel<AscriptionDto>> content =
        page.getContent().stream().map(this::wrapWithLinks).toList();
    PagedModel.PageMetadata metadata =
        new PagedModel.PageMetadata(
            page.getSize(), page.getNumber(),
            page.getTotalElements(), page.getTotalPages());
    PagedModel<EntityModel<AscriptionDto>> model = PagedModel.of(content, metadata);
    model.add(listSelfLink(type, status, archetype, statementFilters, page.getPageable()));
    return model;
  }

  // ========================================================================
  // LINK BUILDERS
  // ========================================================================

  private Link selfLinkFor(UUID ascriptionId) {
    return linkTo(methodOn(AscriptionController.class).getById(ascriptionId)).withSelfRel();
  }

  /**
   * IANA RFC 6892 {@code describedby} — composed JSON Schema: Ascription envelope with the
   * per-instance Archetype schema inlined as the {@code statement} property ({@code
   * application/schema+json}).
   */
  private Link describedbyLinkFor(UUID ascriptionId) {
    return linkTo(AscriptionController.class)
        .slash(ascriptionId)
        .slash("schema")
        .withRel("describedby");
  }

  /** IANA RFC 6903 §6 {@code type} — the typing Archetype Definition. */
  private Link typeLinkFor(UUID archetypeDefinitionId) {
    return linkTo(DefinitionController.class).slash(archetypeDefinitionId).withRel("type");
  }

  /**
   * IANA RFC 6573 {@code collection} — the ascription collection for this ascription's parent
   * Definition.
   */
  private Link collectionLinkFor(UUID definitionId) {
    return linkTo(DefinitionController.class)
        .slash(definitionId)
        .slash("ascriptions")
        .withRel("collection");
  }

  /** RFC 6861 {@code create-form} — the endpoint that accepts new ascriptions. */
  private Link createFormLinkFor() {
    return linkTo(AscriptionController.class).withRel("create-form");
  }

  private Link listSelfLink(
      String type,
      String status,
      String archetype,
      Map<String, String> statementFilters,
      Pageable pageable) {
    var builder =
        linkTo(AscriptionController.class)
            .toUriComponentsBuilder()
            .queryParam("type", type)
            .queryParam("page", pageable.getPageNumber())
            .queryParam("size", pageable.getPageSize());
    if (status != null) {
      builder.queryParam("status", status);
    }
    if (archetype != null) {
      builder.queryParam("archetype", archetype);
    }
    for (var entry : statementFilters.entrySet()) {
      builder.queryParam("statement." + entry.getKey(), entry.getValue());
    }
    pageable
        .getSort()
        .forEach(
            order -> builder.queryParam("sort", order.getProperty() + "," + order.getDirection()));
    return Link.of(builder.toUriString())
        .withSelfRel()
        .andAffordance(afford(methodOn(AscriptionController.class).create(null)));
  }

  // ========================================================================
  // INLINE SCHEMA BUILDERS
  // ========================================================================

  private ObjectNode buildAscriptionEnvelope() {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    schema.put("title", "Ascription");
    schema.put("description", "Governed normative snapshot of a Definition.");
    schema.put("type", "object");
    ArrayNode required = schema.putArray("required");
    required.add("id").add("statement").add("timestamp").add("status");
    ObjectNode props = schema.putObject("properties");
    props.putObject("id").put("type", "string").put("format", "uuid");
    props.putObject("statement").put("type", "object");
    props.putObject("timestamp").put("type", "string").put("format", "date-time");
    ObjectNode statusProp = props.putObject("status").put("type", "string");
    ArrayNode statusEnum = statusProp.putArray("enum");
    for (AscriptionStatusType s : AscriptionStatusType.values()) {
      statusEnum.add(s.name());
    }
    schema.put("additionalProperties", false);
    return schema;
  }
}
