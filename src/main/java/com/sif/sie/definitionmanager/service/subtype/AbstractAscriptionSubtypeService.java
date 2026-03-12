package com.sif.sie.definitionmanager.service.subtype;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sif.sie.definitionmanager.entity.AscriptionEntity;
import com.sif.sie.definitionmanager.type.CascadeType;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;

/**
 * Abstract base for subtype services. Provides lifecycle descriptor methods
 * that the
 * {@link com.sif.sie.definitionmanager.service.AscriptionLifecycleService}
 * uses for referee preconditions, cascades, and identity-bound validation.
 *
 * <p>
 * Subtypes override the relevant methods; defaults return empty (no references,
 * no cascades, no identity-bound fields).
 */
public abstract class AbstractAscriptionSubtypeService implements AscriptionSubtypeService {

    // ======================================================================
    // Inner types (lifecycle descriptors)
    // ======================================================================

    /**
     * A reference edge: the entity being referenced (referee → reference).
     * Used by the lifecycle service to check referee preconditions.
     */
    public record RefereeReference(AscriptionEntity reference, String label) {
    }

    // ======================================================================
    // Lifecycle descriptor methods
    // ======================================================================

    /**
     * Returns the referee → reference edges for precondition checking.
     * Each reference's status must satisfy the per-transition precondition set.
     *
     * @param entity the entity being transitioned (concrete subtype)
     * @return references this entity depends on; empty if not a referee
     */
    public List<RefereeReference> getRefereeReferences(AscriptionEntity entity) {
        return List.of();
    }

    /**
     * Declares which source types cascade TO this subtype and with what cascade
     * type.
     * Target-centric: "I am a cascade target of sourceType X, with cascadeType Y."
     * The lifecycle service inverts this at startup to build the cascade graph.
     *
     * @return map of sourceType → cascadeType; empty if this subtype receives no
     *         cascades
     */
    public Map<DefinitionSubjectType, CascadeType> getCascadeTargetRoles() {
        return Map.of();
    }

    /**
     * Finds entities of this subtype that should receive a cascade from a given
     * source.
     * Called by the lifecycle service when executing cascades.
     *
     * @param sourceType         the type of the cascading source entity
     * @param sourceAscriptionId the ascription ID of the cascading source
     * @return target entities of this subtype referencing the source; empty if none
     */
    public List<? extends AscriptionEntity> findCascadeTargetsFrom(
            DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
        return List.of();
    }

    /**
     * Extracts identity-bound field values from the entity for cross-version
     * invariant
     * validation. Identity-bound fields MUST NOT change across Ascriptions of the
     * same
     * Definition.
     *
     * <p>
     * For FK-based identity-bound fields, return the reference's Definition ID.
     * For statement-based fields, return the extracted value.
     *
     * @param entity the entity whose identity-bound values to extract
     * @return field name → value map; empty if no identity-bound fields
     */
    public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
        return Map.of();
    }

    /**
     * Validates activation-time uniqueness constraints for this subtype.
     * Called by the lifecycle service when transitioning to ACTIVE.
     * Subtypes override to enforce field uniqueness among in-effect ascriptions.
     *
     * @param entity the entity being activated
     */
    public void validateActivationUniqueness(AscriptionEntity entity) {
        // Default: no activation uniqueness checks
    }
}
