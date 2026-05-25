package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Spring integration test for {@link StartupSpanEmitter}: validates that the full Spring context
 * reaches {@link org.springframework.boot.context.event.ApplicationReadyEvent} and exactly one
 * {@code sie.startup} span is emitted via the SDK-overridden {@link OpenTelemetry} bean.
 *
 * <p><b>AC-4 graceful-degradation slice:</b> {@code OTEL_EXPORTER_OTLP_ENDPOINT} is set to an
 * unreachable address ({@code http://localhost:1}) via {@link DynamicPropertySource}. Because this
 * test injects an in-memory exporter (not the OTLP exporter), the unreachable endpoint cannot cause
 * export failure here; what is being proven is that the env-driven configuration surface does not
 * block boot. (Full Testcontainers fail-loud network slice deferred to S-003.)
 */
@SpringBootTest
@ActiveProfiles("tc")
@Testcontainers
class StartupSpanEmitterSpringIT {

  @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16.3-alpine");

  @DynamicPropertySource
  static void dynamicProps(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", pg::getJdbcUrl);
    registry.add("spring.datasource.username", pg::getUsername);
    registry.add("spring.datasource.password", pg::getPassword);
    // AC-4: unreachable OTLP endpoint; boot must still complete.
    registry.add("otel.exporter.otlp.endpoint", () -> "http://localhost:1");
    registry.add("OTEL_EXPORTER_OTLP_ENDPOINT", () -> "http://localhost:1");
  }

  @Autowired InMemorySpanExporter spanExporter;

  @Test
  void contextReachesReadyAndEmitsStartupSpan() {
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(spanExporter.getFinishedSpanItems())
                    .filteredOn(s -> StartupSpanEmitter.SPAN_NAME.equals(s.getName()))
                    .hasSize(1));
  }

  @TestConfiguration
  static class OtelTestConfig {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();

    @Bean
    InMemorySpanExporter spanExporter() {
      return exporter;
    }

    @Bean
    @Primary
    OpenTelemetry testOpenTelemetry() {
      return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }
  }
}
