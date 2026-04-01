package cloud.poesis.sie.defman.type;

import java.util.Set;

/**
 * GSM structural role of a Definition — what kind of primitive the subject is. Sealed enum: tenants
 * MUST NOT extend it. Domain-level typing extensibility is expressed through Archetypes.
 *
 * <p>Maps to PostgreSQL enum {@code definition_subject_type}. Values stored as uppercase enum names
 * via {@link org.hibernate.annotations.JdbcTypeCode} / {@link
 * org.hibernate.type.SqlTypes#NAMED_ENUM}.
 *
 * <p>The lowercase {@link #value} is provided for API-layer compatibility (e.g., query parameters,
 * OpenAPI type discriminators).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public enum DefinitionSubjectType {
  ARCHETYPE(PrimitiveType.ARCHETYPE, Set.of()),
  STRUCTURE(PrimitiveType.STRUCTURE, Set.of("purpose")),
  MECHANISM(PrimitiveType.MECHANISM, Set.of("structure", "function", "rule")),
  EFFECTOR(PrimitiveType.EFFECTOR, Set.of("mechanism", "archetype")),
  RECEPTOR(PrimitiveType.RECEPTOR, Set.of("mechanism", "archetype")),
  INTERACTION(PrimitiveType.INTERACTION, Set.of("effector", "receptor")),
  DIRECTIVE(PrimitiveType.DIRECTIVE, Set.of("structure", "modal", "verb", "qualifier", "purpose")),
  NORM(
      PrimitiveType.NORM,
      Set.of(
          "structure",
          "qualifier",
          "applicability",
          "assertion",
          "toleranceMode",
          "temporalWindow",
          "temporalAggregation",
          "sustainedThreshold"));

  private final PrimitiveType primitiveType;
  private final Set<String> statementProperties;

  DefinitionSubjectType(PrimitiveType primitiveType, Set<String> statementProperties) {
    this.primitiveType = primitiveType;
    this.statementProperties = statementProperties;
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
   * Returns the GSM base archetype statement property names for this subject type.
   *
   * @return unmodifiable set of property names; empty for non-ascription types
   */
  public Set<String> getStatementProperties() {
    return statementProperties;
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
