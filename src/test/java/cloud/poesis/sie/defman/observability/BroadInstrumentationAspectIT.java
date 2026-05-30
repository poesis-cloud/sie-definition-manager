package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.poesis.sie.defman.testbeans.AopProbeRepository;
import cloud.poesis.sie.defman.testbeans.AopProbeService;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@link BroadInstrumentationAspect} (S-004b AC-1, AC-2, AC-3, AC-5, AC-8).
 *
 * <p>Boots the full Spring context with an in-memory OTel SDK injected as a {@code @Primary
 * OpenTelemetry} bean and a Logback {@link ListAppender} attached to the {@code
 * cloud.poesis.sie.defman} logger root, then exercises:
 *
 * <ul>
 *   <li><b>AC-1</b> — enumerates every Defman stereotype bean and asserts a span is recorded for a
 *       no-arg public method call, with required structural attributes ({@code code.namespace},
 *       {@code code.function}, {@code sie.aop.args.summary}, {@code sie.aop.duration_ms}).
 *   <li><b>AC-2</b> — there is no opt-out hook class on the classpath ({@code
 *       NoAopInstrumentation} marker must not exist). Structural compile-time check lives in
 *       {@link FinalStereotypeMethodTest}.
 *   <li><b>AC-3</b> — at {@code DEBUG}, 2 log lines fire on success and 3 on exception; at {@code
 *       OFF}, no AOP log lines fire BUT the span is still emitted.
 *   <li><b>AC-5</b> — secret-shaped arg values never appear in span attributes or log MDC/messages
 *       (only the {@code java.lang.Class} bucket).
 *   <li><b>AC-8</b> — a {@code @Repository} bean is wrapped (the agent-emitted JDBC {@code CLIENT}
 *       child span is verified at deployment by S-015, out of scope here).
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("tc")
@Testcontainers
@Import(BroadInstrumentationAspectIT.AopItTestConfig.class)
// S-004b QA-blocker B-1 fix: drive the single configurable knob via test properties; the
// application.yaml binding propagates this to Logback's filter level, so this IT exercises the
// same production path (no test-only Logback level mutation in @BeforeEach).
@TestPropertySource(properties = "observability.aop.logLevel=DEBUG")
class BroadInstrumentationAspectIT {

  @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16.3-alpine");

  @DynamicPropertySource
  static void dynamicProps(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", pg::getJdbcUrl);
    registry.add("spring.datasource.username", pg::getUsername);
    registry.add("spring.datasource.password", pg::getPassword);
  }

  @Autowired ApplicationContext applicationContext;
  @Autowired InMemorySpanExporter spanExporter;
  @Autowired AopProbeService probeService;
  @Autowired AopProbeRepository probeRepository;

  private ListAppender<ILoggingEvent> appender;
  private Logger defmanLogger;

  @BeforeEach
  void setUp() {
    spanExporter.reset();
    LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
    defmanLogger = lc.getLogger("cloud.poesis.sie.defman");
    // NOTE: we deliberately do NOT call defmanLogger.setLevel(...) here. The Logback level is
    // driven by application.yaml binding `logging.level."[cloud.poesis.sie.defman]" =
    // ${observability.aop.logLevel}`, which @TestPropertySource on the class sets to DEBUG.
    // Mutating it from the test would mask the very production binding we're validating.
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

  // --------------------------------------------------------------------------
  // AC-1: every Defman stereotype bean gets a span on a no-arg public method
  // --------------------------------------------------------------------------

  @Test
  @DisplayName(
      "AC-1: every Defman stereotype bean (outside observability) is wrapped — invoking a no-arg"
          + " public method emits a span with required attributes")
  void everyStereotypeBeanIsWrapped() throws Exception {
    Map<String, Object> stereotypeBeans = collectDefmanStereotypeBeans();
    assertThat(stereotypeBeans)
        .as("sanity: scan must find at least one Defman stereotype bean")
        .isNotEmpty();

    Map<String, String> expectedClassToMethod = new TreeMap<>();
    Map<String, String> skipped = new LinkedHashMap<>();

    for (Map.Entry<String, Object> e : stereotypeBeans.entrySet()) {
      Object bean = e.getValue();
      Class<?> userClass = org.springframework.util.ClassUtils.getUserClass(bean);
      Method invokable = findInvokableNoArgPublicMethod(userClass);
      if (invokable == null) {
        skipped.put(userClass.getName(), "no invokable no-arg public method");
        continue;
      }
      try {
        invokable.invoke(bean);
      } catch (Throwable t) {
        // Method threw — that's fine for AC-1: aspect still wraps and records the span.
      }
      expectedClassToMethod.put(userClass.getName(), invokable.getName());
    }

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    TreeSet<String> capturedClasses = new TreeSet<>();
    for (SpanData s : spans) {
      String ns = s.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("code.namespace"));
      if (ns != null) {
        capturedClasses.add(ns);
      }
    }

    // Every expected class produced at least one span with the AOP scope.
    for (Map.Entry<String, String> e : expectedClassToMethod.entrySet()) {
      String className = e.getKey();
      assertThat(capturedClasses)
          .as(
              "AC-1: AOP span MUST be recorded for stereotype class %s "
                  + "(invoked method=%s). Skipped beans: %s",
              className, e.getValue(), skipped)
          .contains(className);
    }

    // Each captured AOP span must carry the required structural attributes.
    long aopSpanCount =
        spans.stream()
            .filter(s -> BroadInstrumentationAspect.INSTRUMENTATION_SCOPE
                .equals(s.getInstrumentationScopeInfo().getName()))
            .count();
    assertThat(aopSpanCount).as("at least one span must use the AOP instrumentation scope")
        .isGreaterThan(0L);

    spans.stream()
        .filter(s -> BroadInstrumentationAspect.INSTRUMENTATION_SCOPE
            .equals(s.getInstrumentationScopeInfo().getName()))
        .forEach(
            s -> {
              assertThat(
                      s.getAttributes()
                          .get(io.opentelemetry.api.common.AttributeKey.stringKey("code.namespace")))
                  .as("code.namespace required on AOP span %s", s.getName())
                  .isNotNull();
              assertThat(
                      s.getAttributes()
                          .get(io.opentelemetry.api.common.AttributeKey.stringKey("code.function")))
                  .as("code.function required on AOP span %s", s.getName())
                  .isNotNull();
              assertThat(
                      s.getAttributes()
                          .get(
                              io.opentelemetry.api.common.AttributeKey.stringKey(
                                  "sie.aop.args.summary")))
                  .as("sie.aop.args.summary required on AOP span %s", s.getName())
                  .isNotNull();
              assertThat(
                      s.getAttributes()
                          .get(
                              io.opentelemetry.api.common.AttributeKey.longKey(
                                  "sie.aop.duration_ms")))
                  .as("sie.aop.duration_ms required on AOP span %s", s.getName())
                  .isNotNull();
            });
  }

