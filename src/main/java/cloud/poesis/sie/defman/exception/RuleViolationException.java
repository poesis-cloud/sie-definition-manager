package cloud.poesis.sie.defman.exception;

import cloud.poesis.sie.defman.type.RuleType;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic GSM rule-violation exception.
 *
 * <p>The {@link RuleType} carries all semantic weight (which rule was violated); this class is a
 * thin carrier of {@code ruleType} + contextual {@code site} data describing the violating input
 * instances.
 *
 * <p>Depending on the {@code RuleType}, the API layer maps instances of this exception to HTTP
 * {@code 400 Bad Request} or {@code 409 Conflict} responses, rendered as RFC 9457 {@code
 * ProblemDetail}.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public class RuleViolationException extends RuntimeException {

  private final RuleType ruleType;
  private final Map<String, Object> site;

  /**
   * Creates a rule-violation exception with contextual site data.
   *
   * @param ruleType the violated GSM rule
   * @param detail human-readable description of the violation
   * @param site contextual key-value pairs describing the violating input instances; may be {@code
   *     null} (treated as empty)
   */
  public RuleViolationException(RuleType ruleType, String detail, Map<String, Object> site) {
    super(detail);
    this.ruleType = ruleType;
    this.site = site != null ? Map.copyOf(site) : Map.of();
  }

  /**
   * Creates a rule-violation exception without contextual site data.
   *
   * @param ruleType the violated GSM rule
   * @param detail human-readable description of the violation
   */
  public RuleViolationException(RuleType ruleType, String detail) {
    this(ruleType, detail, Map.of());
  }

  /**
   * Creates a rule-violation exception with contextual site data and a cause.
   *
   * @param ruleType the violated GSM rule
   * @param detail human-readable description of the violation
   * @param site contextual key-value pairs describing the violating input instances; may be {@code
   *     null} (treated as empty)
   * @param cause the underlying throwable that triggered this violation
   */
  public RuleViolationException(
      RuleType ruleType, String detail, Map<String, Object> site, Throwable cause) {
    super(detail, cause);
    this.ruleType = ruleType;
    this.site = site != null ? Map.copyOf(site) : Map.of();
  }

  /**
   * Returns the violated GSM rule.
   *
   * @return the {@link RuleType} identifying the specific rule that was violated; never {@code
   *     null}
   */
  public RuleType getRuleType() {
    return ruleType;
  }

  /**
   * Returns the contextual data about the violating input instances.
   *
   * @return an immutable map of site key-value pairs; never {@code null}
   */
  public Map<String, Object> getSite() {
    return site;
  }

  /**
   * Returns the GSM rule type URI — a stable, machine-readable identifier mapped to the {@code
   * type} field of RFC 9457 {@code ProblemDetail}.
   *
   * @return the rule-specific type URI; never {@code null}
   */
  public String getType() {
    return ruleType.getType();
  }

  /**
   * Returns a short, human-readable summary of the rule category, mapped to the {@code title} field
   * of RFC 9457 {@code ProblemDetail}.
   *
   * @return the rule-specific title; never {@code null}
   */
  public String getTitle() {
    return ruleType.getTitle();
  }

  /**
   * Returns structured extension properties for the RFC 9457 {@code ProblemDetail} response.
   *
   * <p>The returned map merges the {@linkplain #getSite() site} entries with the {@code rule} name
   * and {@code ruleDescription}.
   *
   * @return an immutable map of extension attributes; never {@code null}
   */
  public Map<String, Object> getExtensions() {
    Map<String, Object> ext = new LinkedHashMap<>(site);
    ext.put("rule", ruleType.name());
    ext.put("ruleDescription", ruleType.getDescription());
    return Map.copyOf(ext);
  }

  /**
   * Convenience factory that builds the site map from alternating key-value pairs, omitting entries
   * whose value is {@code null}.
   *
   * @param ruleType the violated GSM rule
   * @param detail human-readable description of the violation
   * @param siteKeyValuePairs alternating {@code String} keys and {@code Object} values; must have
   *     an even length
   * @return a new {@code RuleViolationException}
   * @throws IllegalArgumentException if the varargs array has odd length
   */
  public static RuleViolationException of(
      RuleType ruleType, String detail, Object... siteKeyValuePairs) {
    return new RuleViolationException(ruleType, detail, toSite(siteKeyValuePairs));
  }

  /**
   * Convenience factory with cause that builds the site map from alternating key-value pairs,
   * omitting entries whose value is {@code null}.
   *
   * @param ruleType the violated GSM rule
   * @param detail human-readable description of the violation
   * @param cause the underlying throwable
   * @param siteKeyValuePairs alternating {@code String} keys and {@code Object} values; must have
   *     an even length
   * @return a new {@code RuleViolationException}
   * @throws IllegalArgumentException if the varargs array has odd length
   */
  public static RuleViolationException of(
      RuleType ruleType, String detail, Throwable cause, Object... siteKeyValuePairs) {
    return new RuleViolationException(ruleType, detail, toSite(siteKeyValuePairs), cause);
  }

  /**
   * Converts alternating key-value pairs into an immutable map, omitting entries whose value is
   * {@code null}.
   *
   * @param keyValuePairs alternating {@code String} keys and {@code Object} values
   * @return an immutable map built from the non-null entries
   * @throws IllegalArgumentException if the array length is odd
   */
  private static Map<String, Object> toSite(Object... keyValuePairs) {
    if (keyValuePairs.length % 2 != 0) {
      throw new IllegalArgumentException(
          "Site key-value pairs must be even; got " + keyValuePairs.length);
    }
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValuePairs.length; i += 2) {
      Object value = keyValuePairs[i + 1];
      if (value != null) {
        map.put((String) keyValuePairs[i], value);
      }
    }
    return Map.copyOf(map);
  }
}
