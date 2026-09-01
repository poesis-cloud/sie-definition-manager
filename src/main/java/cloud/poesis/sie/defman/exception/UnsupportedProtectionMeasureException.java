package cloud.poesis.sie.defman.exception;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exception thrown when an Archetype declares a {@code $gsm:dataProtection} measure this processor
 * does not implement.
 *
 * <p>This is a <strong>processor capability</strong> limitation, not a GSM rule violation: the
 * Archetype is valid GSM, and a processor implementing every measure would accept it. It is
 * therefore not modeled as an {@code AscriptionConsistencyRuleType}, and API layers translate it
 * into an HTTP {@code 501 Not Implemented} response rather than a {@code 400}.
 *
 * <p>Failing here is deliberate: silently ignoring a declared measure would persist or return an
 * unprotected value while the schema advertises protection.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public class UnsupportedProtectionMeasureException extends RuntimeException {

  private final String propertyName;
  private final String measure;

  /**
   * Creates a new unsupported-measure exception.
   *
   * @param phase the protection phase ({@code atRest} or {@code inTransit})
   * @param measure the declared measure keyword (for example {@code encryption})
   * @param propertyName the property carrying the declaration
   */
  public UnsupportedProtectionMeasureException(String phase, String measure, String propertyName) {
    super(
        "$gsm:dataProtection."
            + phase
            + "."
            + measure
            + " on property '"
            + propertyName
            + "' is not implemented by this processor");
    this.propertyName = propertyName;
    this.measure = phase + "." + measure;
  }

  /**
   * Returns the property carrying the unsupported declaration.
   *
   * @return the property name
   */
  public String getPropertyName() {
    return propertyName;
  }

  /**
   * Returns the unsupported measure as {@code phase.measure}.
   *
   * @return the qualified measure keyword
   */
  public String getMeasure() {
    return measure;
  }

  /**
   * Returns the Problem Details {@code type} value used when this exception is mapped to an error
   * response.
   *
   * @return a non-null string identifying the problem type
   */
  public String getType() {
    return "gsm:exceptions/unsupported-protection-measure";
  }

  /**
   * Returns the Problem Details {@code title} value describing this error.
   *
   * @return a short, human-readable summary of the problem
   */
  public String getTitle() {
    return "Protection measure not implemented";
  }

  /**
   * Returns additional Problem Details extension fields derived from this exception.
   *
   * @return an immutable map with {@code property} and {@code measure}
   */
  public Map<String, Object> getExtensions() {
    Map<String, Object> extensions = new LinkedHashMap<>();
    extensions.put("property", propertyName);
    extensions.put("measure", measure);
    return Map.copyOf(extensions);
  }
}
