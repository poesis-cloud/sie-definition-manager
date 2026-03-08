package org.sif.sie.dm.api;

import jakarta.validation.Valid;
import org.sif.sie.dm.api.dto.AscriptionRequest;
import org.sif.sie.dm.api.dto.AscriptionResponse;
import org.sif.sie.dm.api.dto.TransitionRequest;
import org.sif.sie.dm.api.dto.TransitionResponse;
import org.sif.sie.dm.model.enums.AscriptionStatus;
import org.sif.sie.dm.service.AscriptionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.MediaTypes;
import org.springframework.hateoas.PagedModel;
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * Unified REST controller for all GSM ascription types.
 * <p>
 * Single resource endpoint {@code /api/v1/ascriptions} — the archetype
 * (via archetypeId) discriminates the entity type, not the URL path.
 */
@RestController
@RequestMapping(value = "/api/v1/ascriptions",
        produces = {MediaTypes.HAL_JSON_VALUE, MediaType.APPLICATION_JSON_VALUE})
public class AscriptionController {

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
        URI location = linkTo(methodOn(AscriptionController.class)
                .getByRevisionId(response.revisionId())).toUri();
        return ResponseEntity.created(location).body(model);
    }

    // ========================================================================
    // READ
    // ========================================================================

    @GetMapping("/{revisionId}")
    public EntityModel<AscriptionResponse> getByRevisionId(
            @PathVariable UUID revisionId) {
        AscriptionResponse response = service.getByRevisionId(revisionId);
        return wrapWithLinks(response);
    }

    @GetMapping
    public PagedModel<EntityModel<AscriptionResponse>> list(
            @RequestParam String type,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20) Pageable pageable) {
        AscriptionStatus statusEnum = (status != null)
                ? AscriptionStatus.valueOf(status)
                : null;
        Page<AscriptionResponse> page = service.list(type, statusEnum, pageable);
        return toPagedModel(page, type, status);
    }

    @GetMapping("/{revisionId}/revisions")
    public List<EntityModel<AscriptionResponse>> getRevisionHistory(
            @PathVariable UUID revisionId) {
        return service.getRevisionHistory(revisionId).stream()
                .map(this::wrapWithLinks)
                .toList();
    }

    @GetMapping("/{revisionId}/transitions")
    public List<TransitionResponse> getTransitions(
            @PathVariable UUID revisionId) {
        return service.getTransitions(revisionId);
    }

    // ========================================================================
    // LIFECYCLE TRANSITIONS
    // ========================================================================

    @PostMapping("/{revisionId}/transitions")
    public ResponseEntity<TransitionResponse> transition(
            @PathVariable UUID revisionId,
            @Valid @RequestBody TransitionRequest request) {
        TransitionResponse response = service.transition(revisionId, request);
        return ResponseEntity.ok(response);
    }

    // ========================================================================
    // HATEOAS HELPERS
    // ========================================================================

    private EntityModel<AscriptionResponse> wrapWithLinks(AscriptionResponse response) {
        EntityModel<AscriptionResponse> model = EntityModel.of(response);
        model.add(linkTo(methodOn(AscriptionController.class)
                .getByRevisionId(response.revisionId())).withSelfRel());
        model.add(linkTo(methodOn(AscriptionController.class)
                .getRevisionHistory(response.revisionId())).withRel("revisions"));
        model.add(linkTo(methodOn(AscriptionController.class)
                .getTransitions(response.revisionId())).withRel("transitions"));
        return model;
    }

    private PagedModel<EntityModel<AscriptionResponse>> toPagedModel(
            Page<AscriptionResponse> page, String type, String status) {
        List<EntityModel<AscriptionResponse>> content = page.getContent().stream()
                .map(this::wrapWithLinks)
                .toList();
        PagedModel.PageMetadata metadata = new PagedModel.PageMetadata(
                page.getSize(), page.getNumber(),
                page.getTotalElements(), page.getTotalPages());
        PagedModel<EntityModel<AscriptionResponse>> model = PagedModel.of(content, metadata);
        // self link
        model.add(linkTo(methodOn(AscriptionController.class)
                .list(type, status, page.getPageable())).withSelfRel());
        return model;
    }
}
