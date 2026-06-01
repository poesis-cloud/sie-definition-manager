package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for the OTel Logback appender wiring (S-006 Unit 4).
 *
 * <p>Validates the production code path that flows SLF4J/Logback events through the {@link
 * OpenTelemetryAppender} bridge to the OTel logs SDK. In production the OTel Java agent installs
 * the bridge automatically when {@code OTEL_LOGS_EXPORTER=otlp}; under {@code mvn test} no agent is
 * attached, so this IT installs the appender programmatically against an in-memory {@link
 * InMemoryLogRecordExporter} and asserts:
 *
 * <ul>
 *   <li><b>AC-2</b> — an SLF4J log emitted from application code reaches the OTLP logs pipeline.
 *   <li><b>AC-3</b> — MDC keys {@code gsm.tenant.id} and {@code sie.component} stamped by {@link
 *       TenantMdcFilter} appear as {@code LogRecordData} attributes when set; and are absent (not
 *       empty-string) on the {@code LogRecordData} when MDC is not set (absent &ne; empty).
 *   <li><b>AC-6</b> — the {@code cloud.poesis.sie.defman} Logback level binding from {@code
 *       application.yaml} ({@code observability.aop.logLevel:INFO}) is preserved at the INFO
 *       default.
 * </ul>
 *
 * <p><b>R-INV-1</b>: this test does NOT modify {@link TenantMdcFilter} or {@link
 * BroadInstrumentationAspect}.
 *
 * <p><b>R-INV-2 / D-8</b>: this test asserts that our code does NOT set OTel semantic-convention
 * keys ({@code trace_id}, {@code span_id}, {@code service.name}) as application-level attributes —
 * these are owned by the OTel agent (runtime) and the OTel collector (resource), not by Defman.
 */
@SpringBootTest
@ActiveProfiles("tc")
@Testcontainers
class OpenTelemetryAppenderIT {

  private static final String TENANT_KEY = "gsm.tenant.id";
  private static final String COMPONENT_KEY = "sie.component";

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
    // Attach the OTel Logback bridge in @BeforeEach (not @BeforeAll): Spring Boot's
    // LoggingApplicationListener reconfigures the LoggerContext during context startup and may
    // reset/stop previously-attached appenders. By the time the per-test method runs, the context
    // is fully started and stable — so attaching here guarantees the appender survives long enough
    // to observe the test's emit.
    LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
    appender = new OpenTelemetryAppender();
    appender.setContext(lc);
    // Capture all MDC keys as LogRecord attributes — the appender by default captures NONE.
    // "*" mirrors the OTel agent's auto-instrumentation default and exercises the AC-3 path.
    appender.setCaptureMdcAttributes("*");
    // Bind SDK before start(): if openTelemetry is null at start time and
    // numLogsCapturedBeforeOtelInstall defaults to 0, append() silently drops events. Setting the
    // SDK up-front sidesteps that window — install(sdk) below still runs to mirror the production
    // wiring path used by the OTel Java agent.
    appender.setOpenTelemetry(sdk);
    appender.start();
    rootLogger = lc.getLogger(Logger.ROOT_LOGGER_NAME);
    rootLogger.addAppender(appender);
    // Walks the logger context and binds our SDK into every OpenTelemetryAppender instance —
    // exercises the production install() codepath even though we already set the SDK directly.
    OpenTelemetryAppender.install(sdk);