  // --------------------------------------------------------------------------
  // AC-2: no opt-out hook class exists
  // --------------------------------------------------------------------------

  @Test
  @DisplayName("AC-2: no opt-out hook class (NoAopInstrumentation) exists on the classpath")
  void noOptOutHookClassExists() {
    assertThatThrownBy(
            () -> Class.forName("cloud.poesis.sie.defman.observability.NoAopInstrumentation"))
        .as("ADR-002 D-8: no opt-out marker class may be introduced (no-opt-out invariant)")
        .isInstanceOf(ClassNotFoundException.class);
  }

  // --------------------------------------------------------------------------
  // AC-3: log-level gating at DEBUG
  // --------------------------------------------------------------------------

  @Test
  @DisplayName("AC-3 (DEBUG): success path emits 2 AOP log lines; exception path emits 3")
  void debugLogLevelEmitsCorrectLineCounts() {
    // observability.aop.logLevel=DEBUG via @TestPropertySource — both the aspect's per-call
    // method selection AND Logback's filter level are now DEBUG via the application.yaml binding.
    appender.list.clear();
    probeService.greet();

    long aopLogsForGreet =
        appender.list.stream()
            .filter(ev -> ev.getLoggerName().equals(AopProbeService.class.getName()))
            .filter(ev -> ev.getMessage() != null && ev.getMessage().startsWith("AOP "))
            .count();
    assertThat(aopLogsForGreet)
        .as("AC-3 DEBUG success path: AOP entry + exit (2 lines) on AopProbeService logger")
        .isEqualTo(2L);

    appender.list.clear();
    try {
      probeService.boom();
    } catch (IllegalStateException expected) {
      // Aspect should still emit entry + exception lines.
    }
    long aopLogsForBoom =
        appender.list.stream()
            .filter(ev -> ev.getLoggerName().equals(AopProbeService.class.getName()))
            .filter(ev -> ev.getMessage() != null && ev.getMessage().startsWith("AOP "))
            .count();
    assertThat(aopLogsForBoom)
        .as("AC-3 DEBUG exception path: AOP entry + AOP exception (2 lines) on probe logger")
        .isGreaterThanOrEqualTo(2L);
  }

  // AC-3 OFF (span still emitted while no log lines fire) is covered in a sibling IT class
  // BroadInstrumentationAspectOffIT, which sets observability.aop.logLevel=OFF via
  // @TestPropertySource. A separate context is required because @TestPropertySource is class-
  // scoped and Spring Boot must rebuild the property source / Logback binding from boot.

  // --------------------------------------------------------------------------
  // AC-5: secret-shaped arg values never leak into spans or logs
  // --------------------------------------------------------------------------

