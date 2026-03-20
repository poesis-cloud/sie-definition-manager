package cloud.poesis.sie.defman.controller;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.hateoas.LinkRelation;
import org.springframework.hateoas.MediaTypes;
import org.springframework.hateoas.RepresentationModel;
import org.springframework.hateoas.mediatype.hal.HalModelBuilder;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.poesis.sie.defman.dto.AscriptionDto;
import cloud.poesis.sie.defman.dto.DefinitionDto;
import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.service.DataProtectionService;
import cloud.poesis.sie.defman.service.DefinitionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
        MediaType.APPLICATION_JSON_VALUE })
@Tag(name = "Definitions", description = "Stable identity layer for governed subjects")
public class DefinitionController extends AbstractController {

    private final DefinitionService definitionService;

    /**
     * Constructs the definition controller with its required services.
     *
     * @param definitionService     the definition definitionService
     * @param dataProtectionService the data protection definitionService
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
    @Operation(summary = "Get definition by ID", description = "Returns the definition with its full ascription history.")
    @ApiResponse(responseCode = "200", description = "Definition found")
    @ApiResponse(responseCode = "404", description = "Definition not found")
    public RepresentationModel<?> getById(
            @Parameter(description = "Definition ID") @PathVariable @NonNull UUID id) {
        DefinitionEntity entity = definitionService.getByIdWithArchetypes(id);
        List<AscriptionEntity> ascriptions = entity.getAscriptions();

        @SuppressWarnings("unchecked")
        List<RepresentationModel<?>> ascriptionModels = (List<RepresentationModel<?>>) (List<?>) ascriptions.stream()
                .map(ascription -> {
                    ArchetypeEntity archetype = ascription.getArchetype();
                    AscriptionDto dto = mapEntityToAscriptionDto(ascription, archetype);
                    AscriptionDto archetypeDto = mapEntityToAscriptionDto(archetype, archetype);
                    return (RepresentationModel<?>) HalModelBuilder.halModelOf(dto)
                            .embed(archetypeDto, LinkRelation.of("archetype"))
                            .link(linkTo(AscriptionController.class).slash(dto.id()).withSelfRel())
                            .link(linkTo(AscriptionController.class).slash(dto.id()).slash("transitions")
                                    .withRel("transitions"))
                            .build();
                })
                .toList();

        DefinitionDto response = new DefinitionDto(entity.getId(), entity.getSubjectType().getValue());

        HalModelBuilder builder = HalModelBuilder.halModelOf(Objects.requireNonNull(response));
        ascriptionModels.forEach(builder::embed);
        builder.link(linkTo(DefinitionController.class).slash(id).withSelfRel());
        return builder.build();
    }
}
