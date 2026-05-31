package cloud.poesis.sie.defman.observability.perf;

import cloud.poesis.sie.defman.testbeans.AopProbeService;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Preliminary deterministic micro-benchmark for ADR-001 D-4 / S-004b AC-10.
 *
 * <p><strong>Scope:</strong> compares the same {@link AopProbeService#greet()} method invoked
 * directly on a {@code new} instance (no Spring proxy, no AOP) vs. invoked through the
 * Spring-managed proxied bean (broad AOP aspect active). Reports p50 / p95 latency for trend
 * tracking only \u2014 no assertion.
 *
 * <p><strong>This is NOT the authoritative perf-budget verification.</strong> The end-to-end p95
 * budget verification is owned by S-015 (k6 perf harness with the OTel Java agent and OTLP
 * exporter under representative load). This micro-bench exercises only the AOP advice cost on a
 * synthetic JVM-resident method and is intentionally narrow.
 *
 * <p>JMH is intentionally NOT used: it is not on the project classpath and adding it for one
 * preliminary bench is unjustified. The micro-bench uses {@code System.nanoTime()} with warmup
 * and a fixed sample count.
 *
 * <p><strong>Assertion-free by design.</strong> ADR-001 D-4 (5% p95 overhead budget) is a
 * SYSTEM-LEVEL budget measured at request scale (HTTP / JDBC dominate the per-request work). At
 * nanosecond-scale microbench against a trivial probe method, AOP overhead is dominated by
 * tracer/span/MDC bookkeeping and may exceed the system-level budget by orders of magnitude in
 * relative terms. Authoritative verification of ADR-001 D-4 is deferred to S-015 (perf harness
 * with realistic workload). This bench is preserved for trend tracking only — assertion-free.
 */
@SpringBootTest
@ActiveProfiles("tc")
@Testcontainers
@Import(BroadInstrumentationAspectPerfBenchIT.PerfBenchConfig.class)
class BroadInstrumentationAspectPerfBenchIT {

  private static final Logger log = LoggerFactory.getLogger(BroadInstrumentationAspectPerfBenchIT.class);

  @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16.3-alpine");

  @DynamicPropertySource
  static void dynamicProps(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", pg::getJdbcUrl);
    registry.add("spring.datasource.username", pg::getUsername);
    registry.add("spring.datasource.password", pg::getPassword);
    // Silence the aspect logger so logback I/O does not dominate the bench.
    registry.add("observability.aop.logLevel", () -> "OFF");
  }

  private static final int WARMUP = 5_000;
  private static final int SAMPLES = 50_000;

  /** Trend-only warn threshold; not an assertion. ADR-001 D-4 verification is S-015. */
  private static final double WARN_RATIO = 0.5;

  @Autowired AopProbeService proxied;

  @Test
  @DisplayName("AOP overhead microbench (trend-only, assertion-free; authoritative: S-015)")
  void p95OverheadTrendBench() {
    AopProbeService bare = new AopProbeService();

    // Warmup both paths so JIT compiles both call sites.
    for (int i = 0; i < WARMUP; i++) {
      consume(bare.greet());
      consume(proxied.greet());
    }

    long[] baseNs = new long[SAMPLES];
    long[] aopNs = new long[SAMPLES];

    // Alternate baseline / instrumented samples to share JIT / GC weather.
    for (int i = 0; i < SAMPLES; i++) {
      long t0 = System.nanoTime();
      consume(bare.greet());
      baseNs[i] = System.nanoTime() - t0;

      long t1 = System.nanoTime();
      consume(proxied.greet());
      aopNs[i] = System.nanoTime() - t1;
    }

    long basP50 = percentile(baseNs, 50);
    long basP95 = percentile(baseNs, 95);
    long aopP50 = percentile(aopNs, 50);
    long aopP95 = percentile(aopNs, 95);

    double ratio = ((double) aopP95 / (double) Math.max(basP95, 1L)) - 1.0;

    String msg =
        String.format(
            "perf bench (trend-only, S-015 authoritative): baseline p50=%d ns p95=%d ns ; "
                + "aop p50=%d ns p95=%d ns ; p95 ratio=%.3f",
            basP50, basP95, aopP50, aopP95, ratio);

    if (ratio > WARN_RATIO) {
      log.warn("{} [ratio>{}]", msg, WARN_RATIO);
    } else {
      log.info("{}", msg);
    }
  }

  // Black-hole-ish sink to prevent JIT from eliding the call.
  private static volatile Object sink;

  private static void consume(Object v) {
    sink = v;
  }

  private static long percentile(long[] samples, int p) {
    long[] copy = samples.clone();
    Arrays.sort(copy);
    int idx = (int) Math.ceil((p / 100.0) * copy.length) - 1;
    if (idx < 0) {
      idx = 0;
    }
    if (idx >= copy.length) {
      idx = copy.length - 1;
    }
    return copy[idx];
  }

  @TestConfiguration
  @ComponentScan(basePackages = "cloud.poesis.sie.defman.testbeans")
  static class PerfBenchConfig {
    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();

    @Bean
    @Primary
    OpenTelemetry perfBenchOpenTelemetry() {
      return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }
  }
}
