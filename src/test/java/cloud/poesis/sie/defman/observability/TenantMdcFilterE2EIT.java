package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.common.AttributeKey;
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
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * End-to-end MockMvc IT for the {@link TenantMdcFilter} &rarr; SLF4J/MDC &rarr; {@link
 * OpenTelemetryAppender} &rarr; OTLP {@link LogRecordData#getAttributes()} path (S-006 AC-3,
 * Critique C-2).
 *
 * <p>Complements {@link OpenTelemetryAppenderIT#mdcAttributesPropagatedToLogRecord} which calls
 * {@code MDC.put(...)} directly in the test method and therefore only proves the
 * MDC&rarr;LogRecord-attribute bridge half. This IT closes the loop on AC-3's "MDC populated by
 * {@link TenantMdcFilter}" precondition by driving a real HTTP request through the filter so the
 * filter is the actual writer of {@code gsm.tenant.id} and {@code sie.component}, then emitting a
 * log line from within the request thread (inside a throwaway test controller), and asserting both
 * MDC keys surface on the captured {@link LogRecordData}.
 *
 * <h2>Why no {@code @SpringBootTest}</h2>
 *
 * <p>The contract under test is the cohabitation of the filter (writes MDC inside {@code
 * doFilterInternal}) and the OTel Logback appender (captures MDC into LogRecord attributes via
 * {@code setCaptureMdcAttributes("*")}). Both halves are exercised through pure Logback + MockMvc +
 * the filter class directly &mdash; no auto-configuration, no profile-yaml carrier, no
 * Postgres/Flyway warm-up. This is faster, isolates the assertion, and side-steps Boot-1/Boot-2
 * traps documented in {@code .S-006-huddle.md}.
 *
 * <h2>Hard invariants honored</h2>
 *
 * <ul>
 *   <li><b>R-INV-1 / R-INV-8</b> &mdash; {@link TenantMdcFilter} is NOT modified; the filter class
 *       is instantiated as-is and wired into the standalone MockMvc filter chain.
 *   <li><b>R-INV-2</b> &mdash; no semantic-convention key written by test code; the only attributes
 *       asserted are the {@code gsm.*} / {@code sie.*} vendor namespace per ADR-001 D-3.
 *   <li><b>R-INV-4</b> &mdash; the second assertion confirms the filter's sentinel value {@code
 *       "unknown"} is present on the {@code gsm.tenant.id} attribute when the header is omitted via
 *       {@code .isEqualTo(TenantMdcFilter.UNKNOWN_TENANT)}. This validates the filter writes the
 *       sentinel deterministically, not the empty string or null.
 * </ul>
 */
class TenantMdcFilterE2EIT {

  private static InMemoryLogRecordExporter exporter;
  private static OpenTelemetrySdk sdk;

  private OpenTelemetryAppender appender;
  private Logger rootLogger;
  private MockMvc mockMvc;

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
  void setUp() {
    // Defensive MDC clear — this IT does NOT use @SpringBootTest, so we own the thread state.
    MDC.clear();

    // Attach the OTel Logback bridge to the root logger. Same construct-attach-start pattern as
    // OpenTelemetryAppenderIT (see Unit 4 outcome OTel-1/2/3 in .S-006-huddle.md).
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

    // Build a standalone MockMvc with the real production filter wired ahead of a throwaway
    // controller that emits a single SLF4J INFO log line inside the request thread. The filter
    // populates gsm.tenant.id + sie.component in MDC before the controller runs and restores prior
    // values in its `finally` block (see TenantMdcFilter#doFilterInternal).
    mockMvc =
        MockMvcBuilders.standaloneSetup(new LogProbeController())
            .addFilter(new TenantMdcFilter(), "/*")
            .build();
  }

  @AfterEach
  void tearDown() {
    if (rootLogger != null && appender != null) {
      rootLogger.detachAppender(appender);
      appender.stop();
    }
    MDC.clear();
  }

  // --------------------------------------------------------------------------
  // C-2 — AC-3 end-to-end through the real filter chain
  // --------------------------------------------------------------------------

