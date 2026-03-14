package io.poesis.sie.defman.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.poesis.sie.defman.entity.AscriptionEntity;
import io.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import io.poesis.sie.defman.service.AbstractAscriptionService.RefereeReference;
import io.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import io.poesis.sie.defman.type.AscriptionStatusType;
import io.poesis.sie.defman.type.DefinitionSubjectType;
import jakarta.persistence.EntityManager;

/**
 * Orchestrates GSM lifecycle transitions with referee preconditions,
 * inter-transition cascades, governance convergence, and identity-bound
 * validation.
 *
 * <p>
 * Sourced from {@code gsm-ascription-lifecycle} state machine diagram.
 * Replaces inline lifecycle logic formerly in {@link AscriptionService}.
 */
@Service
public class AscriptionLifecycleService {

    // ======================================================================
    // State machine: valid transitions
    // ======================================================================

    private static final Map<AscriptionStatusType, Set<AscriptionStatusType>> VALID_TRANSITIONS;

    static {
        VALID_TRANSITIONS = new EnumMap<>(AscriptionStatusType.class);
        VALID_TRANSITIONS.put(AscriptionStatusType.DRAFT,
                EnumSet.of(AscriptionStatusType.PROPOSED, AscriptionStatusType.ABANDONED));
        VALID_TRANSITIONS.put(AscriptionStatusType.PROPOSED,
                EnumSet.of(AscriptionStatusType.APPROVED, AscriptionStatusType.REJECTED));
        VALID_TRANSITIONS.put(AscriptionStatusType.APPROVED,
                EnumSet.of(AscriptionStatusType.ACTIVE));
        VALID_TRANSITIONS.put(AscriptionStatusType.ACTIVE,
                EnumSet.of(AscriptionStatusType.SUSPENDED, AscriptionStatusType.DEPRECATED));
        VALID_TRANSITIONS.put(AscriptionStatusType.SUSPENDED,
                EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED));
        VALID_TRANSITIONS.put(AscriptionStatusType.DEPRECATED,
                EnumSet.of(AscriptionStatusType.SUSPENDED, AscriptionStatusType.RETIRED));
        VALID_TRANSITIONS.put(AscriptionStatusType.RETIRED,
                EnumSet.noneOf(AscriptionStatusType.class));
        VALID_TRANSITIONS.put(AscriptionStatusType.ABANDONED,
                EnumSet.noneOf(AscriptionStatusType.class));
        VALID_TRANSITIONS.put(AscriptionStatusType.REJECTED,
                EnumSet.noneOf(AscriptionStatusType.class));
    }

    // ======================================================================
    // Referee precondition: allowed reference statuses per transition
    // ======================================================================

    private static final Set<AscriptionStatusType> IN_EFFECT = EnumSet.of(
            AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);

    private static final Set<AscriptionStatusType> IN_EFFECT_OR_SUSPENDED = EnumSet.of(
            AscriptionStatusType.ACTIVE, AscriptionStatusType.SUSPENDED,
            AscriptionStatusType.DEPRECATED);

    private static final Set<AscriptionStatusType> IN_EFFECT_OR_SUSPENDED_OR_RETIRED = EnumSet.of(
            AscriptionStatusType.ACTIVE, AscriptionStatusType.SUSPENDED,
            AscriptionStatusType.DEPRECATED, AscriptionStatusType.RETIRED);

    private static final Set<AscriptionStatusType> ANY_STATUS = EnumSet.allOf(AscriptionStatusType.class);

    /**
     * Per-transition referee precondition: which statuses each Reference must be
     * in.
     * Key: "fromStatus->toStatus"
     */
    private static final Map<String, Set<AscriptionStatusType>> REFEREE_ALLOWED;

    static {
        REFEREE_ALLOWED = new HashMap<>();
        REFEREE_ALLOWED.put(transitionKey(null, AscriptionStatusType.DRAFT),
                EnumSet.of(AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED,
                        AscriptionStatusType.APPROVED, AscriptionStatusType.ACTIVE));
        REFEREE_ALLOWED.put(transitionKey(AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED),
                EnumSet.of(AscriptionStatusType.PROPOSED, AscriptionStatusType.APPROVED,
                        AscriptionStatusType.ACTIVE));
        REFEREE_ALLOWED.put(transitionKey(AscriptionStatusType.PROPOSED, AscriptionStatusType.APPROVED),
                EnumSet.of(AscriptionStatusType.APPROVED, AscriptionStatusType.ACTIVE));
        REFEREE_ALLOWED.put(transitionKey(AscriptionStatusType.APPROVED, AscriptionStatusType.ACTIVE),
                EnumSet.of(AscriptionStatusType.ACTIVE));
        REFEREE_ALLOWED.put(transitionKey(AscriptionStatusType.ACTIVE, AscriptionStatusType.SUSPENDED),
                IN_EFFECT_OR_SUSPENDED);
        REFEREE_ALLOWED.put(transitionKey(AscriptionStatusType.SUSPENDED, AscriptionStatusType.ACTIVE),
                IN_EFFECT);
        REFEREE_ALLOWED.put(transitionKey(AscriptionStatusType.SUSPENDED, AscriptionStatusType.DEPRECATED),
                IN_EFFECT_OR_SUSPENDED);
        REFEREE_ALLOWED.put(transitionKey(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED),
                IN_EFFECT_OR_SUSPENDED);
        REFEREE_ALLOWED.put(transitionKey(AscriptionStatusType.DEPRECATED, AscriptionStatusType.SUSPENDED),
                IN_EFFECT_OR_SUSPENDED);
        REFEREE_ALLOWED.put(transitionKey(AscriptionStatusType.DEPRECATED, AscriptionStatusType.RETIRED),
                IN_EFFECT_OR_SUSPENDED_OR_RETIRED);
        REFEREE_ALLOWED.put(transitionKey(AscriptionStatusType.DRAFT, AscriptionStatusType.ABANDONED),
                ANY_STATUS);
        // PROPOSED→REJECTED: all statuses except DRAFT (SC-23: DRAFT blocks rejection)
        REFEREE_ALLOWED.put(transitionKey(AscriptionStatusType.PROPOSED, AscriptionStatusType.REJECTED),
                EnumSet.of(AscriptionStatusType.PROPOSED, AscriptionStatusType.APPROVED,
                        AscriptionStatusType.ACTIVE, AscriptionStatusType.SUSPENDED,
                        AscriptionStatusType.DEPRECATED, AscriptionStatusType.ABANDONED,
                        AscriptionStatusType.REJECTED, AscriptionStatusType.RETIRED));
    }

    // ======================================================================
    // Dependent cascade scope: only these transitions trigger dependent cascades
    // ======================================================================

    private static final Map<AscriptionStatusType, Set<AscriptionStatusType>> DEPENDENT_CASCADE_SCOPE;

    static {
        DEPENDENT_CASCADE_SCOPE = new EnumMap<>(AscriptionStatusType.class);
        DEPENDENT_CASCADE_SCOPE.put(AscriptionStatusType.ACTIVE,
                EnumSet.of(AscriptionStatusType.SUSPENDED, AscriptionStatusType.DEPRECATED));
        DEPENDENT_CASCADE_SCOPE.put(AscriptionStatusType.SUSPENDED,
                EnumSet.of(AscriptionStatusType.DEPRECATED));
        DEPENDENT_CASCADE_SCOPE.put(AscriptionStatusType.DEPRECATED,
                EnumSet.of(AscriptionStatusType.SUSPENDED, AscriptionStatusType.RETIRED));
        DEPENDENT_CASCADE_SCOPE.put(AscriptionStatusType.DRAFT,
                EnumSet.of(AscriptionStatusType.ABANDONED));
        DEPENDENT_CASCADE_SCOPE.put(AscriptionStatusType.PROPOSED,
                EnumSet.of(AscriptionStatusType.REJECTED));
    }

    // ======================================================================
    // Cascade graph (built at startup from subtype declarations)
    // ======================================================================

    private record CascadeTargetEntry(
            AbstractAscriptionService targetService,
            AscriptionStatusTransitionCascadeType cascadeType) {
    }

    private final Map<DefinitionSubjectType, AbstractAscriptionService> subtypeByType;
    private final Map<DefinitionSubjectType, List<CascadeTargetEntry>> cascadeTargetsBySourceType;
    private final AscriptionStatusTransitionService transitionService;
    private final EntityManager entityManager;

    public AscriptionLifecycleService(
            List<AbstractAscriptionService> subtypeServices,
            AscriptionStatusTransitionService transitionService,
            EntityManager entityManager) {
        this.transitionService = transitionService;
        this.entityManager = entityManager;

        // Build subtype lookup map
        Map<DefinitionSubjectType, AbstractAscriptionService> byType = new EnumMap<>(
                DefinitionSubjectType.class);
        for (AbstractAscriptionService svc : subtypeServices) {
            byType.put(svc.getSubjectType(), svc);
        }
        this.subtypeByType = Map.copyOf(byType);

        // Build cascade graph (inverted from target declarations)
        Map<DefinitionSubjectType, List<CascadeTargetEntry>> cascadeMap = new EnumMap<>(DefinitionSubjectType.class);
        for (AbstractAscriptionService svc : subtypeServices) {
            for (var entry : svc.getCascadeTargetRoles().entrySet()) {
                cascadeMap.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .add(new CascadeTargetEntry(svc, entry.getValue()));
            }
        }
        this.cascadeTargetsBySourceType = Map.copyOf(cascadeMap);
    }

    // ======================================================================
    // TRANSITION (main orchestrator)
    // ======================================================================

    /**
     * Executes a lifecycle transition with full lifecycle governance:
     * validation, referee preconditions, cascades, convergence.
     *
     * @param ascriptionId the ascription to transition
     * @param targetStatus the requested target status
     * @return the persisted transition record
     */
    public AscriptionStatusTransitionEntity transition(UUID ascriptionId, String targetStatus) {
        AscriptionStatusType targetStatusType = AscriptionStatusType.valueOf(targetStatus);

        AscriptionEntity entity = entityManager.find(AscriptionEntity.class, ascriptionId);
        if (entity == null) {
            throw new IllegalArgumentException("No ascription found for id: " + ascriptionId);
        }

        DefinitionSubjectType type = entity.getDefinition().getSubjectType();
        AscriptionStatusType currentStatus = entity.getStatus();

        // 1. Validate state machine transition
        validateTransition(currentStatus, targetStatusType);

        // 2. Check referee preconditions
        validateRefereePreconditions(entity, type, currentStatus, targetStatusType);

        // 3. Activation uniqueness (Structure purpose, Mechanism function, Archetype
        // schema.title)
        if (targetStatusType == AscriptionStatusType.ACTIVE) {
            validateActivationUniqueness(entity, type);
        }

        // 3b. Subtype-specific activation hook
        if (targetStatusType == AscriptionStatusType.ACTIVE) {
            AbstractAscriptionService svc = subtypeByType.get(type);
            if (svc != null) {
                svc.onActivation(entity);
            }
        }

        // 3c. Subtype-specific deactivation hook (leaving in-effect)
        if (IN_EFFECT.contains(currentStatus) && !IN_EFFECT.contains(targetStatusType)) {
            AbstractAscriptionService svc = subtypeByType.get(type);
            if (svc != null) {
                svc.onDeactivation(entity);
            }
        }

        // 4. Record transition (DB trigger updates entity status/version)
        AscriptionStatusTransitionEntity saved = recordTransition(entity, currentStatus, targetStatusType);

        // 5. Governance convergence (APPROVED → sibling termination)
        if (targetStatusType == AscriptionStatusType.APPROVED) {
            handleApproval(type, entity);
        }

        // 6. Activation (ACTIVE → previous ACTIVE to DEPRECATED)
        if (targetStatusType == AscriptionStatusType.ACTIVE) {
            handleActivation(type, entity);
        }

        // 7. Execute cascades
        executeCascades(entity, type, currentStatus, targetStatusType);

        return saved;
    }

    // ======================================================================
    // TRANSITION QUERIES
    // ======================================================================

    public List<AscriptionStatusTransitionEntity> getTransitions(UUID ascriptionId) {
        return transitionService.findByAscriptionId(ascriptionId);
    }

    // ======================================================================
    // Activation uniqueness validation
    // ======================================================================

    private void validateActivationUniqueness(AscriptionEntity entity, DefinitionSubjectType type) {
        AbstractAscriptionService subtypeService = subtypeByType.get(type);
        if (subtypeService != null) {
            subtypeService.validateActivationUniqueness(entity);
        }
    }

    // ======================================================================
    // Transition validation
    // ======================================================================

    private void validateTransition(AscriptionStatusType from, AscriptionStatusType to) {
        Set<AscriptionStatusType> allowed = VALID_TRANSITIONS.getOrDefault(
                from, EnumSet.noneOf(AscriptionStatusType.class));
        if (!allowed.contains(to)) {
            throw new IllegalArgumentException("Invalid transition: " + from + " -> " + to);
        }
    }

    // ======================================================================
    // Referee preconditions
    // ======================================================================

    private void validateRefereePreconditions(
            AscriptionEntity entity, DefinitionSubjectType type,
            AscriptionStatusType from, AscriptionStatusType to) {

        AbstractAscriptionService subtypeService = subtypeByType.get(type);
        if (subtypeService == null) {
            return;
        }

        List<RefereeReference> refs = subtypeService.getRefereeReferences(entity);
        if (refs.isEmpty()) {
            return;
        }

        String key = transitionKey(from, to);
        Set<AscriptionStatusType> allowed = REFEREE_ALLOWED.get(key);
        if (allowed == null) {
            return; // No precondition for this transition
        }

        for (RefereeReference ref : refs) {
            AscriptionStatusType refStatus = ref.reference().getStatus();
            if (!allowed.contains(refStatus)) {
                throw new IllegalArgumentException(
                        "Referee precondition failed for " + from + "->" + to
                                + ": reference '" + ref.label() + "' (id=" + ref.reference().getId()
                                + ") is in status " + refStatus
                                + ", must be one of " + allowed);
            }
        }
    }

    // ======================================================================
    // Cascade execution
    // ======================================================================

    private void executeCascades(
            AscriptionEntity source, DefinitionSubjectType sourceType,
            AscriptionStatusType fromStatus, AscriptionStatusType toStatus) {

        List<CascadeTargetEntry> targetEntries = cascadeTargetsBySourceType.get(sourceType);
        if (targetEntries == null) {
            return;
        }

        for (CascadeTargetEntry entry : targetEntries) {
            // Check scope: dependent cascades only fire for specific transitions
            if (entry.cascadeType() == AscriptionStatusTransitionCascadeType.DEPENDENT
                    && !isDependentCascadeApplicable(fromStatus, toStatus)) {
                continue;
            }

            List<? extends AscriptionEntity> targets = entry.targetService()
                    .findCascadeTargetsFrom(sourceType, source.getId());

            for (AscriptionEntity target : targets) {
                // Cascade evaluation rule 1: target.status must == fromStatus
                if (target.getStatus() != fromStatus) {
                    if (entry.cascadeType() == AscriptionStatusTransitionCascadeType.CONSTITUTIVE) {
                        throw new IllegalStateException(
                                "Constitutive cascade failed: target " + target.getId()
                                        + " (type=" + entry.targetService().getSubjectType()
                                        + ") has status " + target.getStatus()
                                        + ", expected " + fromStatus);
                    }
                    continue; // GOVERNING / DEPENDENT: no-op
                }

                // Cascade evaluation rule 2: check target's referee preconditions
                DefinitionSubjectType targetType = entry.targetService().getSubjectType();
                try {
                    validateRefereePreconditions(target, targetType, fromStatus, toStatus);
                } catch (IllegalArgumentException e) {
                    if (entry.cascadeType() == AscriptionStatusTransitionCascadeType.CONSTITUTIVE) {
                        throw new IllegalStateException(
                                "Constitutive cascade blocked: " + e.getMessage(), e);
                    }
                    continue; // GOVERNING / DEPENDENT: no-op
                }

                // Cascade evaluation rule 3: transition the target (recursive)
                recordTransition(target, fromStatus, toStatus);

                // Recurse: the target's own cascades
                executeCascades(target, targetType, fromStatus, toStatus);
            }
        }
    }

    private boolean isDependentCascadeApplicable(
            AscriptionStatusType from, AscriptionStatusType to) {
        Set<AscriptionStatusType> targets = DEPENDENT_CASCADE_SCOPE.get(from);
        return targets != null && targets.contains(to);
    }

    // ======================================================================
    // Governance convergence
    // ======================================================================

    private void handleApproval(DefinitionSubjectType type, AscriptionEntity approved) {
        UUID definitionId = approved.getDefinition().getId();
        AbstractAscriptionService svc = subtypeByType.get(type);
        if (svc == null)
            return;

        List<? extends AscriptionEntity> allAscriptions = svc.findAllByDefinitionId(definitionId);
        for (AscriptionEntity sibling : allAscriptions) {
            if (sibling.getId().equals(approved.getId()))
                continue;
            AscriptionStatusType siblingStatus = sibling.getStatus();
            AscriptionStatusType terminalStatus;
            if (siblingStatus == AscriptionStatusType.DRAFT) {
                terminalStatus = AscriptionStatusType.ABANDONED;
            } else if (siblingStatus == AscriptionStatusType.PROPOSED) {
                terminalStatus = AscriptionStatusType.REJECTED;
            } else {
                continue;
            }
            recordTransition(sibling, siblingStatus, terminalStatus);
        }
    }

    private void handleActivation(DefinitionSubjectType type, AscriptionEntity activating) {
        UUID definitionId = activating.getDefinition().getId();
        AbstractAscriptionService svc = subtypeByType.get(type);
        if (svc == null)
            return;

        List<? extends AscriptionEntity> activeAscriptions = svc.findAllByDefinitionIdAndStatus(
                definitionId, List.of(AscriptionStatusType.ACTIVE));
        for (AscriptionEntity prev : activeAscriptions) {
            if (prev.getId().equals(activating.getId()))
                continue;
            recordTransition(prev, AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);
        }
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private AscriptionStatusTransitionEntity recordTransition(
            AscriptionEntity entity, AscriptionStatusType from, AscriptionStatusType to) {
        return transitionService.recordTransition(entity, from, to);
    }

    private static String transitionKey(AscriptionStatusType from, AscriptionStatusType to) {
        return (from == null ? "*" : from.name()) + "->" + to.name();
    }
}
