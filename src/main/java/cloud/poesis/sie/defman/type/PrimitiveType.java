package cloud.poesis.sie.defman.type;

import java.util.Set;

/**
 * Canonical primitive/resource types used by GSM entities and diagnostics.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public enum PrimitiveType {
  DEFINITION("definition", "Definition", Set.of()),
  ASCRIPTION("ascription", "Ascription", Set.of()),
  ASCRIPTION_STATUS_TRANSITION(
      "ascription-status-transition", "AscriptionStatusTransition", Set.of()),
  ARCHETYPE("archetype", "Archetype", Set.of()),
  STRUCTURE("structure", "Structure", Set.of("purpose")),
  MECHANISM("mechanism", "Mechanism", Set.of("structure", "function", "rule")),
  EFFECTOR("effector", "Effector", Set.of("mechanism", "archetype")),
  RECEPTOR("receptor", "Receptor", Set.of("mechanism", "archetype")),
  INTERACTION("interaction", "Interaction", Set.of("effector", "receptor")),
  DIRECTIVE("directive", "Directive", Set.of("structure", "modal", "verb", "qualifier", "purpose")),
  NORM(
      "norm",
      "Norm",
      Set.of(
          "structure",
          "qualifier",
          "applicability",
          "assertion",
          "toleranceMode",
          "temporalWindow",
          "temporalAggregation",
          "sustainedThreshold"));

  private final String value;
  private final String label;
  private final Set<String> statementProperties;

  PrimitiveType(String value, String label, Set<String> statementProperties) {
    this.value = value;
    this.label = label;
    this.statementProperties = statementProperties;
  }

  /**
   * Returns the machine-readable lowercase value.
   *
   * @return the lowercase identifier; never {@code null}
   */
  public String getValue() {
    return value;
  }

  /**
   * Returns the human-readable label.
   *
   * @return the display label; never {@code null}
   */
  public String getLabel() {
    return label;
  }

  /**
   * Returns the GSM base archetype statement property names for this primitive type.
   *
   * @return unmodifiable set of property names; empty for non-ascription types
   */
  public Set<String> getStatementProperties() {
    return statementProperties;
  }

  /**
   * Resolves a primitive type from its lowercase value.
   *
   * @param value the lowercase string to resolve
   * @return the matching primitive type
   * @throws IllegalArgumentException if no constant matches {@code value}
   */
  public static PrimitiveType fromValue(String value) {
    for (PrimitiveType type : values()) {
      if (type.value.equalsIgnoreCase(value)) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unknown primitive_type: " + value);
  }
}