  @Test
  @DisplayName(
      "C-2 / AC-3: X-Tenant-Id request header → TenantMdcFilter writes MDC →"
          + " OpenTelemetryAppender captures MDC into LogRecord attributes (gsm.tenant.id,"
          + " sie.component)")
  void tenantHeaderPropagatesThroughFilterToLogRecordAttributes() throws Exception {
    mockMvc
        .perform(get("/__test/emit-log").header(TenantMdcFilter.HEADER_TENANT_ID, "acme-corp"))
        .andExpect(status().isOk());

    // MockMvc emits incidental INFO records during dispatcher init (Hibernate Validator version,
    // TestDispatcherServlet initialization, etc.). Filter to the controller's SLF4J emit by
    // instrumentation scope; that single record carries the MDC populated by the filter.
    LogRecordData record = findProbeRecord();
    assertThat(record.getAttributes().get(AttributeKey.stringKey(TenantMdcFilter.MDC_TENANT_ID)))
        .as(
            "AC-3: TenantMdcFilter must populate gsm.tenant.id from X-Tenant-Id and the"
                + " OpenTelemetryAppender must surface it as a LogRecord attribute (vendor"
                + " namespace per ADR-001 D-3)")
        .isEqualTo("acme-corp");
    assertThat(record.getAttributes().get(AttributeKey.stringKey(TenantMdcFilter.MDC_COMPONENT)))
        .as(
            "AC-3: TenantMdcFilter must populate sie.component with the COMPONENT_VALUE constant"
                + " and the OpenTelemetryAppender must surface it as a LogRecord attribute")
        .isEqualTo(TenantMdcFilter.COMPONENT_VALUE);
  }

  @Test
  @DisplayName(
      "C-2 corollary: missing X-Tenant-Id header → filter writes 'unknown' sentinel → attribute"
          + " surfaces with the sentinel value (NOT absent — the filter always writes the key)")
  void missingTenantHeaderSurfacesUnknownSentinelOnAttribute() throws Exception {
    mockMvc.perform(get("/__test/emit-log")).andExpect(status().isOk());

    LogRecordData record = findProbeRecord();
    // The filter ALWAYS writes the MDC keys inside doFilterInternal (sanitize() collapses absent /
    // malformed values to UNKNOWN_TENANT). So under this end-to-end path the attribute is
    // PRESENT with the sentinel — distinct from the out-of-request absent-not-empty case asserted
    // by OpenTelemetryAppenderIT#mdcAttributesAbsentWhenNotSet.
    assertThat(record.getAttributes().get(AttributeKey.stringKey(TenantMdcFilter.MDC_TENANT_ID)))
        .as(
            "filter writes UNKNOWN_TENANT for absent header — attribute must surface as the"
                + " sentinel, never null and never empty-string")
        .isEqualTo(TenantMdcFilter.UNKNOWN_TENANT);
    assertThat(record.getAttributes().get(AttributeKey.stringKey(TenantMdcFilter.MDC_COMPONENT)))
        .as("sie.component is unconditionally written by the filter")
        .isEqualTo(TenantMdcFilter.COMPONENT_VALUE);
  }

  /**
   * Finds the {@link LogProbeController}'s single SLF4J emit among the exporter's records, ignoring
   * incidental MockMvc / Hibernate-Validator init records.
   */
  private LogRecordData findProbeRecord() {
    List<LogRecordData> records = exporter.getFinishedLogRecordItems();
    List<LogRecordData> probeRecords =
        records.stream()
            .filter(
                r ->
                    r.getInstrumentationScopeInfo()
                        .getName()
                        .equals(LogProbeController.class.getName()))
            .toList();
    assertThat(probeRecords)
        .as(
            "exactly one LogRecord on the LogProbeController scope should be captured for the"
                + " controller's single SLF4J emit")
        .hasSize(1);
    return probeRecords.get(0);
  }

  /**
   * Throwaway probe controller. Emits a single SLF4J INFO log line inside the request thread so the
   * MDC values written by {@link TenantMdcFilter} are still on the thread when the log event is
   * passed to the OTel appender.
   */
  @RestController
  static class LogProbeController {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(LogProbeController.class);

    @GetMapping("/__test/emit-log")
    String emitLog() {
      LOG.info("e2e-tenant-mdc-probe");
      return "ok";
    }
  }
}
