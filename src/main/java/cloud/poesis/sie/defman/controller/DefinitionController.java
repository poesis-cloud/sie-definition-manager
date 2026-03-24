package cloud.poesis.sie.defman.controller;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cloud.poesis.sie.defman.dto.AscriptionDto;
import cloud.poesis.sie.defman.dto.DefinitionDto;
import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.service.DataProtectionService;
import cloud.poesis.sie.defman.service.DefinitionService;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST controller for GSM Definitions.
 *
 * <p>
 * Exposes the stable identity layer ({@code /api/v1/definitions}) —
 * Definitions are the immutable referents that persist across Ascription
 * versions. Ascription-level operations live in {@link AscriptionController}.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@RestController
@RequestMapping(value = "/api/v1/definitions", produces = { MediaTypes.HAL_JSON_VALUE,
        MediaTypes.HAL_FORMS_JSON_VALUE, MediaType.APPLICATION_JSON_VALUE })
@Tag(name = "Definitions", description = "Stable identity layer for governed subjects")
public class DefinitionController extends AbstractController {

    private final DefinitionService definitionService;

    /**
     * Constructs the definition controller with its required services.
     *
     * @param definitionService     the definition service
     * @param dataProtectionService the data protection service
     */
    public DefinitionController(
            DefinitionService definitionService,
            DataProtectionService dataProtectionService) {
        super(dataProtectionService);
        this.definitionService = definitionService;
    }

