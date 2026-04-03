package cloud.poesis.sie.defman.type;

import cloud.poesis.sie.defman.entity.AscriptionEntity;

/**
 * A reference edge from a referee to its reference: the ascription being referenced by another
 * ascription. Used by the lifecycle services to check referee preconditions during status
 * transitions.
 *
 * @param reference the referenced ascription entity
 * @param label human-readable label for the reference (used in error messages)
 * @author Clément Cazaud
 * @since 1.0.0
 */
public record RefereeReference(AscriptionEntity reference, String label) {}
