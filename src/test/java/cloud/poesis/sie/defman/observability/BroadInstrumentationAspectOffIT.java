package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.poesis.sie.defman.testbeans.AopProbeService;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * S-004b AC-3 (OFF case): with the single configurable knob {@code observability.aop.logLevel} set
 * to {@code OFF}, NO AOP log lines are emitted, but the span is STILL recorded (log gate is
 * orthogonal to span gate).
 *
 * <p>QA-blocker B-1 fix: this IT exercises the production code path end-to-end. The Logback level
 * for {@code cloud.poesis.sie.defman} is bound in {@code application.yaml} to the same
 * {@code observability.aop.logLevel} value, so setting it to {@code OFF} via
 * {@link TestPropertySource} drives BOTH the aspect's choose-which-SLF4J-method switch AND
 * Logback's filter level. No test-only {@code logger.setLevel(...)} mutation is performed.
 *
 * <p>Lives in a separate IT class from {@link BroadInstrumentationAspectIT} because
 * {@code @TestPropertySource} is class-scoped: a different property value requires a different
 * Spring context.
 */
@SpringBootTest
@ActiveProfiles("tc")
@Testcontainers
@Import(BroadInstrumentationAspectIT.AopItTestConfig.class)
@TestPropertySource(properties = "observability.aop.logLevel=OFF")
class BroadInstrumentationAspectOffIT {

  @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16.3-alpine");

  @DynamicPropertySource
  static void dynamicProps(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", pg::getJdbcUrl);
    registry.add("spring.datasource.username", pg::getUsername);
    registry.add("spring.datasource.password", pg::getPassword);
  }

  @Autowired InMemorySpanExporter spanExporter;
  @Autowired AopProbeService probeService;

  private ListAppender<ILoggingEvent> appender;
  private Logger defmanLogger;

  @BeforeEach
  void setUp() {
    spanExporter.reset();
    LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
    defmanLogger = lc.getLogger("cloud.poesis.sie.defman");
    // No defmanLogger.setLevel(...) here — Logback level is driven entirely by the
    // application.yaml binding of logging.level."[cloud.poesis.sie.defman]" =
    // ${observability.aop.logLevel}, which @TestPropertySource sets to OFF. Mutating it from the
    // test would mask the very production binding we're validating.
    appender = new ListAppender<>();
    appender.setContext(lc);
    appender.start();
    defmanLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    if (appender != null) {
      defmanLogger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName(
      "AC-3 (OFF): no AOP log lines, but the span is STILL emitted (log gate ≠ span gate)")
  void offLogLevelEmitsNoLogsButStillEmitsSpan() {
    appender.list.clear();
    spanExporter.reset();

    probeService.greet();

    long aopLogs =
        appender.list.stream()
            .filter(ev -> ev.getMessage() != null && ev.getMessage().startsWith("AOP "))
            .count();
    assertThat(aopLogs)
        .as(
            "AC-3 OFF: no AOP log lines must be emitted (Logback must filter at OFF via the"
                + " application.yaml binding of logging.level for cloud.poesis.sie.defman)")
        .isZero();

    long greetSpans =
        spanExporter.getFinishedSpanItems().stream()
            .filter(s -> s.getName().equals("AopProbeService.greet"))
            .count();
    assertThat(greetSpans)
        .as(
            "AC-3 OFF: span MUST still be emitted even when AOP log level is OFF "
                + "(log gating must not gate span emission)")
        .isGreaterThanOrEqualTo(1L);
  }
}
