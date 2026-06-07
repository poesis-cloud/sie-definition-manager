package cloud.poesis.sie.defman.observability;

import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Broad AOP instrumentation per ADR-002 D-8: wraps every method of every Spring stereotype bean
 * (@Component / @Service / @Repository / @Controller / @RestController / @Configuration) declared
 * under {@code cloud.poesis.sie.defman.**} with one INTERNAL OTel span and three structured SLF4J
 * log lines (entry, exit, exception).
 *
 * <p><strong>Self-exclusion</strong>: this aspect lives inside {@code
 * cloud.poesis.sie.defman.observability} which is itself under the instrumented package. To prevent
 * infinite advice recursion the pointcut explicitly excludes {@code
 * cloud.poesis.sie.defman.observability..*}. This is the ONLY exclusion permitted by ADR-002; no
 * other opt-out mechanism exists.
 *
 * <p>Per ADR-001 D-3 the MDC keys {@code gsm.tenant.id} and {@code sie.component} (if present) are
 * mirrored as span attributes. Per ADR-001 D-7 the {@code sie.aop.args.summary} attribute is capped
 * at 16 KiB by {@link ArgsSummary}.
 */
@Aspect
@Component
public class BroadInstrumentationAspect {

  static final String INSTRUMENTATION_SCOPE = "cloud.poesis.sie.defman.observability.aop";

  private static final Logger SELF_LOG = LoggerFactory.getLogger(BroadInstrumentationAspect.class);

  private final Tracer tracer;

  // Span attribute keys
  private static final String ATTR_CODE_NAMESPACE = "code.namespace";
  private static final String ATTR_CODE_FUNCTION = "code.function";
  private static final String ATTR_ARGS_SUMMARY = "sie.aop.args.summary";
  private static final String ATTR_DURATION_MS = "sie.aop.duration_ms";

  // MDC keys (per ADR-001 D-3 and structured log contract)
  private static final String MDC_TENANT_ID = "gsm.tenant.id";
  private static final String MDC_COMPONENT = "sie.component";
  private static final String MDC_CODE_FUNCTION = "code.function";
  private static final String MDC_ARGS_SUMMARY = "sie.aop.args.summary";
  private static final String MDC_DURATION_MS = "sie.aop.duration_ms";
  private static final String MDC_EXCEPTION_TYPE = "exception.type";
  private static final String MDC_EXCEPTION_MESSAGE = "exception.message";
  private static final String MDC_PAYLOAD = "sie.payload";
  private static final String MDC_PAYLOAD_SAMPLED = "sie.payload.sampled";

  private static final String PAYLOAD_LOG_LINE = "AOP payload";
  private static final String PAYLOAD_SAMPLED_LOG_LINE = "AOP payload sampled";

  /**
   * Configured log level for AOP entry/exit/exception lines (default INFO). Non-final so the
   * integration test ({@code BroadInstrumentationAspectIT}) can flip it via reflection to exercise
   * the DEBUG / OFF gating branches without restarting the Spring context for each level. Prod code
   * MUST NOT mutate this field — it is set once by the constructor.
   */
  private volatile String logLevel;

  private final int payloadCapBytes;
  private final double payloadBypassRate;
  private final DoubleSupplier payloadSampleSupplier;

  @Autowired
  public BroadInstrumentationAspect(
      OpenTelemetry openTelemetry,
      @Value("${observability.aop.logLevel:INFO}") String logLevel,
      @Value("${observability.payloadCapBytes:16384}") int payloadCapBytes,
      @Value("${observability.payloadBypassRate:0.0}") double payloadBypassRate) {
    this(
        openTelemetry,
        logLevel,
        payloadCapBytes,
        payloadBypassRate,
        () -> ThreadLocalRandom.current().nextDouble());
  }

  BroadInstrumentationAspect(OpenTelemetry openTelemetry, String logLevel) {
    this(openTelemetry, logLevel, 16 * 1024, 0.0d, () -> ThreadLocalRandom.current().nextDouble());
  }

