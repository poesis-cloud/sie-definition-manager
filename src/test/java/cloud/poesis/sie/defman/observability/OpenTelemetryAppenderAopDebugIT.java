package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import cloud.poesis.sie.defman.testbeans.AopProbeService;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for the AOP &rarr; SLF4J &rarr; {@link OpenTelemetryAppender} &rarr; OTLP path
 * at {@code observability.aop.logLevel=DEBUG} (S-006 AC-6, Critique C-1).
 *
 * <p>Complements {@link OpenTelemetryAppenderIT#s004bLogLevelBindingPreservedAtInfo} which asserts
 * only the static level binding at the default INFO. This IT exercises the full
 * <b>BroadInstrumentationAspect emit &rarr; Logback DEBUG-enabled target logger &rarr;
 * root-attached OpenTelemetryAppender &rarr; in-memory exporter</b> pipeline end-to-end: invoking
 * {@link AopProbeService#greet()} must produce a {@link LogRecordData} whose body matches the AOP
 * entry-log shape ({@code "AOP entry"}, see {@link BroadInstrumentationAspect}).
 *
 * <h2>Property carrier: profile-yaml, NOT {@code @TestPropertySource}</h2>
 *
 * <p>Per the {@code .S-006-huddle.md} Unit 5 surprise inventory (Boot-1 / Boot-2), Spring Boot's
 * {@code LoggingApplicationListener} fires on {@code ApplicationEnvironmentPreparedEvent} — BEFORE
 * any {@code @TestPropertySource}- or {@code @SpringBootTest(properties=...)}-supplied properties
 * become visible. The {@code application.yaml} binding {@code
 * logging.level."[cloud.poesis.sie.defman]" = ${observability.aop.logLevel:INFO}} is therefore
 * evaluated against the BASE {@code application.yaml} unless a profile-specific yaml has already
 * overridden {@code observability.aop.logLevel}. We use {@code application-tc-aop-debug.yaml}
 * composed via {@code @ActiveProfiles({"tc", "tc-aop-debug"})} — the same pattern {@link
 * LogsSinkSwitchIT} uses for {@code logging.threshold.console}.
 *
 * <h2>Hard invariants honored</h2>
 *
 * <ul>
 *   <li><b>R-INV-1 / R-INV-8</b> — {@link TenantMdcFilter} and {@link BroadInstrumentationAspect}
 *       are NOT modified by this test (verify-only).
 *   <li><b>R-INV-2</b> — no semantic-convention key ({@code trace_id}, {@code span_id}, {@code
 *       service.name}) is written by test code.
 *   <li><b>R-INV-7</b> — the {@code application.yaml} AOP-level binding line is preserved verbatim;
 *       this test only overrides the {@code observability.aop.logLevel} property via profile yaml
 *       (which is exactly how Helm flips it in production via the {@code
 *       OBSERVABILITY_AOP_LOG_LEVEL} env var).
 * </ul>
 */
@SpringBootTest
@ActiveProfiles({"tc", "tc-aop-debug"})
@Testcontainers
@org.springframework.context.annotation.Import(
    OpenTelemetryAppenderAopDebugIT.AopProbeScanConfig.class)
class OpenTelemetryAppenderAopDebugIT {

  @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16.3-alpine");

  @DynamicPropertySource
  static void pgProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", pg::getJdbcUrl);
    registry.add("spring.datasource.username", pg::getUsername);
    registry.add("spring.datasource.password", pg::getPassword);
  }

  private static InMemoryLogRecordExporter exporter;
  private static OpenTelemetrySdk sdk;
  private OpenTelemetryAppender appender;
  private Logger rootLogger;

  @Autowired AopProbeService probeService;

  @BeforeAll
  static void buildSdk() {
    exporter = InMemoryLogRecordExporter.create();
    SdkLoggerProvider loggerProvider =
        SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
            .build();
    sdk = OpenTelemetrySdk.builder().setLoggerProvider(loggerProvider).build();
  }

  @AfterAll
  static void closeSdk() {
    if (sdk != null) {
      sdk.close();
    }
  }

  @BeforeEach
  void attachAppender() {
    // Per OpenTelemetryAppenderIT Unit 4 outcome (OTel-1, OTel-2, OTel-3):
    //   * install(sdk) alone does NOT attach; we must construct + attach explicitly.
    //   * Set SDK BEFORE start() so numLogsCapturedBeforeOtelInstall=0 doesn't silently drop
    // events.
    //   * Attach in @BeforeEach (not @BeforeAll) — LoggingApplicationListener reconfigures the
    //     LoggerContext during context startup and stops pre-context appenders.
    LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
    appender = new OpenTelemetryAppender();
    appender.setContext(lc);
    appender.setCaptureMdcAttributes("*");
    appender.setOpenTelemetry(sdk);
    appender.start();
    rootLogger = lc.getLogger(Logger.ROOT_LOGGER_NAME);
    rootLogger.addAppender(appender);
    OpenTelemetryAppender.install(sdk);
    exporter.reset();
  }

  @AfterEach
  void detachAppender() {
    if (rootLogger != null && appender != null) {
      rootLogger.detachAppender(appender);
      appender.stop();
    }
  }

  // --------------------------------------------------------------------------
  // C-1 — AC-6 direct AOP → OTLP coverage at DEBUG
  // --------------------------------------------------------------------------

  @Test
  @DisplayName(
      "C-1 / AC-6: at observability.aop.logLevel=DEBUG, invoking an AOP-instrumented bean produces"
          + " an 'AOP entry' LogRecord on the OTLP exporter (full aspect→Logback→OTLP path)")
  void aop_debug_log_lines_reach_otlp_exporter() {
    // Sanity: the application.yaml binding `logging.level."[cloud.poesis.sie.defman]" =
    // ${observability.aop.logLevel:INFO}` must have evaluated to DEBUG via the profile yaml. If
    // this
    // fails, the property carrier is broken — diagnose BEFORE asserting the exporter contents.
    org.slf4j.Logger defmanLogger = LoggerFactory.getLogger(AopProbeService.class);
    assertThat(defmanLogger.isDebugEnabled())
        .as(
            "AC-6 pre-flight: profile yaml `tc-aop-debug` must drive"
                + " observability.aop.logLevel=DEBUG → Logback DEBUG for cloud.poesis.sie.defman."
                + " If false, the application.yaml binding or profile composition is broken.")
        .isTrue();

    // Invoke the AOP-instrumented bean. BroadInstrumentationAspect's @Around advice fires and
    // emits two log lines on the AopProbeService logger at the aspect's configured logLevel
    // (DEBUG): "AOP entry" before proceed(), "AOP exit" after.
    probeService.greet();

    List<LogRecordData> records = exporter.getFinishedLogRecordItems();

    // The aspect emits at least two AOP lines (entry + exit). Some additional records may appear
    // from incidental application logs during context warm-up — we filter for the AOP entry shape
    // specifically.
    assertThat(records)
        .as(
            "AC-6 / C-1: the OTLP appender MUST receive an AOP entry LogRecord for the"
                + " AopProbeService.greet() invocation (full BroadInstrumentationAspect → Logback"
                + " DEBUG → OpenTelemetryAppender → exporter path)")
        .anySatisfy(
            r -> {
              assertThat(r.getBodyValue())
                  .as("AOP entry record must carry a non-null body")
                  .isNotNull();
              assertThat(r.getBodyValue().asString())
                  .as("body must start with 'AOP entry' per BroadInstrumentationAspect contract")
                  .startsWith("AOP entry");
              // The aspect target was AopProbeService.greet() — its log emits on the
              // AopProbeService logger.
              assertThat(r.getInstrumentationScopeInfo().getName())
                  .as("LogRecord instrumentation scope must be the AopProbeService logger name")
                  .isEqualTo(AopProbeService.class.getName());
            });
  }

  /**
   * Test-only configuration that registers {@link AopProbeService} (and any other testbeans) as
   * Spring beans so the AOP aspect can wrap them. Mirrors the pattern used in {@link
   * BroadInstrumentationAspectIT.AopItTestConfig}; we omit the OTel tracer wiring here because this
   * IT does NOT assert span emission — the no-op {@code OpenTelemetryConfig} fallback bean is
   * sufficient for the aspect's tracer dependency.
   */
  @TestConfiguration
  @ComponentScan(basePackages = "cloud.poesis.sie.defman.testbeans")
  static class AopProbeScanConfig {}
}
