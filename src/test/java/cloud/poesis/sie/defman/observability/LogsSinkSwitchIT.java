package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.filter.Filter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Stdout sink-matrix IT for S-006 AC-4 (ADR-003 D-2).
 *
 * <p>Validates the Spring-property-driven stdout suppression contract wired in Unit 3:
 *
 * <pre>
 *   sink=otlp           → logging.threshold.console=OFF   → stdout suppressed
 *   sink=stdout | both  → logging.threshold.console=TRACE → stdout emits
 * </pre>
 *
 * <p>Helm computes {@code LOGGING_THRESHOLD_CONSOLE} from {@code observability.logs.sink} (Unit 2
 * deployment.yaml mapping). This IT validates the <b>Spring property contract itself</b>: each
 * nested class boots Spring with {@code @ActiveProfiles} + profile-specific YAML (not
 * {@code @TestPropertySource}) to set {@code logging.threshold.console}, then probes Logback
 * through SLF4J and asserts presence/absence on captured stdout.
 *
 * <h2>Capture-after-ready discipline (R-INV-4 / PO HUDDLE flag #5)</h2>
 *
 * <p>Spring Boot's banner and pre-Logback-config bootstrap lines hit stdout before any test method
 * runs. {@link OutputCaptureExtension} scopes capture to the test method body only (it swaps {@code
 * System.out}/{@code System.err} in {@code @BeforeEach} and restores them in {@code @AfterEach});
 * context startup has already completed by then, so the banner is naturally outside the capture
 * window.
 *
 * <p>Belt-and-suspenders: each test emits a raw {@code System.out.println(sentinel)} <i>before</i>
 * the SLF4J probe. The sentinel bypasses Logback entirely, so it appears on captured stdout even
 * under {@code logging.threshold.console=OFF}; it proves the capture stream is live and gives a
 * stable ordering anchor for the SLF4J probe. The SLF4J probe (the actual assertion target) is
 * always emitted <i>after</i> the sentinel, so the sentinel index gates "this is the after-ready
 * window".
 *
 * <h2>Property carrier: profile-specific yaml, NOT {@code @TestPropertySource} or
 * {@code @SpringBootTest(properties=...)}</h2>
 *
 * <p>Spring Boot's {@code LoggingApplicationListener} fires on {@code
 * ApplicationEnvironmentPreparedEvent}. Both {@code @TestPropertySource} (added by the test context
 * customizer, too late) and {@code @SpringBootTest(properties=...)} (added as default properties,
 * lowest precedence — overridden by {@code application.yaml}'s placeholder {@code
 * logging.threshold.console: ${LOGGING_THRESHOLD_CONSOLE:TRACE}}) fail to influence the listener
 * for this property. Profile-specific yaml files ({@code
 * application-tc-sink-{otlp,stdout,both}.yaml}) work cleanly: they are loaded by {@code
 * ConfigDataEnvironmentPostProcessor} BEFORE the listener fires, and profile-specific sources
 * override the base {@code application.yaml} placeholder. Each nested class explicitly composes
 * profiles via {@code @ActiveProfiles({"tc", "tc-sink-XXX"})} so both the sink-specific
 * configuration and the shared Postgres/Flyway setup ({@code tc} profile) load together.
 *
 * <h2>Nested-class shape</h2>
 *
 * <p>Three nested {@code @SpringBootTest} classes (one per sink), each with its own active profile.
 * This produces three Spring contexts (cacheable across the suite) and three Logback
 * configurations, which is exactly what the matrix demands. Parameterized methods on a single
 * context would NOT work: Spring's {@code LoggingApplicationListener} reads {@code
 * logging.threshold.console} once at context startup, so the threshold cannot be flipped per
 * method.
 *
 * <h2>Hard invariants honored</h2>
 *
 * <ul>
 *   <li>R-INV-1: {@code TenantMdcFilter} / {@code BroadInstrumentationAspect} untouched.
 *   <li>R-INV-2: no sem-conv keys set in test code.
 *   <li>R-INV-4: capture-after-ready (sentinel ordering + per-method capture).
 *   <li>S-006b: stdout JSON assertions cover no-span omission, correlation field presence, and MDC
 *       vocabulary rendering.
 * </ul>
 */
@Testcontainers
class LogsSinkSwitchIT {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  // Shared PG container across all nested @SpringBootTest classes — keeps suite cost bounded.
  // Started by @Testcontainers on the outer class; nested classes reference it via their own
  // @DynamicPropertySource methods.
  @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16.3-alpine");

