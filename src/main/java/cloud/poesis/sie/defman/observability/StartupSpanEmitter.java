package cloud.poesis.sie.defman.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Emits a single deliberate {@code startup} marker span on {@link ApplicationReadyEvent}, providing
 * a guaranteed first span before any inbound traffic. Satisfies S-001 AC-3.
 *
 * <p>The span name is unqualified ({@code "startup"}) by design: the SIE/Poesis identity is carried
 * by the OTel resource attributes {@code service.namespace=poesis} and {@code
 * service.name=sie-definition-manager} which the SDK attaches to every span via the resource
 * pipeline ({@code OTEL_RESOURCE_ATTRIBUTES}, set by Helm in S-001 cycle 2). Re-encoding {@code
 * sie.} into the span name would conflate the {@code service.*} dimensions with the operation name
 * and is intentionally avoided.
 *
 * <p>Resource attributes are intentionally NOT re-added on the span here, avoiding drift between
 * the env contract and Java code.
 *
 * <p>See ADR-001: products/sie-definition/architecture/adr-001-otel-collector-and-conventions.md
 */
@Component
public class StartupSpanEmitter {

  static final String INSTRUMENTATION_SCOPE = "cloud.poesis.sie.defman.observability";
  static final String SPAN_NAME = "startup";

  private final Tracer tracer;

  public StartupSpanEmitter(OpenTelemetry openTelemetry) {
    this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
  }

  /** Emits the {@code startup} marker span. */
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    tracer.spanBuilder(SPAN_NAME).startSpan().end();
  }
}
