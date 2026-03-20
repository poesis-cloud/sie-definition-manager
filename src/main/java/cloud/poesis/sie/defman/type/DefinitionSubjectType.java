package cloud.poesis.sie.defman.type;

/**
 * GSM structural role of a Definition — what kind of primitive the subject is.
 * Sealed enum: tenants MUST NOT extend it. Domain-level typing extensibility is
 * expressed through Archetypes.
 *
 * <p>
 * Maps to PostgreSQL enum {@code definition_subject_type}. Values stored as
 * uppercase enum names via {@link org.hibernate.annotations.JdbcTypeCode} /
 * {@link org.hibernate.type.SqlTypes#NAMED_ENUM}.
 *
 * <p>
 * The lowercase {@link #value} is provided for API-layer compatibility (e.g.,
 * query parameters, OpenAPI type discriminators).
 *
 * @author Clément Cazaud
 * @since 0.1.0
 */
public enum DefinitionSubjectType {
    ARCHETYPE(PrimitiveType.ARCHETYPE),
    STRUCTURE(PrimitiveType.STRUCTURE),
    MECHANISM(PrimitiveType.MECHANISM),
    EFFECTOR(PrimitiveType.EFFECTOR),
    RECEPTOR(PrimitiveType.RECEPTOR),
    INTERACTION(PrimitiveType.INTERACTION),
    DIRECTIVE(PrimitiveType.DIRECTIVE),
    NORM(PrimitiveType.NORM);

    private final PrimitiveType primitiveType;

    DefinitionSubjectType(PrimitiveType primitiveType) {
        this.primitiveType = primitiveType;
    }

    /**
     * Returns the canonical primitive type backing this subject type.
     *
     * @return the backing primitive type; never {@code null}
     */
    public PrimitiveType getPrimitiveType() {
        return primitiveType;
    }

    /**
     * Returns the lowercase API-layer representation of this subject type.
     *
     * @return the lowercase identifier; never {@code null}
     */
    public String getValue() {
        return primitiveType.getValue();
    }

    /**
     * Resolves a {@code DefinitionSubjectType} from its lowercase value.
     *
     * @param value the lowercase string to resolve
     * @return the matching enum constant
     * @throws IllegalArgumentException if no constant matches {@code value}
     */
    public static DefinitionSubjectType fromValue(String value) {
        PrimitiveType primitiveType = PrimitiveType.fromValue(value);
        for (DefinitionSubjectType type : values()) {
            if (type.primitiveType == primitiveType) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown definition_subject_type: " + value);
    }
}
