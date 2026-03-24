package cloud.poesis.sie.defman.exception;

import cloud.poesis.sie.defman.type.PrimitiveType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Exception thrown when a GSM resource lookup by identifier (for example a Definition, Ascription,
 * or other GSM entity) returns no result.
 *
 * <p>This is the domain-specific runtime exception used by the Definition Plane when a referenced
 * GSM resource cannot be found; API layers are expected to translate it into an HTTP {@code 404 Not
 * Found} response (typically as a problem detail using {@link #getType()}, {@link #getTitle()} and
 * {@link #getExtensions()}).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public class ResourceNotFoundException extends RuntimeException {

  private final PrimitiveType resourceType;
  private final UUID resourceId;

  /**
   * Creates a new resource-not-found exception for the given resource type and identifier.
   *
   * @param resourceType canonical primitive type of the missing resource
   * @param resourceId unique identifier of the missing resource
   */
  public ResourceNotFoundException(PrimitiveType resourceType, UUID resourceId) {
    super(resourceType.getLabel() + " " + resourceId + " not found");
    this.resourceType = resourceType;
    this.resourceId = resourceId;
  }

  /**
   * Returns the GSM resource type that was looked up but not found.
   *
   * <p>Typical values are canonical primitive types such as {@code Definition} or {@code
   * Ascription}.
   *
   * @return the missing primitive type, or {@code null} if it was not provided
   */
  public PrimitiveType getResourceType() {
    return resourceType;
  }

  /**
   * Returns the identifier of the GSM resource that could not be found.
   *
   * @return the UUID of the missing resource, or {@code null} if it was not provided
   */
  public UUID getResourceId() {
    return resourceId;
  }

  /**
   * Returns the Problem Details {@code type} value used when this exception is mapped to an error
   * response.
   *
   * <p>The value is a stable, opaque identifier for the {@code resource-not-found} problem in the
   * GSM domain.
   *
   * @return a non-null string identifying the problem type
   */
  public String getType() {
    return "gsm:exceptions/resource-not-found";
  }

  /**
   * Returns the Problem Details {@code title} value describing this error.
   *
   * @return a short, human-readable summary of the problem
   */
  public String getTitle() {
    return "Not found";
  }

  /**
   * Returns additional Problem Details extension fields derived from this exception.
   *
   * <p>When available, the map includes:
   *
   * <ul>
   *   <li>{@code "resourceType"} – the human-readable label of the missing resource
   *   <li>{@code "resourceId"} – the UUID of the missing resource
   * </ul>
   *
   * The returned map is immutable.
   *
   * @return an immutable map of extension fields; never {@code null}
   */
  public Map<String, Object> getExtensions() {
    Map<String, Object> map = new LinkedHashMap<>();
    if (resourceType != null) {
      map.put("resourceType", resourceType.getLabel());
    }
    if (resourceId != null) {
      map.put("resourceId", resourceId);
    }
    return Map.copyOf(map);
  }
}