    exporter.reset();
    // Defensive: clear any MDC residue from prior tests on this thread.
    MDC.remove(TENANT_KEY);
    MDC.remove(COMPONENT_KEY);
  }

  @AfterEach
  void detachAppender() {
    if (rootLogger != null && appender != null) {
      rootLogger.detachAppender(appender);
      appender.stop();
    }
    MDC.remove(TENANT_KEY);
    MDC.remove(COMPONENT_KEY);
  }

  // --------------------------------------------------------------------------
  // AC-2 — log emitted via SLF4J reaches the OpenTelemetry log exporter
  // --------------------------------------------------------------------------

  @Test
  @DisplayName("AC-2: an SLF4J INFO log emitted from application code is exported as a LogRecord")
  void logEmittedViaSlf4jReachesOpenTelemetryExporter() {
    org.slf4j.Logger log = LoggerFactory.getLogger(getClass());

    log.info("hello-S006-AC2");

    List<LogRecordData> records = exporter.getFinishedLogRecordItems();
    assertThat(records)
        .as("exactly one LogRecord should be captured for the single SLF4J INFO emit")
        .hasSize(1);
    assertThat(records.get(0).getBodyValue())
        .as("LogRecord body must carry the SLF4J message verbatim")
        .isNotNull();
    assertThat(records.get(0).getBodyValue().asString()).contains("hello-S006-AC2");
  }

  // --------------------------------------------------------------------------
  // AC-3 — MDC vocab keys flow to LogRecord attributes
  // --------------------------------------------------------------------------

  @Test
  @DisplayName("AC-3: MDC keys gsm.tenant.id + sie.component propagate to LogRecordData attributes")
  void mdcAttributesPropagatedToLogRecord() {
    MDC.put(TENANT_KEY, "tenant-42");
    MDC.put(COMPONENT_KEY, "DefinitionManager");

    LoggerFactory.getLogger(getClass()).info("mdc-propagation-probe");

    List<LogRecordData> records = exporter.getFinishedLogRecordItems();
    assertThat(records).hasSize(1);
    LogRecordData record = records.get(0);
    assertThat(record.getAttributes().get(AttributeKey.stringKey(TENANT_KEY)))
        .as("gsm.tenant.id MDC must surface as a LogRecord attribute")
        .isEqualTo("tenant-42");
    assertThat(record.getAttributes().get(AttributeKey.stringKey(COMPONENT_KEY)))
        .as("sie.component MDC must surface as a LogRecord attribute")
        .isEqualTo("DefinitionManager");
  }

  // --------------------------------------------------------------------------
  // AC-3 corollary — absent != empty
  // --------------------------------------------------------------------------

  @Test
  @DisplayName(
      "AC-3 strict: when MDC keys are NOT set, the LogRecord attributes must omit the keys"
          + " (absent, not empty-string)")
  void mdcAttributesAbsentWhenNotSet() {
    // No MDC.put — emit straight away.
    LoggerFactory.getLogger(getClass()).info("mdc-absence-probe");

    List<LogRecordData> records = exporter.getFinishedLogRecordItems();
    assertThat(records).hasSize(1);
    LogRecordData record = records.get(0);

    assertThat(record.getAttributes().get(AttributeKey.stringKey(TENANT_KEY)))
        .as("gsm.tenant.id must be ABSENT (null), never an empty string")
        .isNull();
    assertThat(record.getAttributes().get(AttributeKey.stringKey(COMPONENT_KEY)))
        .as("sie.component must be ABSENT (null), never an empty string")
        .isNull();
  }

  // --------------------------------------------------------------------------
  // AC-6 — S-004b Logback level binding preserved at INFO default
  // --------------------------------------------------------------------------

  @Test
  @DisplayName(
      "AC-6: cloud.poesis.sie.defman logger is bound to INFO (default of"
          + " observability.aop.logLevel) — INFO enabled, DEBUG disabled")
  void s004bLogLevelBindingPreservedAtInfo() {
    org.slf4j.Logger defmanLogger =
        LoggerFactory.getLogger("cloud.poesis.sie.defman.observability.OpenTelemetryAppenderIT");

    assertThat(defmanLogger.isInfoEnabled())
        .as("INFO must be enabled for cloud.poesis.sie.defman per application.yaml binding")
        .isTrue();
    assertThat(defmanLogger.isDebugEnabled())
        .as(
            "DEBUG must be disabled — observability.aop.logLevel default is INFO; raising it would"
                + " be a regression of S-004b QA-blocker B-1")
        .isFalse();
  }

  // --------------------------------------------------------------------------
  // D-8 smoke — our code does NOT set OTel semantic-convention keys
  // --------------------------------------------------------------------------

  @Test
  @DisplayName(
      "D-8: Defman code MUST NOT set trace_id / span_id / service.name as LogRecord attributes —"
          + " these are owned by the OTel agent (runtime) and collector (resource)")
  void semconvAttributesNotSetInCode() {
    LoggerFactory.getLogger(getClass()).info("semconv-guard-probe");

    List<LogRecordData> records = exporter.getFinishedLogRecordItems();
    assertThat(records).hasSize(1);
    LogRecordData record = records.get(0);

    assertThat(record.getAttributes().get(AttributeKey.stringKey("trace_id")))
        .as("trace_id is owned by the OTel agent — Defman must not set it as an attribute")
        .isNull();
    assertThat(record.getAttributes().get(AttributeKey.stringKey("span_id")))
        .as("span_id is owned by the OTel agent — Defman must not set it as an attribute")
        .isNull();
    assertThat(record.getAttributes().get(AttributeKey.stringKey("service.name")))
        .as("service.name is a Resource attribute owned by the collector — never per-LogRecord")
        .isNull();
  }
}
