package cloud.poesis.sie.defman.controller;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;

import cloud.poesis.sie.defman.dto.AscriptionStatusTransitionCreationDto;
import cloud.poesis.sie.defman.dto.AscriptionStatusTransitionDto;
import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import cloud.poesis.sie.defman.service.AscriptionLifecycleService;
import cloud.poesis.sie.defman.service.DataProtectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller for ascription status transitions (lifecycle audit trail).
 *
 * <p>
 * Sub-resource of ascriptions at
 * {@code /api/v1/ascriptions/{ascriptionId}/transitions}.
 *
 * <p>
 * HAL link relations on every transition resource:
 *
 * <ul>
 * <li>{@code self} — this transition resource
 * <li>{@code collection} — the transition collection for the parent ascription
 * <li>{@code up} — the parent ascription resource
 * <li>{@code first} / {@code last} — first and last transitions in the
 * collection
 * <li>{@code previous} / {@code next} — navigation within the ordered
 * collection
 * <li>{@code create-form} (RFC 6861) — the endpoint that accepts new
 * transitions
 * </ul>
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@RestController
@RequestMapping(value = "/api/v1/ascriptions/{ascriptionId}/transitions", produces = {
        MediaTypes.HAL_JSON_VALUE,
        MediaTypes.HAL_FORMS_JSON_VALUE,
        MediaType.APPLICATION_JSON_VALUE
})
@Tag(name = "Transitions", description = "Lifecycle transitions for GSM ascriptions")
public class AscriptionStatusTransitionController extends AbstractController {

    private final AscriptionLifecycleService lifecycleService;

    /**
     * Constructs the transition controller with its required services.
     *
     * @param lifecycleService      the lifecycle transition service
     * @param dataProtectionService the data protection service
     */
    public AscriptionStatusTransitionController(
            AscriptionLifecycleService lifecycleService, DataProtectionService dataProtectionService) {
        super(dataProtectionService);
        this.lifecycleService = lifecycleService;
    }

    @GetMapping
    @Operation(summary = "Get transition audit trail")
    @ApiResponse(responseCode = "200", description = "Ordered transition records")
    @ApiResponse(responseCode = "404", description = "Ascription not found", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public CollectionModel<EntityModel<AscriptionStatusTransitionDto>> getTransitions(
            @Parameter(description = "Ascription ID") @PathVariable UUID ascriptionId) {
        List<AscriptionStatusTransitionEntity> transitions = lifecycleService.getTransitions(ascriptionId);
        List<EntityModel<AscriptionStatusTransitionDto>> items = new ArrayList<>();
        for (int i = 0; i < transitions.size(); i++) {
            AscriptionStatusTransitionEntity t = transitions.get(i);
            EntityModel<AscriptionStatusTransitionDto> model = EntityModel
                    .of(mapEntityToAscriptionStatusTransitionDto(t));
            addTransitionLinks(model, ascriptionId, t.getId(), transitions, i);
            items.add(model);
        }
        return CollectionModel.of(
                items,
                linkTo(AscriptionStatusTransitionController.class, ascriptionId).withSelfRel());
    }

    @GetMapping("/{transitionId}")
    @Operation(summary = "Get a single transition record")
    @ApiResponse(responseCode = "200", description = "Transition record")
    @ApiResponse(responseCode = "404", description = "Ascription or transition not found", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public EntityModel<AscriptionStatusTransitionDto> getTransition(
            @Parameter(description = "Ascription ID") @PathVariable UUID ascriptionId,
            @Parameter(description = "Transition ID") @PathVariable UUID transitionId) {
        AscriptionStatusTransitionEntity transition = lifecycleService
                .getTransition(transitionId, ascriptionId)
                .orElseThrow(
                        () -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Transition "
                                        + transitionId
                                        + " not found for ascription "
                                        + ascriptionId));
        List<AscriptionStatusTransitionEntity> allTransitions = lifecycleService.getTransitions(ascriptionId);
        int index = -1;
        for (int i = 0; i < allTransitions.size(); i++) {
            if (allTransitions.get(i).getId().equals(transitionId)) {
                index = i;
                break;
            }
        }
        EntityModel<AscriptionStatusTransitionDto> model = EntityModel
                .of(mapEntityToAscriptionStatusTransitionDto(transition));
        addTransitionLinks(model, ascriptionId, transitionId, allTransitions, index);
        return model;
    }

    // ========================================================================
    // LIFECYCLE TRANSITIONS
    // ========================================================================

    @PostMapping
    @Operation(summary = "Transition ascription to a new status")
    @ApiResponse(responseCode = "201", description = "Transition recorded")
    @ApiResponse(responseCode = "400", description = "Invalid transition", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Ascription not found", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<EntityModel<AscriptionStatusTransitionDto>> transition(
            @Parameter(description = "Ascription ID") @PathVariable UUID ascriptionId,
            @Valid @RequestBody AscriptionStatusTransitionCreationDto request) {
        AscriptionStatusTransitionEntity saved = lifecycleService.transition(ascriptionId,
                request.getTargetStatus().name());
        EntityModel<AscriptionStatusTransitionDto> model = EntityModel
                .of(mapEntityToAscriptionStatusTransitionDto(saved));
        List<AscriptionStatusTransitionEntity> allTransitions = lifecycleService.getTransitions(ascriptionId);
        int index = -1;
        for (int i = 0; i < allTransitions.size(); i++) {
            if (allTransitions.get(i).getId().equals(saved.getId())) {
                index = i;
                break;
            }
        }
        addTransitionLinks(model, ascriptionId, saved.getId(), allTransitions, index);
        URI location = linkTo(AscriptionStatusTransitionController.class, ascriptionId)
                .slash(saved.getId())
                .toUri();
        return ResponseEntity.created(location).body(model);
    }

    // ========================================================================
    // LINK BUILDERS
    // ========================================================================

    /** Adds HAL links to an individual transition resource. */
    private void addTransitionLinks(
            EntityModel<AscriptionStatusTransitionDto> model,
            UUID ascriptionId,
            UUID transitionId,
            List<AscriptionStatusTransitionEntity> allTransitions,
            int currentIndex) {
        model.add(
                linkTo(AscriptionStatusTransitionController.class, ascriptionId)
                        .slash(transitionId)
                        .withSelfRel());
        model.add(
                linkTo(AscriptionStatusTransitionController.class, ascriptionId).withRel("collection"));
        model.add(linkTo(AscriptionController.class).slash(ascriptionId).withRel("up"));
        if (!allTransitions.isEmpty()) {
            model.add(
                    linkTo(AscriptionStatusTransitionController.class, ascriptionId)
                            .slash(allTransitions.get(0).getId())
                            .withRel("first"));
            model.add(
                    linkTo(AscriptionStatusTransitionController.class, ascriptionId)
                            .slash(allTransitions.get(allTransitions.size() - 1).getId())
                            .withRel("last"));
        }
        if (currentIndex > 0) {
            model.add(
                    linkTo(AscriptionStatusTransitionController.class, ascriptionId)
                            .slash(allTransitions.get(currentIndex - 1).getId())
                            .withRel("previous"));
        }
        if (currentIndex >= 0 && currentIndex < allTransitions.size() - 1) {
            model.add(
                    linkTo(AscriptionStatusTransitionController.class, ascriptionId)
                            .slash(allTransitions.get(currentIndex + 1).getId())
                            .withRel("next"));
        }
        model.add(
                linkTo(AscriptionStatusTransitionController.class, ascriptionId).withRel("create-form"));
    }
}
