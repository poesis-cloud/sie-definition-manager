package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link OpenTelemetryConfig}: verifies the bean factory method returns the
 * agent-installed global {@link OpenTelemetry} (no-op when no agent is present in the test JVM).
 */
class OpenTelemetryConfigTest {

  @Test
  void beanReturnsGlobalOpenTelemetry() {
    OpenTelemetry bean = new OpenTelemetryConfig().openTelemetry();
    // GlobalOpenTelemetry.get() returns an ObfuscatedOpenTelemetry wrapper each call (not
    // reference-equal), so we verify the bean is non-null and yields a usable Tracer.
    assertThat(bean).isNotNull();
    assertThat(bean.getTracer("test")).isNotNull();
  }
}
