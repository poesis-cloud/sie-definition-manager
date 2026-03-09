package com.sif.sie.definitionmanager.enums;

/**
 * GSM structural role of a Definition — what kind of primitive the subject is. Sealed enum: tenants
 * MUST NOT extend it. Domain-level typing extensibility is expressed through Archetypes.
 *
 * <p>Maps to PostgreSQL enum {@code definition_subject_type}. Values stored as uppercase enum names
 * via {@link org.hibernate.annotations.JdbcTypeCode} / {@link org.hibernate.type.SqlTypes#NAMED_ENUM}.
 *
 * <p>The lowercase {@link #value} is provided for API-layer compatibility (e.g., query parameters,
 * OpenAPI type discriminators).
 */
public enum DefinitionSubjectType {
    ARCHETYPE("archetype"),
    STRUCTURE("structure"),
    MECHANISM("mechanism"),
    INTERFACE("interface"),
    EFFECTOR("effector"),
    RECEPTOR("receptor"),
    INTERACTION("interaction"),
    DIRECTIVE("directive"),
    NORM("norm");

    private final String value;

    DefinitionSubjectType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static DefinitionSubjectType fromValue(String v) {
        for (DefinitionSubjectType t : values()) {
            if (t.value.equals(v)) return t;
        }
        throw new IllegalArgumentException("Unknown definition_subject_type: " + v);
    }
}
