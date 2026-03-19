package cloud.poesis.sie.defman.controller;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.hateoas.PagedModel;
import org.springframework.hateoas.RepresentationModel;
import org.springframework.hateoas.mediatype.hal.HalModelBuilder;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cloud.poesis.sie.defman.dto.AscriptionCreationDto;
import cloud.poesis.sie.defman.dto.AscriptionDto;
import cloud.poesis.sie.defman.dto.AscriptionStatusTransitionCreationDto;
import cloud.poesis.sie.defman.dto.AscriptionStatusTransitionDto;
import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.service.AbstractAscriptionService;
import cloud.poesis.sie.defman.service.ArchetypeService;
import cloud.poesis.sie.defman.service.AscriptionLifecycleService;
import cloud.poesis.sie.defman.service.AscriptionService;
import cloud.poesis.sie.defman.service.DataProtectionService;
import cloud.poesis.sie.defman.service.DefinitionService;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Unified REST controller for all GSM ascription types.
 *
 * <p>
 * Single resource endpoint {@code /api/v1/ascriptions} — the archetype (via
 * archetypeId) discriminates the entity type, not the URL path.
 *
 * <p>
 * HAL link relations on every ascription:
 * <ul>
 * <li>{@code self} — this ascription resource
 * <li>{@code definition} — the stable Definition identity this ascription
 * belongs to
 * <li>{@code describedby} (IANA RFC 6892) — the Archetype ascription whose
 * {@code statement} IS the JSON Schema governing this ascription's
 * statement payload
 * <li>{@code history} — version history for this Definition
 * <li>{@code transitions} — lifecycle audit trail for this ascription
 * </ul>
 *
 * <p>
 * Single-item responses ({@link #getById}, {@link #create}) include
 * {@code _embedded} projections of the Definition (with
 * {@code subjectType}) and Archetype (with {@code title}), each carrying
 * their own {@code self} link for inline type discrimination without extra
 * round-trips.
 */
@RestController
@RequestMapping(value = "/api/v1/ascriptions", produces = { MediaTypes.HAL_JSON_VALUE,
        MediaType.APPLICATION_JSON_VALUE })
@Tag(name = "Ascriptions", description = "CRUD and lifecycle transitions for GSM ascriptions")
public class AscriptionController extends AbstractController {

    private final Map<DefinitionSubjectType, AbstractAscriptionService> serviceRegistry;
    private final ArchetypeService archetypeService;
    private final AscriptionService ascriptionService;
    private final AscriptionLifecycleService lifecycleService;
    private final DefinitionService definitionService;

    public AscriptionController(
            Map<DefinitionSubjectType, AbstractAscriptionService> serviceRegistry,
            ArchetypeService archetypeService,
            AscriptionService ascriptionService,
            AscriptionLifecycleService lifecycleService,
            DefinitionService definitionService,
            DataProtectionService dataProtectionService) {
        super(dataProtectionService);
        this.serviceRegistry = serviceRegistry;
        this.archetypeService = archetypeService;
        this.ascriptionService = ascriptionService;
        this.lifecycleService = lifecycleService;
        this.definitionService = definitionService;
    }

    // ========================================================================
    // CREATE
    // ========================================================================

    /**
     * Explicit-fetch design — see README.md § "Single-item fetch pattern".
     */
    @PostMapping
    @Operation(summary = "Create an ascription", description = "Creates a new ascription. The archetypeId determines the GSM type; statement must conform to the archetype's JSON Schema.")
    @ApiResponse(responseCode = "201", description = "Ascription created")
    @ApiResponse(responseCode = "400", description = "Validation error (schema, identity-bound, referee precondition)")
    @ApiResponse(responseCode = "409", description = "Conflict (duplicate identity)")
    public ResponseEntity<RepresentationModel<?>> create(
            @Valid @RequestBody AscriptionCreationDto request) {
        var resolution = archetypeService.resolveForCreation(request.archetypeId());
        AbstractAscriptionService subtypeService = requireService(resolution.subjectType());
        AscriptionEntity entity = subtypeService.create(
                resolution.archetype(), request.statement(), request.definitionId());
        DefinitionEntity definition = definitionService.getById(entity.getDefinition().getId());
        ArchetypeEntity archetype = archetypeService.findEntityById(entity.getArchetype().getId());
        RepresentationModel<?> model = wrapWithLinksAndEmbeds(entity, definition, archetype);
        URI location = linkTo(AscriptionController.class).slash(entity.getId()).toUri();
        return ResponseEntity.created(location).body(model);
    }

    // ========================================================================
    // READ
    // ========================================================================

    /**
     * Explicit-fetch design — see README.md § "Single-item fetch pattern".
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get ascription by ID")
    @ApiResponse(responseCode = "200", description = "Ascription found")
    @ApiResponse(responseCode = "404", description = "Ascription not found")
    public RepresentationModel<?> getById(
            @Parameter(description = "Ascription ID") @PathVariable UUID id) {
        AscriptionEntity entity = ascriptionService.getById(id);
        DefinitionEntity definition = definitionService.getById(entity.getDefinition().getId());
        ArchetypeEntity archetype = archetypeService.findEntityById(entity.getArchetype().getId());
        return wrapWithLinksAndEmbeds(entity, definition, archetype);
    }

    /**
     * Explicit-fetch design — see README.md § "Batch fetch pattern".
     */
    @GetMapping
    @Operation(summary = "List ascriptions (paginated)", description = "Filter by subject type and optionally by status.")
    @ApiResponse(responseCode = "200", description = "Paged list of ascriptions")
    @ApiResponse(responseCode = "400", description = "Invalid type or status value")
    public PagedModel<EntityModel<AscriptionDto>> list(
            @Parameter(description = "GSM subject type (STRUCTURE, MECHANISM, EFFECTOR, RECEPTOR, INTERACTION, ARCHETYPE, NORM, DIRECTIVE)") @RequestParam String type,
            @Parameter(description = "Optional lifecycle status filter") @RequestParam(required = false) String status,
            @PageableDefault(size = 20) Pageable pageable) {
        AscriptionStatusType statusEnum = (status != null) ? AscriptionStatusType.valueOf(status) : null;
        DefinitionSubjectType subjectType = DefinitionSubjectType.fromValue(type);
        AbstractAscriptionService subtypeService = requireService(subjectType);
        Page<? extends AscriptionEntity> page = (statusEnum != null)
                ? subtypeService.findAllByStatus(statusEnum, pageable)
                : subtypeService.findAll(pageable);
        // Explicit-fetch design — see README.md § "Batch fetch pattern"
        if (page.isEmpty()) {
            return toPagedModel(page, type, status, Collections.emptyMap());
        }
        Set<UUID> archetypeIds = page.getContent().stream()
                .map(e -> e.getArchetype().getId())
                .collect(Collectors.toSet());
        Map<UUID, ArchetypeEntity> archetypeMap = archetypeService.getByIds(archetypeIds);
        return toPagedModel(page, type, status, archetypeMap);
    }

    /**
     * Explicit-fetch design — see README.md § "Batch fetch pattern".
     */
    @GetMapping("/history")
    @Operation(summary = "Get ascription history for a definition")
    @ApiResponse(responseCode = "200", description = "Ordered list of ascription versions")
    public List<EntityModel<AscriptionDto>> getAscriptionHistory(
            @Parameter(description = "Definition ID") @RequestParam UUID definitionId,
            @Parameter(description = "GSM subject type") @RequestParam String type) {
        DefinitionSubjectType subjectType = DefinitionSubjectType.fromValue(type);
        AbstractAscriptionService subtypeService = requireService(subjectType);
        List<? extends AscriptionEntity> history = subtypeService.getHistory(definitionId);
        // Explicit-fetch design — see README.md § "Batch fetch pattern"
        if (history.isEmpty()) {
            return List.of();
        }
        Set<UUID> archetypeIds = history.stream()
                .map(e -> e.getArchetype().getId())
                .collect(Collectors.toSet());
        Map<UUID, ArchetypeEntity> archetypeMap = archetypeService.getByIds(archetypeIds);
        return history.stream()
                .map(e -> wrapWithLinks(e, type, archetypeMap.get(e.getArchetype().getId())))
                .toList();
    }

    @GetMapping("/{id}/transitions")
    @Operation(summary = "Get transition audit trail")
    @ApiResponse(responseCode = "200", description = "Ordered transition records")
    @ApiResponse(responseCode = "404", description = "Ascription not found")
    public List<AscriptionStatusTransitionDto> getTransitions(
            @Parameter(description = "Ascription ID") @PathVariable UUID id) {
        return lifecycleService.getTransitions(id).stream()
                .map(this::toTransitionDto)
                .toList();
    }

    // ========================================================================
    // LIFECYCLE TRANSITIONS
    // ========================================================================

    @PostMapping("/{id}/transitions")
    @Operation(summary = "Transition ascription to a new status")
    @ApiResponse(responseCode = "200", description = "Transition recorded")
    @ApiResponse(responseCode = "400", description = "Invalid transition")
    @ApiResponse(responseCode = "404", description = "Ascription not found")
    public ResponseEntity<AscriptionStatusTransitionDto> transition(
            @Parameter(description = "Ascription ID") @PathVariable UUID id,
            @Valid @RequestBody AscriptionStatusTransitionCreationDto request) {
        AscriptionStatusTransitionEntity saved = lifecycleService.transition(id, request.targetStatus());
        return ResponseEntity.ok(toTransitionDto(saved));
    }

    // ========================================================================
    // DISPATCH HELPER
    // ========================================================================

    private AbstractAscriptionService requireService(DefinitionSubjectType type) {
        AbstractAscriptionService svc = serviceRegistry.get(type);
        if (svc == null) {
            throw new IllegalStateException("No service registered for type: " + type);
        }
        return svc;
    }

    // ========================================================================
    // HATEOAS — single-item (rich: _embedded + _links)
    // ========================================================================

    /**
     * Full HAL representation for single-item responses: embedded Definition
     * and Archetype projections (each with own {@code self} link), plus all
     * navigational links on the ascription itself.
     */
    private RepresentationModel<?> wrapWithLinksAndEmbeds(
            AscriptionEntity entity, DefinitionEntity definition, ArchetypeEntity archetype) {
        AscriptionDto dto = toAscriptionDto(entity, archetype);
        String subjectType = definition.getSubjectType().getValue();

        return HalModelBuilder.halModelOf(dto)
                .embed(EntityModel.of(toEmbeddedDefinitionDto(definition),
                        linkTo(DefinitionController.class).slash(dto.definitionId()).withSelfRel()))
                .embed(EntityModel.of(toEmbeddedArchetypeDto(archetype),
                        linkTo(AscriptionController.class).slash(dto.archetypeId()).withSelfRel()))
                .link(selfLinkFor(dto))
                .link(definitionLinkFor(dto))
                .link(describedbyLinkFor(dto))
                .link(historyLinkFor(dto, subjectType))
                .link(transitionsLinkFor(dto))
                .build();
    }

    // ========================================================================
    // HATEOAS — list items (lightweight: _links only)
    // ========================================================================

    private EntityModel<AscriptionDto> wrapWithLinks(
            AscriptionEntity entity, String subjectType, ArchetypeEntity archetype) {
        AscriptionDto dto = toAscriptionDto(entity, archetype);
        EntityModel<AscriptionDto> model = EntityModel.of(Objects.requireNonNull(dto));
        model.add(selfLinkFor(dto));
        model.add(definitionLinkFor(dto));
        model.add(describedbyLinkFor(dto));
        model.add(historyLinkFor(dto, subjectType));
        model.add(transitionsLinkFor(dto));
        return model;
    }

    private PagedModel<EntityModel<AscriptionDto>> toPagedModel(
            Page<? extends AscriptionEntity> page, String type, String status,
            Map<UUID, ArchetypeEntity> archetypeMap) {
        List<EntityModel<AscriptionDto>> content = page.getContent().stream()
                .map(e -> wrapWithLinks(e, type, archetypeMap.get(e.getArchetype().getId())))
                .toList();
        PagedModel.PageMetadata metadata = new PagedModel.PageMetadata(
                page.getSize(), page.getNumber(),
                page.getTotalElements(), page.getTotalPages());
        PagedModel<EntityModel<AscriptionDto>> model = PagedModel.of(
                Objects.requireNonNull(content), metadata);
        model.add(listSelfLink(type, status, page.getPageable()));
        return model;
    }

    // ========================================================================
    // LINK BUILDERS
    // ========================================================================

    private @NonNull Link selfLinkFor(AscriptionDto dto) {
        return linkTo(AscriptionController.class)
                .slash(Objects.requireNonNull(dto.id()))
                .withSelfRel();
    }

    /** Stable Definition identity this ascription belongs to. */
    private @NonNull Link definitionLinkFor(AscriptionDto dto) {
        return linkTo(DefinitionController.class)
                .slash(Objects.requireNonNull(dto.definitionId()))
                .withRel("definition");
    }

    /**
     * IANA RFC 6892 {@code describedby} — the Archetype ascription whose
     * {@code statement} IS the JSON Schema that governs this ascription's
     * statement payload. Follow this link to retrieve the schema.
     */
    private @NonNull Link describedbyLinkFor(AscriptionDto dto) {
        return linkTo(AscriptionController.class)
                .slash(Objects.requireNonNull(dto.archetypeId()))
                .withRel("describedby");
    }

    private @NonNull Link historyLinkFor(AscriptionDto dto, String subjectType) {
        String href = linkTo(AscriptionController.class)
                .slash("history")
                .toUriComponentsBuilder()
                .queryParam("definitionId", Objects.requireNonNull(dto.definitionId()))
                .queryParam("type", Objects.requireNonNull(subjectType))
                .toUriString();
        return Link.of(href, "history");
    }

    private @NonNull Link transitionsLinkFor(AscriptionDto dto) {
        return linkTo(AscriptionController.class)
                .slash(Objects.requireNonNull(dto.id()))
                .slash("transitions")
                .withRel("transitions");
    }

    private @NonNull Link listSelfLink(String type, String status, Pageable pageable) {
        var builder = linkTo(AscriptionController.class)
                .toUriComponentsBuilder()
                .queryParam("type", Objects.requireNonNull(type))
                .queryParam("page", pageable.getPageNumber())
                .queryParam("size", pageable.getPageSize());
        if (status != null) {
            builder.queryParam("status", status);
        }
        pageable
                .getSort()
                .forEach(
                        order -> builder.queryParam("sort", order.getProperty() + "," + order.getDirection()));
        return Link.of(builder.toUriString()).withSelfRel();
    }
}
