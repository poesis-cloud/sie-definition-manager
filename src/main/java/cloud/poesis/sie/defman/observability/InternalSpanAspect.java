package cloud.poesis.sie.defman.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

/**
 * Spring AOP aspect that intercepts {@link InternalSpan}-annotated methods and creates
 * OpenTelemetry INTERNAL spans.
 *
 * <p><strong>Behavior:</strong>
 *
 * <ul>
 *   <li>Creates an INTERNAL span named by the {@code @InternalSpan} value.
 *   <li>Captures {@code code.function} and {@code code.namespace} per OTel semantic conventions.
 *   <li>Closes the span on method completion (success or exception).
 *   <li>Marks the span as error if the method throws an exception.
 *   <li>No-op for unannotated methods (AC-9).
 * </ul>
 *
 * <p><strong>Story:</strong> S-004
 *
 * <p><strong>Package boundary:</strong> {@code cloud.poesis.sie.defman.observability.*} per ADR-001
 * D-5.
 */
@Aspect
@Component
public class InternalSpanAspect {

  private final Tracer tracer;

  public InternalSpanAspect(OpenTelemetry openTelemetry) {
    this.tracer = openTelemetry.getTracer("cloud.poesis.sie.defman");
  }

  /**
   * Intercepts methods annotated with {@code @InternalSpan} and creates an INTERNAL span.
   *
   * @param joinPoint the join point representing the annotated method
   * @return the result of the intercepted method
   * @throws Throwable if the intercepted method throws
   */
  @Around("@annotation(cloud.poesis.sie.defman.observability.InternalSpan)")
  public Object aroundInternalSpan(ProceedingJoinPoint joinPoint) throws Throwable {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    Method method = signature.getMethod();

    // Use AnnotationUtils for robust annotation resolution across proxies
    InternalSpan annotation = AnnotationUtils.findAnnotation(method, InternalSpan.class);

    if (annotation == null) {
      // Should not happen due to pointcut, but defensive fallback
      return joinPoint.proceed();
    }

    String spanName = annotation.value();
    // Derive code.namespace from actual target class, not interface/proxy
    String codeNamespace = joinPoint.getTarget().getClass().getName();
    String codeFunction = method.getName();

    Span span =
        tracer
            .spanBuilder(spanName)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("code.namespace", codeNamespace)
            .setAttribute("code.function", codeFunction)
            .setParent(Context.current())
            .startSpan();

    try (Scope scope = span.makeCurrent()) {
      return joinPoint.proceed();
    } catch (Throwable throwable) {
      span.recordException(throwable);
      span.setStatus(StatusCode.ERROR, throwable.getMessage());
      throw throwable;
    } finally {
      span.end();
    }
  }
}
