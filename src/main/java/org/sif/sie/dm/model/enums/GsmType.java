package org.sif.sie.dm.model.enums;

/**
 * Discriminator for {@code ascription_status_transition.gsm_type}.
 * Values match the PostgreSQL CHECK constraint exactly (lowercase table names).
 */
public enum GsmType {
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

    GsmType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static GsmType fromValue(String v) {
        for (GsmType t : values()) {
            if (t.value.equals(v))
                return t;
        }
        throw new IllegalArgumentException("Unknown gsm_type: " + v);
    }
}
