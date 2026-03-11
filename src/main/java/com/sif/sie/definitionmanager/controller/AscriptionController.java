package com.sif.sie.definitionmanager.controller;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.hateoas.PagedModel;
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

import com.sif.sie.definitionmanager.dto.AscriptionDto;
import com.sif.sie.definitionmanager.dto.AscriptionRequestDto;
import com.sif.sie.definitionmanager.dto.AscriptionStatusTransitionDto;
import com.sif.sie.definitionmanager.dto.AscriptionStatusTransitionRequestDto;
import com.sif.sie.definitionmanager.entity.AscriptionEntity;
import com.sif.sie.definitionmanager.entity.AscriptionStatusTransitionEntity;
import com.sif.sie.definitionmanager.service.AscriptionService;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;

import jakarta.validation.Valid;

/**
 * Unified REST controller for all GSM ascription types.
 *
 * <p>
 * Single resource endpoint {@code /api/v1/ascriptions} — the archetype (via
 * archetypeId) discriminates the entity type, not the URL path.
 */
@RestController
@RequestMapping(value = "/api/v1/ascriptions", produces = { MediaTypes.HAL_JSON_VALUE,
        MediaType.APPLICATION_JSON_VALUE })
public class AscriptionController extends Controller {

    private final AscriptionService service;

    public AscriptionController(AscriptionService service) {
        this.service = service;
    }

    // ========================================================================
    // CREATE
    // ========================================================================

    @PostMapping
    public ResponseEntity<EntityModel<AscriptionDto>> create(
            @Valid @RequestBody AscriptionRequestDto request) {
        AscriptionEntity entity = service.create(request.archetypeId(), request.compilation(), request.definitionId());
        AscriptionDto response = toAscriptionDto(entity);
        EntityModel<AscriptionDto> model = wrapWithLinks(response);
        URI location = selfLinkFor(response).toUri();
        return ResponseEntity.created(location).body(model);
    }

    // ========================================================================
    // READ
    // ========================================================================

    @GetMapping("/{id}")
    public EntityModel<AscriptionDto> getById(@PathVariable UUID id) {
        AscriptionEntity entity = service.getById(id);
        return wrapWithLinks(toAscriptionDto(entity));
    }

    @GetMapping
    public PagedModel<EntityModel<AscriptionDto>> list(
            @RequestParam String type,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20) Pageable pageable) {
        AscriptionStatusType statusEnum = (status != null) ? AscriptionStatusType.valueOf(status) : null;
        Page<AscriptionEntity> page = service.list(type, statusEnum, pageable);
        DefinitionSubjectType subjectType = DefinitionSubjectType.fromValue(type);
        Page<AscriptionDto> dtoPage = page.map(e -> toAscriptionDto(subjectType, e));
        return toPagedModel(dtoPage, type, status);
    }

    @GetMapping("/history")
    public List<EntityModel<AscriptionDto>> getAscriptionHistory(
            @RequestParam UUID definitionId, @RequestParam String type) {
        return service.getAscriptionHistory(definitionId, type).stream()
                .map(e -> wrapWithLinks(toAscriptionDto(e)))
                .toList();
    }

    @GetMapping("/{id}/transitions")
    public List<AscriptionStatusTransitionDto> getTransitions(@PathVariable UUID id) {
        return service.getTransitions(id).stream()
                .map(this::toTransitionDto)
                .toList();
    }

    // ========================================================================
    // LIFECYCLE TRANSITIONS
    // ========================================================================

    @PostMapping("/{id}/transitions")
    public ResponseEntity<AscriptionStatusTransitionDto> transition(
            @PathVariable UUID id, @Valid @RequestBody AscriptionStatusTransitionRequestDto request) {
        AscriptionStatusTransitionEntity saved = service.transition(id, request.targetStatus());
        return ResponseEntity.ok(toTransitionDto(saved));
    }

    // ========================================================================
    // MAPPING HELPERS
    // ========================================================================

    private AscriptionDto toAscriptionDto(AscriptionEntity entity) {
        return toAscriptionDto(entity.getDefinition().getSubjectType(), entity);
    }

    // ========================================================================
    // HATEOAS HELPERS
    // ========================================================================

    private EntityModel<AscriptionDto> wrapWithLinks(AscriptionDto response) {
        EntityModel<AscriptionDto> model = EntityModel.of(Objects.requireNonNull(response));
        model.add(selfLinkFor(response));
        model.add(historyLinkFor(response));
        model.add(transitionsLinkFor(response));
        return model;
    }

    private PagedModel<EntityModel<AscriptionDto>> toPagedModel(
            Page<AscriptionDto> page, String type, String status) {
        List<EntityModel<AscriptionDto>> content = page.getContent().stream().map(this::wrapWithLinks).toList();
        PagedModel.PageMetadata metadata = new PagedModel.PageMetadata(
                page.getSize(), page.getNumber(),
                page.getTotalElements(), page.getTotalPages());
        PagedModel<EntityModel<AscriptionDto>> model = PagedModel.of(Objects.requireNonNull(content), metadata);
        model.add(listSelfLink(type, status, page.getPageable()));
        return model;
    }

    private @NonNull Link selfLinkFor(AscriptionDto response) {
        return linkTo(AscriptionController.class)
                .slash(Objects.requireNonNull(response.id()))
                .withSelfRel();
    }

    private @NonNull Link historyLinkFor(AscriptionDto response) {
        String href = linkTo(AscriptionController.class)
                .slash("history")
                .toUriComponentsBuilder()
                .queryParam("definitionId", Objects.requireNonNull(response.definitionId()))
                .queryParam("type", Objects.requireNonNull(response.subjectType()))
                .toUriString();
        return Link.of(href, "history");
    }

    private @NonNull Link transitionsLinkFor(AscriptionDto response) {
        return linkTo(AscriptionController.class)
                .slash(Objects.requireNonNull(response.id()))
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