  BroadInstrumentationAspect(
      OpenTelemetry openTelemetry,
      String logLevel,
      int payloadCapBytes,
      double payloadBypassRate,
      DoubleSupplier payloadSampleSupplier) {
    this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE, "1");
    this.logLevel = normaliseLevel(logLevel);
    this.payloadCapBytes = payloadCapBytes;
    this.payloadBypassRate = payloadBypassRate;
    this.payloadSampleSupplier = payloadSampleSupplier;
  }

  private static String normaliseLevel(String raw) {
    if (raw == null) {
      return "INFO";
    }
    String upper = raw.trim().toUpperCase(Locale.ROOT);
    switch (upper) {
      case "TRACE":
      case "DEBUG":
      case "INFO":
      case "WARN":
      case "ERROR":
      case "OFF":
        return upper;
      default:
        SELF_LOG.warn("Unsupported observability.aop.logLevel '{}' — falling back to INFO", raw);
        return "INFO";
    }
  }

  @Pointcut("within(cloud.poesis.sie.defman..*)")
  public void inDefmanPackage() {}

  @Pointcut("!within(cloud.poesis.sie.defman.observability..*)")
  public void notInObservabilityPackage() {}

  @Pointcut(
      "@within(org.springframework.stereotype.Component) "
          + "|| @within(org.springframework.stereotype.Service) "
          + "|| @within(org.springframework.stereotype.Repository) "
          + "|| @within(org.springframework.stereotype.Controller) "
          + "|| @within(org.springframework.web.bind.annotation.RestController) "
          + "|| @within(org.springframework.context.annotation.Configuration)")
  public void inAnyStereotype() {}

  @Around("inDefmanPackage() && notInObservabilityPackage() && inAnyStereotype()")
  public Object aroundDefmanStereotypeMethod(ProceedingJoinPoint pjp) throws Throwable {
    Object target = pjp.getTarget();
    Class<?> userClass =
        target != null ? ClassUtils.getUserClass(target) : pjp.getSignature().getDeclaringType();
    String className = userClass.getName();
    String methodName = pjp.getSignature().getName();
    String spanName = userClass.getSimpleName() + "." + methodName;
    String argsSummary = ArgsSummary.summarize(pjp.getArgs());

    Logger targetLog = LoggerFactory.getLogger(className);

    Span span =
        tracer
            .spanBuilder(spanName)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_CODE_NAMESPACE, className)
            .setAttribute(ATTR_CODE_FUNCTION, methodName)
            .setAttribute(ATTR_ARGS_SUMMARY, argsSummary)
            .startSpan();

    // Mirror MDC values onto the span per ADR-001 D-3.
    String tenantId = MDC.get(MDC_TENANT_ID);
    if (tenantId != null) {
      span.setAttribute(MDC_TENANT_ID, tenantId);
    }
    String component = MDC.get(MDC_COMPONENT);
    if (component != null) {
      span.setAttribute(MDC_COMPONENT, component);
    }

    long startNs = System.nanoTime();
    try (Scope ignored = span.makeCurrent()) {
      PayloadLogEmission payloadEmission =
          buildPayloadEmission(className, methodName, pjp.getArgs());
      if (payloadEmission != null) {
        MDC.put(MDC_PAYLOAD, payloadEmission.boundedPayload());
        try {
          logAtLevel(targetLog, PAYLOAD_LOG_LINE, null);
        } finally {
          MDC.remove(MDC_PAYLOAD);
        }

        if (payloadEmission.sampledPayload() != null) {
          MDC.put(MDC_PAYLOAD, payloadEmission.sampledPayload());
          MDC.put(MDC_PAYLOAD_SAMPLED, "true");
          try {
            logAtLevel(targetLog, PAYLOAD_SAMPLED_LOG_LINE, null);
          } finally {
            MDC.remove(MDC_PAYLOAD);
            MDC.remove(MDC_PAYLOAD_SAMPLED);
          }
        }
      }

      // ENTRY log
      MDC.put(MDC_CODE_FUNCTION, methodName);
      MDC.put(MDC_ARGS_SUMMARY, argsSummary);
      try {
        logAtLevel(targetLog, "AOP entry", null);
      } finally {
        MDC.remove(MDC_CODE_FUNCTION);
        MDC.remove(MDC_ARGS_SUMMARY);
      }

      Object result;
      try {
        result = pjp.proceed();
      } catch (Throwable ex) {
        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        span.setAttribute(ATTR_DURATION_MS, durationMs);
        span.recordException(ex);
        span.setStatus(StatusCode.ERROR, ex.getClass().getSimpleName());

        MDC.put(MDC_CODE_FUNCTION, methodName);
        MDC.put(MDC_DURATION_MS, Long.toString(durationMs));
        MDC.put(MDC_EXCEPTION_TYPE, ex.getClass().getName());
        MDC.put(MDC_EXCEPTION_MESSAGE, String.valueOf(ex.getMessage()));
        try {
          logAtLevel(targetLog, "AOP exception", ex);
        } finally {
          MDC.remove(MDC_CODE_FUNCTION);
          MDC.remove(MDC_DURATION_MS);
          MDC.remove(MDC_EXCEPTION_TYPE);
          MDC.remove(MDC_EXCEPTION_MESSAGE);
        }
        throw ex;
      }

      long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
      span.setAttribute(ATTR_DURATION_MS, durationMs);

      // EXIT log
      MDC.put(MDC_CODE_FUNCTION, methodName);
      MDC.put(MDC_DURATION_MS, Long.toString(durationMs));
      try {
        logAtLevel(targetLog, "AOP exit", null);
      } finally {
        MDC.remove(MDC_CODE_FUNCTION);
        MDC.remove(MDC_DURATION_MS);
      }

      return result;
    } finally {
      span.end();
    }
  }

  private PayloadLogEmission buildPayloadEmission(
      String className, String methodName, Object[] args) {
    if (!isDefinitionOrAscriptionContext(className)) {
      return null;
    }

    String payload = extractPayload(args);
    if (payload == null || payload.isBlank()) {
      return null;
    }

    String id = extractId(args);
    String operation = className + "." + methodName;
    String bounded =
        PayloadLogHelper.boundedPayloadWithSpanSummary(operation, id, payload, payloadCapBytes);
    // Non-positive caps are fail-safe mode; they should not enable raw-payload bypass.
    boolean truncated = payloadCapBytes > 0 && PayloadLogHelper.utf8Bytes(payload) > payloadCapBytes;
    if (!truncated) {
      return new PayloadLogEmission(bounded, null);
    }

    double sample = payloadSampleSupplier.getAsDouble();
    if (PayloadLogHelper.shouldBypassPayloadCap(payloadBypassRate, sample)) {
      return new PayloadLogEmission(bounded, payload);
    }
    return new PayloadLogEmission(bounded, null);
  }

  private static boolean isDefinitionOrAscriptionContext(String className) {
    return className.contains("Definition") || className.contains("Ascription");
  }

  private static String extractPayload(Object[] args) {
    if (args == null) {
      return null;
    }
    for (Object arg : args) {
      String payload = coercePayload(arg);
      if (payload != null) {
        return payload;
      }
    }
    return null;
  }

  private static String coercePayload(Object arg) {
    if (arg == null) {
      return null;
    }
    if (arg instanceof JsonNode node) {
      return node.toString();
    }

    try {
      Method getter = arg.getClass().getMethod("getStatement");
      if (getter.getParameterCount() == 0) {
        Object value = getter.invoke(arg);
        if (value instanceof JsonNode node) {
          return node.toString();
        }
      }
    } catch (ReflectiveOperationException ignored) {
      // no statement payload on this argument type
    }

    return null;
  }

  private static String extractId(Object[] args) {
    if (args == null) {
      return "unknown";
    }
    for (Object arg : args) {
      if (arg instanceof UUID uuid) {
        return uuid.toString();
      }
      if (arg == null) {
        continue;
      }
      try {
        Method getter = arg.getClass().getMethod("getDefinitionId");
        if (getter.getParameterCount() == 0) {
          Object value = getter.invoke(arg);
          if (value instanceof UUID uuid) {
            return uuid.toString();
          }
        }
      } catch (ReflectiveOperationException ignored) {
        // no definition id on this argument type
      }
      try {
        Method getter = arg.getClass().getMethod("getId");
        if (getter.getParameterCount() == 0) {
          Object value = getter.invoke(arg);
          if (value instanceof UUID uuid) {
            return uuid.toString();
          }
        }
      } catch (ReflectiveOperationException ignored) {
        // no id on this argument type
      }
    }
    return "unknown";
  }

  private record PayloadLogEmission(String boundedPayload, String sampledPayload) {}

  /**
   * Single dispatch point for the configured log level. Entry/exit pass {@code thrown=null}; the
   * exception path passes the throwable so Logback's JSON encoder can render it natively (never
   * concatenated into the message).
   */
  private void logAtLevel(Logger log, String msg, Throwable thrown) {
    switch (logLevel) {
      case "TRACE":
        if (thrown != null) {
          log.trace(msg, thrown);
        } else {
          log.trace(msg);
        }
        break;
      case "DEBUG":
        if (thrown != null) {
          log.debug(msg, thrown);
        } else {
          log.debug(msg);
        }
        break;
      case "WARN":
        if (thrown != null) {
          log.warn(msg, thrown);
        } else {
          log.warn(msg);
        }
        break;
      case "ERROR":
        if (thrown != null) {
          log.error(msg, thrown);
        } else {
          log.error(msg);
        }
        break;
      case "OFF":
        // no-op
        break;
      case "INFO":
      default:
        if (thrown != null) {
          log.info(msg, thrown);
        } else {
          log.info(msg);
        }
        break;
    }
  }
}
