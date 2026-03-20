package cloud.poesis.sie.defman.type;

/**
 * Lifecycle stages of a GSM Ascription.
 *
 * <p>
 * Maps to PostgreSQL enum type {@code ascription_status}.
 * </p>
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public enum AscriptionStatusType {
    DRAFT,
    PROPOSED,
    APPROVED,
    ACTIVE,
    SUSPENDED,
    DEPRECATED,
    RETIRED,
    ABANDONED,
    REJECTED
}