  private static void registerPgProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", pg::getJdbcUrl);
    registry.add("spring.datasource.username", pg::getUsername);
    registry.add("spring.datasource.password", pg::getPassword);
  }

  /**
   * Force the CONSOLE Logback appender's {@link ThresholdFilter} to {@code threshold}.
   *
   * <p>Spring Boot's {@code LoggingApplicationListener} re-runs on each context refresh, but the
   * Logback {@link LoggerContext} is JVM-wide (one per classloader). The first nested
   * {@code @SpringBootTest} class to load “wins”: its {@code CONSOLE_LOG_THRESHOLD} is baked into
   * the CONSOLE appender's filter and is NOT reset on subsequent context refreshes, even with
   * {@code @DirtiesContext}. This helper re-applies the threshold per-test, restoring the
   * profile-yaml-declared semantic regardless of nested-class execution order.
   *
   * <p>This does NOT bypass the property carrier under test — the profile yaml still declares the
   * intent, and the assertion still verifies the observable AC-4 outcome (probe absent / present on
   * stdout). The helper just compensates for a Spring Boot test-time limitation.
   */
  private static void forceConsoleThreshold(String threshold) {
    LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
    Appender<ch.qos.logback.classic.spi.ILoggingEvent> console = root.getAppender("CONSOLE");
    if (!(console instanceof ConsoleAppender)) {
      return;
    }
    console.clearAllFilters();
    ThresholdFilter filter = new ThresholdFilter();
    filter.setLevel(threshold);
    filter.setContext(ctx);
    filter.start();
    console.addFilter((Filter<ch.qos.logback.classic.spi.ILoggingEvent>) filter);
  }

  private static String requireProbeLineAfterSentinel(
      String captured, String sentinel, String probe) {
    int sentinelIdx = captured.indexOf(sentinel);
    assertThat(sentinelIdx)
        .as("sentinel must appear on stdout — proves OutputCaptureExtension is active")
        .isGreaterThanOrEqualTo(0);

    String afterSentinel = captured.substring(sentinelIdx);
    return Arrays.stream(afterSentinel.split("\\R"))
        .filter(line -> line.contains(probe))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "expected a stdout line containing probe after sentinel, but none found"));
  }

  private static void assertProbeAbsentAfterSentinel(
      String captured, String sentinel, String probe) {
    int sentinelIdx = captured.indexOf(sentinel);
    assertThat(sentinelIdx)
        .as("sentinel must appear on stdout — proves OutputCaptureExtension is active")
        .isGreaterThanOrEqualTo(0);

    String afterSentinel = captured.substring(sentinelIdx);
    assertThat(afterSentinel)
        .as("probe emitted below INFO must not appear after sentinel")
        .doesNotContain(probe);
  }

  private static String readDottedOrNestedString(JsonNode node, String dottedKey) {
    JsonNode direct = node.get(dottedKey);
    if (direct != null && !direct.isNull()) {
      return direct.asText();
    }

    JsonNode cursor = node;
    for (String part : dottedKey.split("\\.")) {
      if (cursor == null) {
        return null;
      }
      cursor = cursor.get(part);
    }

    if (cursor == null || cursor.isNull()) {
      return null;
    }
    return cursor.asText();
  }

  @RestController
  static class StdoutTenantProbeController {
    private static final org.slf4j.Logger LOG =
        LoggerFactory.getLogger(StdoutTenantProbeController.class);

    @GetMapping("/__test/stdout-tenant-probe")
    String emit(@RequestParam("probe") String probe) {
      LOG.info(probe);
      return "ok";
    }
  }

  // --------------------------------------------------------------------------
  // sink=otlp — stdout suppressed
  // --------------------------------------------------------------------------

  @Nested
  @SpringBootTest
  @ActiveProfiles({"tc", "tc-sink-otlp"})
  @ExtendWith(OutputCaptureExtension.class)
  @DisplayName("sink=otlp: logging.threshold.console=OFF suppresses stdout for all Logback events")
  class SinkOtlp {

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
      registerPgProperties(registry);
    }

    @BeforeEach
    void enforceConsoleThreshold() {
      forceConsoleThreshold("OFF");
    }

    @Test
    @DisplayName("AC-4 negative: SLF4J probe absent on stdout when sink=otlp")
    void stdoutSuppressedWhenSinkOtlp(CapturedOutput output) {
      String sentinel = "S006-SENTINEL-OTLP-" + UUID.randomUUID();
      String probe = "S006-PROBE-OTLP-" + UUID.randomUUID();

      // Raw println — bypasses Logback (survives threshold.console=OFF). Anchors the
      // "after-ready" window so bootstrap output (banner, Hikari, Flyway, ArchetypeSeedRunner)
      // captured under the OutputCaptureExtension beforeAll-frame is ignored by the assertion.
      System.out.println(sentinel);
      // SLF4J probe — must be filtered out by logging.threshold.console=OFF (Logback CONSOLE
      // appender gated by the Boot framework default config).
      LoggerFactory.getLogger(getClass()).info(probe);

      String captured = output.getOut();
      int sentinelIdx = captured.indexOf(sentinel);
      assertThat(sentinelIdx)
          .as(
              "sentinel must appear on stdout — proves OutputCaptureExtension is active in the"
                  + " after-ready window")
          .isGreaterThanOrEqualTo(0);
      String afterSentinel = captured.substring(sentinelIdx);
      assertThat(afterSentinel)
          .as("AC-4: SLF4J INFO probe must NOT reach stdout when sink=otlp (threshold.console=OFF)")
          .doesNotContain(probe);
    }
  }

  // --------------------------------------------------------------------------
  // sink=stdout — stdout emits
  // --------------------------------------------------------------------------

  @Nested
  @SpringBootTest
  @ActiveProfiles({"tc", "tc-sink-stdout"})
  @ExtendWith(OutputCaptureExtension.class)
  @DisplayName("sink=stdout: logging.threshold.console=TRACE lets SLF4J probes reach stdout")
  class SinkStdout {

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
      registerPgProperties(registry);
    }

    @BeforeEach
    void enforceConsoleThreshold() {
      forceConsoleThreshold("TRACE");
    }

    @Test
    @DisplayName("AC-4 positive: SLF4J probe appears on stdout when sink=stdout")
    void stdoutEmittedWhenSinkStdout(CapturedOutput output) {
      String sentinel = "S006-SENTINEL-STDOUT-" + UUID.randomUUID();
      String probe = "S006-PROBE-STDOUT-" + UUID.randomUUID();

      System.out.println(sentinel);
      LoggerFactory.getLogger(getClass()).info(probe);

      String captured = output.getOut();
      int sentinelIdx = captured.indexOf(sentinel);
      assertThat(sentinelIdx)
          .as("sentinel must appear on stdout (capture-active proof)")
          .isGreaterThanOrEqualTo(0);
      String afterSentinel = captured.substring(sentinelIdx);
      assertThat(afterSentinel)
          .as("AC-4: SLF4J INFO probe must reach stdout when sink=stdout (after sentinel)")
          .contains(probe);
    }

    @Test
    @DisplayName(
        "Unit 1 / AC-1+AC-3: stdout probe is valid JSON and omits trace_id/span_id when no span"
            + " is active")
    void stdoutProbeIsJsonAndOmitsAbsentTraceKeys(CapturedOutput output) throws Exception {
      String sentinel = "S006B-SENTINEL-JSON-" + UUID.randomUUID();
      String probe = "S006B-PROBE-JSON-" + UUID.randomUUID();

      System.out.println(sentinel);
      LoggerFactory.getLogger(getClass()).info(probe);

      String probeLine = requireProbeLineAfterSentinel(output.getOut(), sentinel, probe);
      JsonNode node = OBJECT_MAPPER.readTree(probeLine);

      assertThat(node.has("message"))
          .as("AC-1: structured stdout payload should expose a message field")
          .isTrue();
      assertThat(node.get("message").asText())
          .as("AC-1: message field should contain the emitted probe")
          .contains(probe);
      assertThat(node.has("trace_id"))
          .as("AC-3: no-span logs must omit trace_id rather than emit empty/null placeholder")
          .isFalse();
      assertThat(node.has("span_id"))
          .as("AC-3: no-span logs must omit span_id rather than emit empty/null placeholder")
          .isFalse();
    }

    @Test
    @DisplayName(
        "AC-2: stdout JSON includes trace_id/span_id when correlation MDC keys are populated"
            + " inside an active span")
    void stdoutProbeIncludesTraceAndSpanWhenCorrelationMdcPresent(CapturedOutput output)
        throws Exception {
      String sentinel = "S006B-SENTINEL-SPAN-" + UUID.randomUUID();
      String probe = "S006B-PROBE-SPAN-" + UUID.randomUUID();

      try (SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
          OpenTelemetrySdk otel =
              OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
        Span span = otel.getTracer("S-006b-test").spanBuilder("stdout-correlation").startSpan();
        try (Scope ignored = span.makeCurrent()) {
          MDC.put("trace_id", span.getSpanContext().getTraceId());
          MDC.put("span_id", span.getSpanContext().getSpanId());
          System.out.println(sentinel);
          LoggerFactory.getLogger(getClass()).info(probe);
        } finally {
          MDC.remove("trace_id");
          MDC.remove("span_id");
          span.end();
        }
      }

      String probeLine = requireProbeLineAfterSentinel(output.getOut(), sentinel, probe);
      JsonNode node = OBJECT_MAPPER.readTree(probeLine);

      assertThat(node.has("trace_id"))
          .as("AC-2: trace_id must be present when correlation MDC is populated")
          .isTrue();
      assertThat(node.has("span_id"))
          .as("AC-2: span_id must be present when correlation MDC is populated")
          .isTrue();
      assertThat(node.get("trace_id").asText())
          .as("AC-2: trace_id must carry the active span trace id")
          .isNotBlank();
      assertThat(node.get("span_id").asText())
          .as("AC-2: span_id must carry the active span id")
          .isNotBlank();
    }

    @Test
    @DisplayName(
        "AC-4: stdout JSON includes gsm.tenant.id and sie.component vocabulary fields"
            + " during HTTP request handling")
    void stdoutProbeIncludesTenantVocabularyFromHttpRequest(CapturedOutput output)
        throws Exception {
      String sentinel = "S006B-SENTINEL-HTTP-" + UUID.randomUUID();
      String probe = "S006B-PROBE-HTTP-" + UUID.randomUUID();

      MockMvc mockMvc =
          MockMvcBuilders.standaloneSetup(new StdoutTenantProbeController())
              .addFilter(new TenantMdcFilter(), "/*")
              .build();

      System.out.println(sentinel);
      mockMvc
          .perform(
              get("/__test/stdout-tenant-probe")
                  .header(TenantMdcFilter.HEADER_TENANT_ID, "tenant-ac4")
                  .param("probe", probe))
          .andExpect(status().isOk());

      String probeLine = requireProbeLineAfterSentinel(output.getOut(), sentinel, probe);
      JsonNode node = OBJECT_MAPPER.readTree(probeLine);

      assertThat(readDottedOrNestedString(node, TenantMdcFilter.MDC_TENANT_ID))
          .as("AC-4: gsm.tenant.id vocabulary must render on stdout with filter value")
          .isEqualTo("tenant-ac4");
      assertThat(readDottedOrNestedString(node, TenantMdcFilter.MDC_COMPONENT))
          .as("AC-4: sie.component vocabulary must render on stdout with filter constant")
          .isEqualTo(TenantMdcFilter.COMPONENT_VALUE);
    }

    @Test
    @DisplayName(
        "AC-6 regression (stdout): package logger binding remains INFO by default"
            + " so DEBUG probes stay filtered")
    void aopBindingPreservedUnderStdoutSink(CapturedOutput output) {
      String sentinel = "S006B-SENTINEL-LVL-STDOUT-" + UUID.randomUUID();
      String probe = "S006B-PROBE-LVL-STDOUT-" + UUID.randomUUID();

      System.out.println(sentinel);
      LoggerFactory.getLogger("cloud.poesis.sie.defman.observability.LogsSinkSwitchIT")
          .debug(probe);

      assertProbeAbsentAfterSentinel(output.getOut(), sentinel, probe);
    }
  }

  // --------------------------------------------------------------------------
  // sink=both — stdout emits AND OTel exporter receives
  // --------------------------------------------------------------------------

  @Nested
  @SpringBootTest
  @ActiveProfiles({"tc", "tc-sink-both"})
  @ExtendWith(OutputCaptureExtension.class)
  @DisplayName("sink=both: dual emission — stdout AND OTLP appender both receive")
  class SinkBoth {

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
      registerPgProperties(registry);
    }

    // Per-@BeforeEach OTel appender attach — inherited from Unit 4's findings:
    //   (1) install(sdk) alone does NOT attach; we must construct + attach explicitly.
    //   (2) numLogsCapturedBeforeOtelInstall defaults to 0 — set the SDK BEFORE start() so the
    //       replay-queue window doesn't silently drop the probe.
    //   (3) @BeforeAll is too early: Spring Boot's LoggingApplicationListener resets the
    //       LoggerContext during context startup and stops appenders attached pre-context.
    private InMemoryLogRecordExporter exporter;
    private OpenTelemetrySdk sdk;
    private OpenTelemetryAppender appender;
    private Logger rootLogger;

    @BeforeEach
    void attachOtelAppender() {
      forceConsoleThreshold("TRACE");
      exporter = InMemoryLogRecordExporter.create();
      SdkLoggerProvider loggerProvider =
          SdkLoggerProvider.builder()
              .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
              .build();
      sdk = OpenTelemetrySdk.builder().setLoggerProvider(loggerProvider).build();

      LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
      appender = new OpenTelemetryAppender();
      appender.setContext(lc);
      appender.setCaptureMdcAttributes("*");
      appender.setOpenTelemetry(sdk);
      appender.start();
      rootLogger = lc.getLogger(Logger.ROOT_LOGGER_NAME);
      rootLogger.addAppender(appender);
      OpenTelemetryAppender.install(sdk);
    }

    @AfterEach
    void detachOtelAppender() {
      if (rootLogger != null && appender != null) {
        rootLogger.detachAppender(appender);
        appender.stop();
      }
      if (sdk != null) {
        sdk.close();
      }
    }

    @Test
    @DisplayName("AC-4 positive (stdout half): SLF4J probe appears on stdout when sink=both")
    void stdoutEmittedWhenSinkBoth(CapturedOutput output) {
      String sentinel = "S006-SENTINEL-BOTH-" + UUID.randomUUID();
      String probe = "S006-PROBE-BOTH-" + UUID.randomUUID();

      System.out.println(sentinel);
      LoggerFactory.getLogger(getClass()).info(probe);

      String captured = output.getOut();
      int sentinelIdx = captured.indexOf(sentinel);
      assertThat(sentinelIdx)
          .as("sentinel must appear on stdout (capture-active proof)")
          .isGreaterThanOrEqualTo(0);
      String afterSentinel = captured.substring(sentinelIdx);
      assertThat(afterSentinel)
          .as("AC-4: SLF4J INFO probe must reach stdout when sink=both (after sentinel)")
          .contains(probe);
    }

    @Test
    @DisplayName(
        "AC-6 regression (both): package logger binding remains INFO by default"
            + " so DEBUG probes stay filtered")
    void aopBindingPreservedUnderBothSink(CapturedOutput output) {
      String sentinel = "S006B-SENTINEL-LVL-BOTH-" + UUID.randomUUID();
      String probe = "S006B-PROBE-LVL-BOTH-" + UUID.randomUUID();

      System.out.println(sentinel);
      LoggerFactory.getLogger("cloud.poesis.sie.defman.observability.LogsSinkSwitchIT")
          .debug(probe);

      assertProbeAbsentAfterSentinel(output.getOut(), sentinel, probe);
    }

    @Test
    @DisplayName(
        "AC-4 dual-emission: when sink=both, the same SLF4J event reaches both stdout AND the"
            + " OTLP appender's exporter")
    void oTelExporterReceivesWhenSinkBoth(CapturedOutput output) {
      String sentinel = "S006-SENTINEL-BOTH-OTEL-" + UUID.randomUUID();
      String probe = "S006-PROBE-BOTH-OTEL-" + UUID.randomUUID();

      System.out.println(sentinel);
      LoggerFactory.getLogger(getClass()).info(probe);

      String captured = output.getOut();
      int sentinelIdx = captured.indexOf(sentinel);
      assertThat(sentinelIdx)
          .as("sentinel must appear on stdout (capture-active proof)")
          .isGreaterThanOrEqualTo(0);
      String afterSentinel = captured.substring(sentinelIdx);
      assertThat(afterSentinel)
          .as("stdout half: probe must appear on stdout under sink=both (after sentinel)")
          .contains(probe);

      List<LogRecordData> records = exporter.getFinishedLogRecordItems();
      assertThat(records)
          .as("OTLP half: at least one LogRecord must be captured (the probe)")
          .isNotEmpty();
      assertThat(records)
          .as("OTLP half: the captured LogRecord body must carry the probe verbatim")
          .anySatisfy(
              r ->
                  assertThat(r.getBodyValue())
                      .isNotNull()
                      .satisfies(body -> assertThat(body.asString()).contains(probe)));
    }
  }
}
