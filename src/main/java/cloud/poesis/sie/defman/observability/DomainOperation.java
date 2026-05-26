package cloud.poesis.sie.defman.observability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for service-layer methods that represent GSM-meaningful domain operations.
 *
 * <p>When a method is annotated with {@code @DomainOperation}, the {@link DomainOperationAspect}
 * intercepts it and creates an OpenTelemetry INTERNAL span named by the annotation value.
 *
 * <p><strong>Usage:</strong> Annotate service-layer methods that represent business-meaningful
 * operations (e.g., {@code gsm.definition.create}, {@code gsm.ascription.transition}). Do NOT
 * annotate repository, entity, or helper methods.
 *
 * <p><strong>Story:</strong> S-004
 *
 * <p><strong>Example:</strong>
 *
 * <pre>
 * {@literal @}DomainOperation("gsm.definition.create")
 * public Definition create(UUID definitionId, JsonNode payload, UUID userId) {
 *     // implementation
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DomainOperation {
  /**
   * The span name for the domain operation (e.g., "gsm.definition.create").
   *
   * @return the span name
   */
  String value();
}
