package io.poesis.sie.defman.controller;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.poesis.sie.defman.dto.AscriptionDto;
import io.poesis.sie.defman.dto.DefinitionDto;
import io.poesis.sie.defman.entity.DefinitionEntity;
import io.poesis.sie.defman.service.DefinitionService;

/**
 * REST controller for GSM Definitions.
 *
 * <p>
 * Exposes the stable identity layer ({@code /api/v1/definitions}) —
 * Definitions are the immutable referents that persist across Ascription
 * versions. Ascription-level operations live in {@link AscriptionController}.
 */
@RestController
@RequestMapping(value = "/api/v1/definitions", produces = { MediaTypes.HAL_JSON_VALUE,
        MediaType.APPLICATION_JSON_VALUE })
public class DefinitionController extends AbstractController {

    private final DefinitionService service;

    public DefinitionController(DefinitionService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public EntityModel<DefinitionDto> getById(@PathVariable @NonNull UUID id) {
        DefinitionEntity entity = service.getById(id);

        List<AscriptionDto> ascriptions = entity.getAscriptions().stream()
                .map(a -> toAscriptionDto(entity.getSubjectType(), a))
                .toList();

        DefinitionDto response = new DefinitionDto(
                entity.getId(), entity.getSubjectType().getValue(), ascriptions);
                
        return EntityModel.of(
                Objects.requireNonNull(response),
                linkTo(DefinitionController.class).slash(id).withSelfRel());
    }
}