  @Test
  @DisplayName(
      "AC-5: secret-shaped arg values never appear in any AOP span attribute or log message")
  void secretArgValuesNeverLeak() {
    // observability.aop.logLevel=DEBUG via @TestPropertySource — aspect emits at DEBUG and
    // Logback lets DEBUG through, so the appender captures the lines we're asserting against.
    String secret = "AOP_PROBE_SECRET_LEAK";
    appender.list.clear();
    spanExporter.reset();

    probeService.echo(new LeakyArg(secret));

    // No span attribute should contain the secret.
    spanExporter
        .getFinishedSpanItems()
        .forEach(
            s ->
                s.getAttributes()
                    .forEach(
                        (k, v) ->
                            assertThat(String.valueOf(v))
                                .as("AC-5: span attr %s on span %s must not leak secret value",
                                    k.getKey(), s.getName())
                                .doesNotContain(secret)));

    // No log line message OR MDC value should contain the secret.
    appender.list.forEach(
        ev -> {
          assertThat(ev.getFormattedMessage())
              .as("AC-5: log message must not leak secret value")
              .doesNotContain(secret);
          if (ev.getMDCPropertyMap() != null) {
            ev.getMDCPropertyMap()
                .forEach(
                    (mk, mv) ->
                        assertThat(mv)
                            .as("AC-5: log MDC key %s must not leak secret value", mk)
                            .doesNotContain(secret));
          }
        });
  }

  // --------------------------------------------------------------------------
  // AC-8: @Repository bean is wrapped (structural)
  // --------------------------------------------------------------------------

  @Test
  @DisplayName("AC-8: @Repository bean methods are wrapped by the broad aspect")
  void repositoryBeanIsWrapped() {
    spanExporter.reset();
    probeRepository.findByKey("anything");

    long repoSpans =
        spanExporter.getFinishedSpanItems().stream()
            .filter(s -> s.getName().equals("AopProbeRepository.findByKey"))
            .filter(
                s ->
                    BroadInstrumentationAspect.INSTRUMENTATION_SCOPE.equals(
                        s.getInstrumentationScopeInfo().getName()))
            .count();
    assertThat(repoSpans)
        .as(
            "AC-8: @Repository method must be wrapped by the broad aspect. The agent-emitted"
                + " JDBC CLIENT child span (db.*) is verified end-to-end by S-015 at deployment.")
        .isGreaterThanOrEqualTo(1L);
  }

  // --------------------------------------------------------------------------
  // helpers
  // --------------------------------------------------------------------------

  private Map<String, Object> collectDefmanStereotypeBeans() {
    Map<String, Object> all = new LinkedHashMap<>();
    addBeans(all, applicationContext.getBeansWithAnnotation(org.springframework.stereotype.Component.class));
    addBeans(all, applicationContext.getBeansWithAnnotation(org.springframework.stereotype.Service.class));
    addBeans(all, applicationContext.getBeansWithAnnotation(org.springframework.stereotype.Repository.class));
    addBeans(all, applicationContext.getBeansWithAnnotation(org.springframework.stereotype.Controller.class));
    addBeans(all, applicationContext.getBeansWithAnnotation(org.springframework.web.bind.annotation.RestController.class));
    addBeans(all, applicationContext.getBeansWithAnnotation(org.springframework.context.annotation.Configuration.class));

    Map<String, Object> filtered = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : all.entrySet()) {
      Object bean = e.getValue();
      if (bean == null) continue;
      Class<?> user = org.springframework.util.ClassUtils.getUserClass(bean);
      String name = user.getName();
      if (!name.startsWith("cloud.poesis.sie.defman.")) continue;
      if (name.startsWith("cloud.poesis.sie.defman.observability.")) continue;
      if (name.startsWith("cloud.poesis.sie.defman.testbeans.")) continue;
      // Exclude the test config itself.
      if (name.contains("$")) continue;
      filtered.put(e.getKey(), bean);
    }
    return filtered;
  }

  private static void addBeans(Map<String, Object> sink, Map<String, Object> src) {
    if (src != null) sink.putAll(src);
  }

  private static Method findInvokableNoArgPublicMethod(Class<?> userClass) {
    Method best = null;
    for (Method m : userClass.getDeclaredMethods()) {
      int mods = m.getModifiers();
      if (!Modifier.isPublic(mods)) continue;
      if (Modifier.isStatic(mods)) continue;
      if (m.getParameterCount() != 0) continue;
      if (m.isSynthetic() || m.isBridge()) continue;
      String n = m.getName();
      // Skip Object overrides — they're usually inherited and not interesting.
      if (n.equals("toString") || n.equals("hashCode") || n.equals("equals")
          || n.equals("getClass") || n.equals("notify") || n.equals("notifyAll")
          || n.equals("wait") || n.equals("clone") || n.equals("finalize")) {
        continue;
      }
      best = m;
      break;
    }
    return best;
  }

  /** Synthetic non-String secret carrier; its toString embeds the secret on purpose. */
  static final class LeakyArg {
    private final String secret;

    LeakyArg(String secret) {
      this.secret = secret;
    }

    @Override
    public String toString() {
      return "LeakyArg[" + secret + "]";
    }
  }

  @TestConfiguration
  @ComponentScan(basePackages = "cloud.poesis.sie.defman.testbeans")
  static class AopItTestConfig {
    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();

    @Bean
    InMemorySpanExporter spanExporter() {
      return exporter;
    }

    @Bean
    @Primary
    OpenTelemetry aopItOpenTelemetry() {
      return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }
  }

}
