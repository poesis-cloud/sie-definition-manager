package cloud.poesis.sie.defman.controller;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.MediaTypes;
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
import cloud.poesis.sie.defman.service.ArchetypeService;
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
 * @since 0.1.0
 */
@RestController
@RequestMapping(value = "/api/v1/definitions", produces = { MediaTypes.HAL_JSON_VALUE,
        MediaType.APPLICATION_JSON_VALUE })
@Tag(name = "Definitions", description = "Stable identity layer for governed subjects")
public class DefinitionController extends AbstractController {

    private final DefinitionService service;
    private final ArchetypeService archetypeService;

    /**
     * Constructs the definition controller with its required services.
     *
     * @param service               the definition service
     * @param archetypeService      the archetype service for batch fetching
     * @param dataProtectionService the data protection service
     */
    public DefinitionController(
            DefinitionService service,
            ArchetypeService archetypeService,
            DataProtectionService dataProtectionService) {
        super(dataProtectionService);
        this.service = service;
        this.archetypeService = archetypeService;
    }

    /**
     * Explicit-fetch design — see {@code package-info.java} §3 (batch fetch).
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get definition by ID", description = "Returns the definition with its full ascription history.")
    @ApiResponse(responseCode = "200", description = "Definition found")
    @ApiResponse(responseCode = "404", description = "Definition not found")
    public EntityModel<DefinitionDto> getById(
            @Parameter(description = "Definition ID") @PathVariable @NonNull UUID id) {
        DefinitionEntity entity = service.getById(id);

        List<AscriptionEntity> ascriptions = entity.getAscriptions();
        List<AscriptionDto> ascriptionDtos;
        if (ascriptions.isEmpty()) {
            ascriptionDtos = Collections.emptyList();
        } else {
            Set<UUID> archetypeIds = ascriptions.stream()
                    .map(a -> a.getArchetype().getId())
                    .collect(Collectors.toSet());
            Map<UUID, ArchetypeEntity> archetypeMap = archetypeService.getByIds(archetypeIds);
            ascriptionDtos = ascriptions.stream()
                    .map(a -> toAscriptionDto(a, archetypeMap.get(a.getArchetype().getId())))
                    .toList();
        }

        DefinitionDto response = new DefinitionDto(
                entity.getId(), entity.getSubjectType().getValue(), ascriptionDtos);

        return EntityModel.of(
                Objects.requireNonNull(response),
                linkTo(DefinitionController.class).slash(id).withSelfRel());
    }
}
