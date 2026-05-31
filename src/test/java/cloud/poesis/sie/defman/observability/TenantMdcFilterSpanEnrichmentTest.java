package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import jakarta.servlet.FilterChain;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Span-enrichment tests for {@link TenantMdcFilter} (AC-3).
 *
 * <p>Uses {@link OpenTelemetryExtension} to capture spans into an in-memory exporter, then runs the
 * filter inside a manually-started parent span (the OTel Java agent would normally provide this
 * from the Servlet-container layer; in this unit test we synthesise it).
 *
 * <p>The "no active span" branch is covered by a separate test that runs the filter without any
 * {@code Scope} open, asserting it neither throws nor leaks attributes.
 */
class TenantMdcFilterSpanEnrichmentTest {

  @RegisterExtension static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

  @Test
  @DisplayName("AC-3: active span receives gsm.tenant.id and sie.component attributes")
  void activeSpanEnrichedWithTenantAndComponent() throws Exception {
    TenantMdcFilter filter = new TenantMdcFilter();
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader(TenantMdcFilter.HEADER_TENANT_ID, "acme-corp");

    Tracer tracer = otel.getOpenTelemetry().getTracer("test");
    Span server = tracer.spanBuilder("inbound").startSpan();
    try (Scope ignored = server.makeCurrent()) {
      filter.doFilter(req, new MockHttpServletResponse(), noopChain());
    } finally {
      server.end();
    }

    SpanData captured = otel.getSpans().get(0);
    assertThat(captured.getAttributes().get(AttributeKey.stringKey("gsm.tenant.id")))
        .isEqualTo("acme-corp");
    assertThat(captured.getAttributes().get(AttributeKey.stringKey("sie.component")))
        .isEqualTo("definition-manager");
    MDC.clear();
  }

  @Test
  @DisplayName("AC-3: missing header → span attribute is 'unknown'")
  void missingHeaderProducesUnknownAttributeOnSpan() throws Exception {
    TenantMdcFilter filter = new TenantMdcFilter();
    MockHttpServletRequest req = new MockHttpServletRequest();

    Tracer tracer = otel.getOpenTelemetry().getTracer("test");
    Span server = tracer.spanBuilder("inbound-missing").startSpan();
    try (Scope ignored = server.makeCurrent()) {
      filter.doFilter(req, new MockHttpServletResponse(), noopChain());
    } finally {
      server.end();
    }

    SpanData captured =
        otel.getSpans().stream()
            .filter(s -> s.getName().equals("inbound-missing"))
            .findFirst()
            .orElseThrow();
    assertThat(captured.getAttributes().get(AttributeKey.stringKey("gsm.tenant.id")))
        .isEqualTo("unknown");
    assertThat(captured.getAttributes().get(AttributeKey.stringKey("sie.component")))
        .isEqualTo("definition-manager");
    MDC.clear();
  }

  @Test
  @DisplayName("AC-3: no active span → filter does not throw and still populates MDC")
  void noActiveSpan_filterDoesNotThrow() throws Exception {
    TenantMdcFilter filter = new TenantMdcFilter();
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader(TenantMdcFilter.HEADER_TENANT_ID, "acme");

    boolean[] sawTenant = {false};
    FilterChain chain =
        (r, s) -> {
          sawTenant[0] = "acme".equals(MDC.get(TenantMdcFilter.MDC_TENANT_ID));
        };

    filter.doFilter(req, new MockHttpServletResponse(), chain);

    assertThat(sawTenant[0]).isTrue();
    MDC.clear();
  }

  private static FilterChain noopChain() {
    return (r, s) -> {
      // intentionally empty — span enrichment happens before chain.doFilter.
    };
  }

  @Test
  @DisplayName("AC-3: span.setAttribute throws → request not broken, MDC restored to prior values")
  void spanSetAttributeThrows_doesNotBreakRequestNorLeakMdc() throws Exception {
    // Pre-seed MDC with sentinel values so we can verify exact restoration.
    MDC.put(TenantMdcFilter.MDC_TENANT_ID, "prior-tenant");
    MDC.put(TenantMdcFilter.MDC_COMPONENT, "prior-component");

    // Build a valid SpanContext so enrichActiveSpan does not short-circuit on isValid().
    SpanContext validCtx =
        SpanContext.create(
            "00000000000000000000000000000001",
            "0000000000000002",
            TraceFlags.getSampled(),
            TraceState.getDefault());
    Span throwingSpan = new ThrowingSetAttributeSpan(validCtx);

    TenantMdcFilter filter = new TenantMdcFilter();
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader(TenantMdcFilter.HEADER_TENANT_ID, "acme");

    int[] chainInvocations = {0};
    FilterChain chain = (r, s) -> chainInvocations[0]++;

    try (Scope ignored = throwingSpan.makeCurrent()) {
      assertThatCode(() -> filter.doFilter(req, new MockHttpServletResponse(), chain))
          .doesNotThrowAnyException();
    }

    assertThat(chainInvocations[0]).as("chain.doFilter must be invoked exactly once").isEqualTo(1);
    assertThat(MDC.get(TenantMdcFilter.MDC_TENANT_ID))
        .as("prior MDC tenant must be restored, not leaked")
        .isEqualTo("prior-tenant");
    assertThat(MDC.get(TenantMdcFilter.MDC_COMPONENT))
        .as("prior MDC component must be restored, not leaked")
        .isEqualTo("prior-component");

    MDC.clear();
  }

  /**
   * Hand-rolled {@link Span} that exposes a valid {@link SpanContext} (so {@code
   * Span.current().getSpanContext().isValid()} returns true) but throws {@link RuntimeException}
   * from {@code setAttribute(AttributeKey, T)} — which the {@code setAttribute(String, String)}
   * default delegates to. Used to simulate a misbehaving span implementation or OTel SDK bug.
   */
  private static final class ThrowingSetAttributeSpan implements Span {
    private final SpanContext ctx;

    ThrowingSetAttributeSpan(SpanContext ctx) {
      this.ctx = ctx;
    }

    @Override
    public <T> Span setAttribute(AttributeKey<T> key, T value) {
      throw new RuntimeException("simulated misbehaving span: setAttribute boom");
    }

    @Override
    public Span addEvent(String name, Attributes attributes) {
      return this;
    }

    @Override
    public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
      return this;
    }

    @Override
    public Span setStatus(StatusCode statusCode, String description) {
      return this;
    }

    @Override
    public Span recordException(Throwable exception, Attributes additionalAttributes) {
      return this;
    }

    @Override
    public Span updateName(String name) {
      return this;
    }

    @Override
    public void end() {
      // no-op
    }

    @Override
    public void end(long timestamp, TimeUnit unit) {
      // no-op
    }

    @Override
    public SpanContext getSpanContext() {
      return ctx;
    }

    @Override
    public boolean isRecording() {
      return true;
    }
  }
}
