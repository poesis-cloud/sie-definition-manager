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

import com.sif.sie.definitionmanager.dto.AscriptionRequestDto;
import com.sif.sie.definitionmanager.dto.AscriptionResponseDto;
import com.sif.sie.definitionmanager.dto.TransitionRequestDto;
import com.sif.sie.definitionmanager.dto.TransitionResponseDto;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;
import com.sif.sie.definitionmanager.service.DefinitionService;

import jakarta.validation.Valid;

/**
 * Unified REST controller for all GSM ascription types.
 *
 * <p>
 * Single resource endpoint {@code /api/v1/ascriptions} — the archetype (via
 * archetypeId)
 * discriminates the entity type, not the URL path.
 */
@RestController
@RequestMapping(value = "/api/v1/ascriptions", produces = { MediaTypes.HAL_JSON_VALUE,
        MediaType.APPLICATION_JSON_VALUE })
public class DefinitionController extends Controller {

    private final DefinitionService service;

    public DefinitionController(DefinitionService service) {
        this.service = service;
    }

    // ========================================================================
    // CREATE
    // ========================================================================

    @PostMapping
    public ResponseEntity<EntityModel<AscriptionResponseDto>> create(
            @Valid @RequestBody AscriptionRequestDto request) {
        AscriptionResponseDto response = service.create(request);
        EntityModel<AscriptionResponseDto> model = wrapWithLinks(response);
        URI location = selfLinkFor(response).toUri();
        return ResponseEntity.created(location).body(model);
    }

    // ========================================================================
    // READ
    // ========================================================================

    @GetMapping("/{id}")
    public EntityModel<AscriptionResponseDto> getById(@PathVariable UUID id) {
        AscriptionResponseDto response = service.getById(id);
        return wrapWithLinks(response);
    }

    @GetMapping
    public PagedModel<EntityModel<AscriptionResponseDto>> list(
            @RequestParam String type,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20) Pageable pageable) {
        AscriptionStatusType statusEnum = (status != null) ? AscriptionStatusType.valueOf(status) : null;
        Page<AscriptionResponseDto> page = service.list(type, statusEnum, pageable);
        return toPagedModel(page, type, status);
    }

    @GetMapping("/history")
    public List<EntityModel<AscriptionResponseDto>> getAscriptionHistory(
            @RequestParam UUID definitionId, @RequestParam String type) {
        return service.getAscriptionHistory(definitionId, type).stream()
                .map(this::wrapWithLinks)
                .toList();
    }

    @GetMapping("/{id}/transitions")
    public List<TransitionResponseDto> getTransitions(@PathVariable UUID id) {
        return service.getTransitions(id);
    }

    // ========================================================================
    // LIFECYCLE TRANSITIONS
    // ========================================================================

    @PostMapping("/{id}/transitions")
    public ResponseEntity<TransitionResponseDto> transition(
            @PathVariable UUID id, @Valid @RequestBody TransitionRequestDto request) {
        TransitionResponseDto response = service.transition(id, request);
        return ResponseEntity.ok(response);
    }

    // ========================================================================
    // HATEOAS HELPERS
    // ========================================================================

    private EntityModel<AscriptionResponseDto> wrapWithLinks(AscriptionResponseDto response) {
        EntityModel<AscriptionResponseDto> model = EntityModel.of(Objects.requireNonNull(response));
        model.add(selfLinkFor(response));
        model.add(historyLinkFor(response));
        model.add(transitionsLinkFor(response));
        return model;
    }

    private PagedModel<EntityModel<AscriptionResponseDto>> toPagedModel(
            Page<AscriptionResponseDto> page, String type, String status) {
        List<EntityModel<AscriptionResponseDto>> content = page.getContent().stream().map(this::wrapWithLinks).toList();
        PagedModel.PageMetadata metadata = new PagedModel.PageMetadata(
                page.getSize(), page.getNumber(),
                page.getTotalElements(), page.getTotalPages());
        PagedModel<EntityModel<AscriptionResponseDto>> model = PagedModel.of(Objects.requireNonNull(content), metadata);
        model.add(listSelfLink(type, status, page.getPageable()));
        return model;
    }

    private @NonNull Link selfLinkFor(AscriptionResponseDto response) {
        return linkTo(DefinitionController.class)
                .slash(Objects.requireNonNull(response.id()))
                .withSelfRel();
    }

    private @NonNull Link historyLinkFor(AscriptionResponseDto response) {
        String href = linkTo(DefinitionController.class)
                .slash("history")
                .toUriComponentsBuilder()
                .queryParam("definitionId", Objects.requireNonNull(response.definitionId()))
                .queryParam("type", Objects.requireNonNull(response.subjectType()))
                .toUriString();
        return Link.of(href, "history");
    }

    private @NonNull Link transitionsLinkFor(AscriptionResponseDto response) {
        return linkTo(DefinitionController.class)
                .slash(Objects.requireNonNull(response.id()))
                .slash("transitions")
                .withRel("transitions");
    }

    private @NonNull Link listSelfLink(String type, String status, Pageable pageable) {
        var builder = linkTo(DefinitionController.class)
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
