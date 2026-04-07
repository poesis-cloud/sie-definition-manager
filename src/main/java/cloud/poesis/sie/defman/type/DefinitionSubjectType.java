package cloud.poesis.sie.defman.type;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * GSM structural role of a Definition — what kind of primitive the subject is.
 * Sealed enum: tenants
 * MUST NOT extend it. Domain-level typing extensibility is expressed through
 * Archetypes.
 *
 * <p>
 * Maps to PostgreSQL enum {@code definition_subject_type}. Values stored as
 * uppercase enum names
 * via {@link org.hibernate.annotations.JdbcTypeCode} / {@link
 * org.hibernate.type.SqlTypes#NAMED_ENUM}.
 *
 * <p>
 * The lowercase {@link #value} is provided for API-layer compatibility (e.g.,
 * query parameters,
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

  private static final Set<String> ARCHETYPE_TITLES = Collections.unmodifiableSet(
      Stream.of(values())
          .map(DefinitionSubjectType::getArchetypeTitle)
          .collect(Collectors.toSet()));

  private static final Map<String, DefinitionSubjectType> TITLE_TO_TYPE;

  static {
    Map<String, DefinitionSubjectType> map = new HashMap<>();
    for (DefinitionSubjectType type : values()) {
      map.put(type.getArchetypeTitle(), type);
    }
    TITLE_TO_TYPE = Collections.unmodifiableMap(map);
  }

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
   * Returns the GSM base archetype statement property names for this subject
   * type.
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
   * Returns the GSM base archetype title for this subject type. Convention: the
   * PascalCase label of
   * the backing primitive type (e.g., {@code "Structure"}, {@code "Mechanism"},
   * {@code
   * "Archetype"}).
   *
   * @return the base archetype title; never {@code null}
   */
  public String getArchetypeTitle() {
    return primitiveType.getLabel();
  }

  /**
   * Returns the immutable set of all 8 GSM base archetype titles.
   *
   * @return unmodifiable set of archetype titles
   */
  public static Set<String> archetypeTitles() {
    return ARCHETYPE_TITLES;
  }

  /**
   * Resolves a {@code DefinitionSubjectType} from a GSM base archetype title.
   *
   * @param title the archetype title (e.g., {@code "Structure"})
   * @return the matching enum constant, or {@code null} if the title is not a GSM
   *         base archetype
   */
  public static DefinitionSubjectType fromArchetypeTitle(String title) {
    return TITLE_TO_TYPE.get(title);
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
