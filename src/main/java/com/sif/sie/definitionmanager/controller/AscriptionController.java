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

import com.sif.sie.definitionmanager.controller.dto.AscriptionRequest;
import com.sif.sie.definitionmanager.controller.dto.AscriptionResponse;
import com.sif.sie.definitionmanager.controller.dto.TransitionRequest;
import com.sif.sie.definitionmanager.controller.dto.TransitionResponse;
import com.sif.sie.definitionmanager.enums.AscriptionStatus;
import com.sif.sie.definitionmanager.service.AscriptionService;

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
public class AscriptionController extends BaseController {

    private final AscriptionService service;

    public AscriptionController(AscriptionService service) {
        this.service = service;
    }

    // ========================================================================
    // CREATE
    // ========================================================================

    @PostMapping
    public ResponseEntity<EntityModel<AscriptionResponse>> create(
            @Valid @RequestBody AscriptionRequest request) {
        AscriptionResponse response = service.create(request);
        EntityModel<AscriptionResponse> model = wrapWithLinks(response);
        URI location = selfLinkFor(response).toUri();
        return ResponseEntity.created(location).body(model);
    }

    // ========================================================================
    // READ
    // ========================================================================

    @GetMapping("/{id}")
    public EntityModel<AscriptionResponse> getById(@PathVariable UUID id) {
        AscriptionResponse response = service.getById(id);
        return wrapWithLinks(response);
    }

    @GetMapping
    public PagedModel<EntityModel<AscriptionResponse>> list(
            @RequestParam String type,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20) Pageable pageable) {
        AscriptionStatus statusEnum = (status != null) ? AscriptionStatus.valueOf(status) : null;
        Page<AscriptionResponse> page = service.list(type, statusEnum, pageable);
        return toPagedModel(page, type, status);
    }

    @GetMapping("/history")
    public List<EntityModel<AscriptionResponse>> getAscriptionHistory(
            @RequestParam UUID definitionId, @RequestParam String type) {
        return service.getAscriptionHistory(definitionId, type).stream()
                .map(this::wrapWithLinks)
                .toList();
    }

    @GetMapping("/{id}/transitions")
    public List<TransitionResponse> getTransitions(@PathVariable UUID id) {
        return service.getTransitions(id);
    }

    // ========================================================================
    // LIFECYCLE TRANSITIONS
    // ========================================================================

    @PostMapping("/{id}/transitions")
    public ResponseEntity<TransitionResponse> transition(
            @PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        TransitionResponse response = service.transition(id, request);
        return ResponseEntity.ok(response);
    }

    // ========================================================================
    // HATEOAS HELPERS
    // ========================================================================

    private EntityModel<AscriptionResponse> wrapWithLinks(AscriptionResponse response) {
        EntityModel<AscriptionResponse> model = EntityModel.of(Objects.requireNonNull(response));
        model.add(selfLinkFor(response));
        model.add(historyLinkFor(response));
        model.add(transitionsLinkFor(response));
        return model;
    }

    private PagedModel<EntityModel<AscriptionResponse>> toPagedModel(
            Page<AscriptionResponse> page, String type, String status) {
        List<EntityModel<AscriptionResponse>> content = page.getContent().stream().map(this::wrapWithLinks).toList();
        PagedModel.PageMetadata metadata = new PagedModel.PageMetadata(
                page.getSize(), page.getNumber(),
                page.getTotalElements(), page.getTotalPages());
        PagedModel<EntityModel<AscriptionResponse>> model = PagedModel.of(Objects.requireNonNull(content), metadata);
        model.add(listSelfLink(type, status, page.getPageable()));
        return model;
    }

    private @NonNull Link selfLinkFor(AscriptionResponse response) {
        return linkTo(AscriptionController.class)
                .slash(Objects.requireNonNull(response.id()))
                .withSelfRel();
    }

    private @NonNull Link historyLinkFor(AscriptionResponse response) {
        String href = linkTo(AscriptionController.class)
                .slash("history")
                .toUriComponentsBuilder()
                .queryParam("definitionId", Objects.requireNonNull(response.definitionId()))
                .queryParam("type", Objects.requireNonNull(response.subjectType()))
                .toUriString();
        return Link.of(href, "history");
    }

    private @NonNull Link transitionsLinkFor(AscriptionResponse response) {
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
