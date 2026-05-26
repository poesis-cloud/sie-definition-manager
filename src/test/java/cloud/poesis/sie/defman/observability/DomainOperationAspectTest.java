package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/**
 * Unit tests for {@link DomainOperationAspect}.
 *
 * <p><strong>Coverage:</strong>
 *
 * <ul>
 *   <li>AC-8: Annotated methods create INTERNAL spans with correct name and attributes.
 *   <li>AC-9: Unannotated methods do not create custom domain spans.
 *   <li>Exception handling: Span marked error when method throws.
 * </ul>
 *
 * <p><strong>Story:</strong> S-004
 */
class DomainOperationAspectTest {

  @RegisterExtension
  static final OpenTelemetryExtension otelTesting = OpenTelemetryExtension.create();

  private Tracer tracer;
  private DomainOperationAspect aspect;

  @BeforeEach
  void setUp() {
    tracer = otelTesting.getOpenTelemetry().getTracer("test-tracer");
    aspect = new DomainOperationAspect(tracer);
  }

  @Test
  void annotatedMethodCreatesSpanWithCorrectNameAndAttributes() {
    // Given: A stub service with @DomainOperation annotation
    StubService stubService = createProxiedStubService();

    // When: The annotated method is called within a parent span
    Span parentSpan = tracer.spanBuilder("parent-span").startSpan();
    try (Scope scope = parentSpan.makeCurrent()) {
      String result = stubService.annotatedOperation("test-input");

      // Then: The result is correct
      assertThat(result).isEqualTo("annotated-result");
    } finally {
      parentSpan.end();
    }

    // Then: Two spans were created (parent + domain operation)
    List<SpanData> spans = otelTesting.getSpans();
    assertThat(spans).hasSize(2);

    // Then: The domain operation span has correct name, kind, and attributes
    SpanData domainSpan =
        spans.stream()
            .filter(s -> s.getName().equals("gsm.test.operation"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Domain span not found"));

    assertThat(domainSpan.getKind()).isEqualTo(SpanKind.INTERNAL);
    assertThat(
            domainSpan
                .getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("code.namespace")))
        .isEqualTo(StubService.class.getName());
    assertThat(
            domainSpan
                .getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("code.function")))
        .isEqualTo("annotatedOperation");
  }

  @Test
  void unannotatedMethodDoesNotCreateSpan() {
    // Given: A stub service with an unannotated method
    StubService stubService = createProxiedStubService();

    // When: The unannotated method is called within a parent span
    Span parentSpan = tracer.spanBuilder("parent-span").startSpan();
    try (Scope scope = parentSpan.makeCurrent()) {
      String result = stubService.unannotatedOperation("test-input");

      // Then: The result is correct
      assertThat(result).isEqualTo("unannotated-result");
    } finally {
      parentSpan.end();
    }

    // Then: Only the parent span was created (no custom domain span)
    List<SpanData> spans = otelTesting.getSpans();
    assertThat(spans).hasSize(1);
    assertThat(spans.get(0).getName()).isEqualTo("parent-span");
  }

  @Test
  void annotatedMethodRecordsExceptionWhenMethodThrows() {
    // Given: A stub service with an annotated method that throws
    StubService stubService = createProxiedStubService();

    // When/Then: The annotated method throws and the exception propagates
    assertThatThrownBy(() -> stubService.throwingOperation())
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Expected test exception");

    // Then: A span was created and marked as error
    List<SpanData> spans = otelTesting.getSpans();
    assertThat(spans).hasSize(1);

    SpanData domainSpan = spans.get(0);
    assertThat(domainSpan.getName()).isEqualTo("gsm.test.throwing");
    assertThat(domainSpan.getStatus().getStatusCode())
        .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
    assertThat(domainSpan.getStatus().getDescription()).isEqualTo("Expected test exception");
    assertThat(domainSpan.getEvents()).isNotEmpty();
    assertThat(domainSpan.getEvents().get(0).getName()).isEqualTo("exception");
  }

  private StubService createProxiedStubService() {
    StubService target = new StubService();
    AspectJProxyFactory factory = new AspectJProxyFactory(target);
    factory.addAspect(aspect);
    return factory.getProxy();
  }

  /** Stub service for testing aspect behavior. */
  static class StubService {

    @DomainOperation("gsm.test.operation")
    public String annotatedOperation(String input) {
      return "annotated-result";
    }

    public String unannotatedOperation(String input) {
      return "unannotated-result";
    }

    @DomainOperation("gsm.test.throwing")
    public void throwingOperation() {
      throw new RuntimeException("Expected test exception");
    }
  }
}