    /**
     * Retrieves a definition by its unique identifier, including its full
     * ascription history.
     *
     * @param id the unique identifier of the definition
     * @return the definition with its full ascription history
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get definition by ID", description = "Returns the definition with links to its ascriptions.")
    @ApiResponse(responseCode = "200", description = "Definition found")
    @ApiResponse(responseCode = "404", description = "Definition not found",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public EntityModel<DefinitionDto> getById(
            @Parameter(description = "Definition ID") @PathVariable @NonNull UUID id) {
        DefinitionEntity entity = definitionService.getByIdWithArchetypes(id);
        DefinitionDto response = new DefinitionDto(entity.getId(), entity.getSubjectType());
        List<AscriptionEntity> ascriptions = entity.getAscriptions();

        EntityModel<DefinitionDto> model = EntityModel.of(Objects.requireNonNull(response),
                linkTo(DefinitionController.class).slash(id).withSelfRel());
        if (ascriptions != null && !ascriptions.isEmpty()) {
            // Ascriptions ordered desc by timestamp — first=oldest (last elem), last=newest (first elem)
            model.add(linkTo(AscriptionController.class)
                    .slash(ascriptions.get(ascriptions.size() - 1).getId()).withRel("first"));
            model.add(linkTo(AscriptionController.class)
                    .slash(ascriptions.get(0).getId()).withRel("last"));
            // latest-version: first ascription with version >= 1 (omit if all v0)
            for (AscriptionEntity a : ascriptions) {
                if (a.getVersion() >= 1) {
                    model.add(linkTo(AscriptionController.class)
                            .slash(a.getId()).withRel("latest-version"));
                    break;
                }
            }
        }
        model.add(linkTo(methodOn(DefinitionController.class).listAscriptions(id, 1))
                .withRel("version-history"));
        return model;
    }

    // ========================================================================
    // ASCRIPTION SUB-RESOURCES
    // ========================================================================

    /**
     * Lists all ascription versions for a definition, ordered by timestamp
     * descending (newest first).
     *
     * @param id the definition identifier
     * @return ascription list with HAL links
     */
    @GetMapping("/{id}/ascriptions")
    @Operation(summary = "List ascriptions for a definition",
            description = "Returns ascription versions for this definition. "
                    + "Use minVersion=1 to retrieve only governance-approved versions (version-history).")
    @ApiResponse(responseCode = "200", description = "Ascription list")
    @ApiResponse(responseCode = "404", description = "Definition not found",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public CollectionModel<EntityModel<AscriptionDto>> listAscriptions(
            @Parameter(description = "Definition ID") @PathVariable @NonNull UUID id,
            @Parameter(description = "Minimum version filter (inclusive). "
                    + "Use 1 to exclude unapproved drafts.")
            @RequestParam(required = false) Integer minVersion) {
        DefinitionEntity entity = definitionService.getByIdWithArchetypes(id);
        List<AscriptionEntity> ascriptions = entity.getAscriptions();
        if (minVersion != null) {
            ascriptions = ascriptions.stream()
                    .filter(a -> a.getVersion() >= minVersion)
                    .sorted(Comparator.comparingInt(AscriptionEntity::getVersion))
                    .toList();
        }
        List<EntityModel<AscriptionDto>> items = new ArrayList<>();
        for (AscriptionEntity ascription : ascriptions) {
            ArchetypeEntity archetype = ascription.getArchetype();
            AscriptionDto dto = mapEntityToAscriptionDto(ascription, archetype);
            EntityModel<AscriptionDto> model = EntityModel.of(dto);
            ascriptionLinks(ascription.getId(), id, archetype.getDefinition().getId())
                    .forEach(model::add);
            items.add(model);
        }
        return CollectionModel.of(items,
                linkTo(DefinitionController.class).slash(id).slash("ascriptions").withSelfRel());
    }

    /**
     * Returns the most recent in-effect ascription (ACTIVE or DEPRECATED)
     * for a definition, as a full HAL resource with embedded Definition and
     * Archetype.
     *
     * @param id the definition identifier
     * @return the latest in-effect ascription
     */
    @GetMapping("/{id}/ascriptions/latest")
    @Operation(summary = "Get the latest in-effect ascription",
            description = "Returns the most recent ascription with ACTIVE or DEPRECATED status for this definition.")
    @ApiResponse(responseCode = "200", description = "Latest in-effect ascription")
    @ApiResponse(responseCode = "404", description = "Definition or in-effect ascription not found",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public EntityModel<AscriptionDto> getLatestAscription(
            @Parameter(description = "Definition ID") @PathVariable @NonNull UUID id) {
        DefinitionEntity entity = definitionService.getByIdWithArchetypes(id);
        List<AscriptionEntity> ascriptions = entity.getAscriptions();
        int latestIndex = -1;
        for (int i = 0; i < ascriptions.size(); i++) {
            AscriptionStatusType st = ascriptions.get(i).getStatus();
            if (st == AscriptionStatusType.ACTIVE || st == AscriptionStatusType.DEPRECATED) {
                latestIndex = i;
                break;
            }
        }
        if (latestIndex < 0) {
            throw new ResourceNotFoundException(PrimitiveType.ASCRIPTION, id);
        }
        AscriptionEntity latest = ascriptions.get(latestIndex);
        ArchetypeEntity archetype = latest.getArchetype();
        AscriptionDto dto = mapEntityToAscriptionDto(latest, archetype);

        EntityModel<AscriptionDto> model = EntityModel.of(dto);
        ascriptionLinks(latest.getId(), id, archetype.getDefinition().getId())
                .forEach(model::add);
        return model;
    }

    // ========================================================================
    // LINK BUILDERS
    // ========================================================================

    private List<Link> ascriptionLinks(UUID ascriptionId,
            UUID definitionId, UUID archetypeDefinitionId) {
        List<Link> links = new ArrayList<>();
        links.add(linkTo(methodOn(AscriptionController.class).getById(ascriptionId))
                .withSelfRel());
        links.add(linkTo(AscriptionController.class).slash(ascriptionId)
                .slash("schema").withRel("describedby"));
        links.add(linkTo(DefinitionController.class).slash(archetypeDefinitionId)
                .withRel("type"));
        links.add(linkTo(DefinitionController.class).slash(definitionId)
                .slash("ascriptions").withRel("collection"));
        links.add(linkTo(AscriptionController.class).withRel("create-form"));
        return links;
    }

}
