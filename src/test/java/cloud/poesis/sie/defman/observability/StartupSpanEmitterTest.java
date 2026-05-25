package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for {@link StartupSpanEmitter}: builds an in-process SDK wired to {@link
 * InMemorySpanExporter}, invokes the listener directly, asserts exactly one {@code startup}
 * span was exported. Resource attributes are NOT asserted here (they come from env in production;
 * covered by {@link StartupSpanEmitterSpringIT}).
 */
class StartupSpanEmitterTest {

  @Test
  void emitsSingleStartupSpanOnApplicationReady() {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();

    StartupSpanEmitter emitter = new StartupSpanEmitter(sdk);
    emitter.onApplicationReady();

    assertThat(exporter.getFinishedSpanItems())
        .hasSize(1)
        .first()
        .satisfies(s -> assertThat(s.getName()).isEqualTo(StartupSpanEmitter.SPAN_NAME));

    tracerProvider.close();
  }
}
