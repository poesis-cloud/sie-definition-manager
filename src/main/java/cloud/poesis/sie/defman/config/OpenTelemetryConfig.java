package cloud.poesis.sie.defman.config;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the application's {@link OpenTelemetry} instance.
 *
 * <p>In production the OpenTelemetry Java agent (baked into the container image, S-001 cycle 1)
 * installs and registers a fully-configured {@link io.opentelemetry.sdk.OpenTelemetrySdk} as the
 * global instance via env-driven autoconfiguration ({@code OTEL_*} vars wired by Helm in cycle 2).
 * This bean simply exposes that already-installed global as a Spring bean for constructor
 * injection. No {@code OpenTelemetrySdk.builder()} is constructed in {@code src/main} (avoids
 * double-export and keeps SDK construction out of the application bytecode per ADR-001 D-1).
 *
 * <p>Marked {@link ConditionalOnMissingBean} so tests can override with their own {@code
 * OpenTelemetry} bean (typically an {@code OpenTelemetrySdk} wired to an {@code
 * InMemorySpanExporter}).
 *
 * <p>See ADR-001: products/sie-definition/architecture/adr-001-otel-collector-and-conventions.md
 */
@Configuration
public class OpenTelemetryConfig {

  /** Returns the agent-installed global {@link OpenTelemetry} (no-op when the agent is absent). */
  @Bean
  @ConditionalOnMissingBean(OpenTelemetry.class)
  public OpenTelemetry openTelemetry() {
    return GlobalOpenTelemetry.get();
  }
}
