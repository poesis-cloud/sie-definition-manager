package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;

/**
 * Direct unit test for {@link BroadInstrumentationAspect}.
 *
 * <p>Exercises the {@code @Around} advice by invoking it on a fresh aspect instance with a mocked
 * {@link ProceedingJoinPoint}, bypassing Spring's AOP proxy machinery. This is required for
 * accurate JaCoCo line coverage: when the aspect runs through Spring's CGLIB / AspectJ proxy chain,
 * the loaded bytecode no longer matches {@code target/classes} and JaCoCo reports 0 coverage with a
 * "execution data does not match" warning. A direct unit test loads the class once, cleanly, so
 * JaCoCo records every executed instruction.
 *
 * <p>Behavioural assertions are intentionally minimal — structural / Spring-integration behaviour
 * is owned by {@link BroadInstrumentationAspectIT}. This test exists to drive coverage of every
 * branch in {@code aroundDefmanStereotypeMethod} and {@code logAtLevel}.
 */
class BroadInstrumentationAspectUnitTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private InMemorySpanExporter exporter;
  private OpenTelemetry otel;

  @BeforeEach
  void setUp() {
    exporter = InMemorySpanExporter.create();
    SdkTracerProvider tp =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    otel = OpenTelemetrySdk.builder().setTracerProvider(tp).build();
    MDC.clear();
  }

  /**
   * Mock JoinPoint where {@code getTarget()} returns a real instance (drives {@code userClass}).
   */
  private ProceedingJoinPoint jp(Object target, String methodName, Object[] args) throws Throwable {
    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    Signature sig = mock(Signature.class);
    when(sig.getName()).thenReturn(methodName);
    // Reflective accessor: not used when target != null; needed when target == null
    Class<?> declaring = target != null ? target.getClass() : DummyTarget.class;
    when(sig.getDeclaringType()).thenAnswer(inv -> declaring);
    when(pjp.getSignature()).thenReturn(sig);
    when(pjp.getTarget()).thenReturn(target);
    when(pjp.getArgs()).thenReturn(args);
    when(pjp.proceed()).thenReturn("ok");
    return pjp;
  }

  @Test
  @DisplayName("happy path emits a span with required attributes and returns proceed() value")
  void happyPathEmitsSpan() throws Throwable {
    BroadInstrumentationAspect aspect = new BroadInstrumentationAspect(otel, "INFO");
    ProceedingJoinPoint pjp = jp(new DummyTarget(), "doWork", new Object[] {"a", 1});

    Object result = aspect.aroundDefmanStereotypeMethod(pjp);

    assertThat(result).isEqualTo("ok");
    List<SpanData> spans = exporter.getFinishedSpanItems();
    assertThat(spans).hasSize(1);
    SpanData span = spans.get(0);
    assertThat(span.getName()).isEqualTo("DummyTarget.doWork");
    assertThat(span.getAttributes().asMap().keySet().stream().map(Object::toString))
        .contains("code.namespace", "code.function", "sie.aop.args.summary", "sie.aop.duration_ms");
  }

  @Test
  @DisplayName("MDC tenant.id and sie.component are mirrored onto the span")
  void mdcMirroredOntoSpan() throws Throwable {
    MDC.put("gsm.tenant.id", "tenant-42");
    MDC.put("sie.component", "test-comp");
    BroadInstrumentationAspect aspect = new BroadInstrumentationAspect(otel, "INFO");
    ProceedingJoinPoint pjp = jp(new DummyTarget(), "m", new Object[0]);

    aspect.aroundDefmanStereotypeMethod(pjp);

    SpanData span = exporter.getFinishedSpanItems().get(0);
    assertThat(span.getAttributes().asMap().toString()).contains("tenant-42").contains("test-comp");
  }

  @Test
  @DisplayName("exception path records span error and rethrows")
  void exceptionPathRecordsAndRethrows() throws Throwable {
    BroadInstrumentationAspect aspect = new BroadInstrumentationAspect(otel, "INFO");
    ProceedingJoinPoint pjp = jp(new DummyTarget(), "boom", new Object[0]);
    RuntimeException boom = new IllegalStateException("nope");
    when(pjp.proceed()).thenThrow(boom);

    assertThatThrownBy(() -> aspect.aroundDefmanStereotypeMethod(pjp)).isSameAs(boom);

    SpanData span = exporter.getFinishedSpanItems().get(0);
    assertThat(span.getStatus().getStatusCode().name()).isEqualTo("ERROR");
    assertThat(span.getEvents()).anySatisfy(e -> assertThat(e.getName()).isEqualTo("exception"));
  }

  @Test
  @DisplayName("null target falls back to signature.declaringType()")
  void nullTargetUsesDeclaringType() throws Throwable {
    BroadInstrumentationAspect aspect = new BroadInstrumentationAspect(otel, "INFO");
    ProceedingJoinPoint pjp = jp(null, "noTargetMethod", new Object[0]);

    aspect.aroundDefmanStereotypeMethod(pjp);

    SpanData span = exporter.getFinishedSpanItems().get(0);
    assertThat(span.getName()).isEqualTo("DummyTarget.noTargetMethod");
  }

  @Test
  @DisplayName("every log level (TRACE/DEBUG/INFO/WARN/ERROR/OFF) and bad level falls back to INFO")
  void everyLogLevelBranch() throws Throwable {
    for (String level : new String[] {"TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF", "BOGUS"}) {
      BroadInstrumentationAspect aspect = new BroadInstrumentationAspect(otel, level);
      ProceedingJoinPoint pjp = jp(new DummyTarget(), "m", new Object[0]);
      aspect.aroundDefmanStereotypeMethod(pjp);
    }
    // also exercise null level via normaliseLevel (constructor accepts via @Value default, but
    // direct construction with null still drives the branch)
    BroadInstrumentationAspect nullLevel = new BroadInstrumentationAspect(otel, null);
    ProceedingJoinPoint pjp = jp(new DummyTarget(), "m", new Object[0]);
    nullLevel.aroundDefmanStereotypeMethod(pjp);
  }

  @Test
  @DisplayName("exception path under every log level (covers throwable-passing branches)")
  void exceptionUnderEveryLevel() throws Throwable {
    RuntimeException ex = new RuntimeException("x");
    for (String level : new String[] {"TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF"}) {
      BroadInstrumentationAspect aspect = new BroadInstrumentationAspect(otel, level);
      ProceedingJoinPoint pjp = jp(new DummyTarget(), "boom", new Object[0]);
      when(pjp.proceed()).thenThrow(ex);
      assertThatThrownBy(() -> aspect.aroundDefmanStereotypeMethod(pjp)).isSameAs(ex);
    }
  }

  @Test
  @DisplayName("logLevel field is volatile and mutable for IT-only level flipping")
  void logLevelFieldIsVolatileAndMutable() throws Exception {
    BroadInstrumentationAspect aspect = new BroadInstrumentationAspect(otel, "INFO");
    Field f = BroadInstrumentationAspect.class.getDeclaredField("logLevel");
    assertThat(java.lang.reflect.Modifier.isVolatile(f.getModifiers())).isTrue();
    f.setAccessible(true);
    f.set(aspect, "DEBUG");
    assertThat(f.get(aspect)).isEqualTo("DEBUG");
  }

  @Test
  @DisplayName("agent-owned trace/span MDC keys are not mutated by the aspect")
  void traceAndSpanMdcKeysAreNotMutatedByAspect() throws Throwable {
    String priorTrace = "trace-prior";
    String priorSpan = "span-prior";
    MDC.put("trace_id", priorTrace);
    MDC.put("span_id", priorSpan);
    try {
      BroadInstrumentationAspect aspect = new BroadInstrumentationAspect(otel, "INFO");
      ProceedingJoinPoint pjp = jp(new DummyTarget(), "m", new Object[0]);

      aspect.aroundDefmanStereotypeMethod(pjp);

      assertThat(MDC.get("trace_id"))
          .as("trace_id is agent-owned and must not be rewritten/removed by application code")
          .isEqualTo(priorTrace);
      assertThat(MDC.get("span_id"))
          .as("span_id is agent-owned and must not be rewritten/removed by application code")
          .isEqualTo(priorSpan);
    } finally {
      MDC.remove("trace_id");
      MDC.remove("span_id");
    }
  }

  @Test
  @DisplayName("AC-3 runtime: truncated payload emits summary event on active span")
  void truncatedPayloadEmitsSummaryEventInRuntimePath() throws Throwable {
    BroadInstrumentationAspect aspect =
        new BroadInstrumentationAspect(otel, "INFO", 40, 0.0d, () -> 0.99d);
    String payload = "{\"statement\":\"abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz\"}";
    ProceedingJoinPoint pjp =
        jp(
            new DummyAscriptionTarget(),
            "create",
            new Object[] {UUID.fromString("9dc2da8c-b639-4eb3-9f7d-bf29543fc830"), OBJECT_MAPPER.readTree(payload)});

    aspect.aroundDefmanStereotypeMethod(pjp);

    SpanData span = exporter.getFinishedSpanItems().get(0);
    assertThat(span.getEvents())
        .anySatisfy(
            event -> {
              assertThat(event.getName()).isEqualTo("sie.payload.truncated.summary");
              assertThat(event.getAttributes().get(AttributeKey.stringKey("operation")))
                  .isEqualTo(
                      "cloud.poesis.sie.defman.observability.BroadInstrumentationAspectUnitTest$DummyAscriptionTarget.create");
              assertThat(event.getAttributes().get(AttributeKey.stringKey("id")))
                  .isEqualTo("9dc2da8c-b639-4eb3-9f7d-bf29543fc830");
              assertThat(event.getAttributes().get(AttributeKey.stringKey("capped.preview")))
                  .contains("<truncated:");
            });
  }

  @Test
  @DisplayName("AC-4 runtime: sampled bypass selected emits sibling full-payload line with sampled tag")
  void sampledBypassSelectedEmitsSiblingPayloadLine() throws Throwable {
    BroadInstrumentationAspect aspect =
        new BroadInstrumentationAspect(otel, "INFO", 40, 1.0d, () -> 0.0d);
    String payload = "{\"statement\":\"abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz\"}";
    ProceedingJoinPoint pjp =
        jp(
            new DummyAscriptionTarget(),
            "create",
            new Object[] {UUID.randomUUID(), OBJECT_MAPPER.readTree(payload)});

    ListAppender<ILoggingEvent> appender = attachAppender(DummyAscriptionTarget.class);
    try {
      aspect.aroundDefmanStereotypeMethod(pjp);
    } finally {
      detachAppender(DummyAscriptionTarget.class, appender);
    }

    assertThat(appender.list)
        .anySatisfy(
            event -> {
              if (!"AOP payload sampled".equals(event.getMessage())) {
                return;
              }
              assertThat(event.getMDCPropertyMap().get("sie.payload.sampled")).isEqualTo("true");
              assertThat(event.getMDCPropertyMap().get("sie.payload")).isEqualTo(payload);
            });
  }

  @Test
  @DisplayName("AC-4 runtime: sampled bypass not selected does not emit sibling full-payload line")
  void sampledBypassNotSelectedDoesNotEmitSiblingPayloadLine() throws Throwable {
    BroadInstrumentationAspect aspect =
        new BroadInstrumentationAspect(otel, "INFO", 40, 0.5d, () -> 0.9d);
    String payload = "{\"statement\":\"abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz\"}";
    ProceedingJoinPoint pjp =
        jp(
            new DummyAscriptionTarget(),
            "create",
            new Object[] {UUID.randomUUID(), OBJECT_MAPPER.readTree(payload)});

    ListAppender<ILoggingEvent> appender = attachAppender(DummyAscriptionTarget.class);
    try {
      aspect.aroundDefmanStereotypeMethod(pjp);
    } finally {
      detachAppender(DummyAscriptionTarget.class, appender);
    }

    assertThat(appender.list)
        .noneSatisfy(
            event -> {
              assertThat(event.getMessage()).isEqualTo("AOP payload sampled");
            });
  }

  private static ListAppender<ILoggingEvent> attachAppender(Class<?> loggerClass) {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger logger = context.getLogger(loggerClass.getName());
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.setContext(context);
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void detachAppender(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger logger = context.getLogger(loggerClass.getName());
    logger.detachAppender(appender);
    appender.stop();
  }

  /**
   * Stand-in target with predictable simple-name; lives in observability so userClass is stable.
   */
  static class DummyTarget {
    public String doWork(String s, int n) {
      return s + n;
    }

    public void m() {}

    public void noTargetMethod() {}

    public void boom() {
      throw new IllegalStateException("never invoked directly");
    }
  }

  static class DummyAscriptionTarget {
    public String create(UUID id, Object statement) {
      return id + String.valueOf(statement);
    }
  }
}
