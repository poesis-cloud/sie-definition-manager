package cloud.poesis.sie.defman.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Emits a single deliberate {@code sie.startup} span on {@link ApplicationReadyEvent}, providing a
 * guaranteed first span before any inbound traffic. Satisfies S-001 AC-3.
 *
 * <p>Resource attributes ({@code service.name}, {@code service.namespace}, {@code service.version})
 * are sourced from {@code OTEL_RESOURCE_ATTRIBUTES} (set by Helm in S-001 cycle 2) and are
 * automatically attached to every span by the SDK's resource pipeline. They are intentionally NOT
 * re-added on the span here, avoiding drift between the env contract and Java code.
 *
 * <p>See ADR-001: products/sie-definition/architecture/adr-001-otel-collector-and-conventions.md
 */
@Component
public class StartupSpanEmitter {

  static final String INSTRUMENTATION_SCOPE = "cloud.poesis.sie.defman.observability";
  static final String SPAN_NAME = "sie.startup";

  private final Tracer tracer;

  public StartupSpanEmitter(OpenTelemetry openTelemetry) {
    this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
  }

  /** Emits the {@code sie.startup} marker span. */
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    tracer.spanBuilder(SPAN_NAME).startSpan().end();
  }
}
