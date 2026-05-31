package cloud.poesis.sie.defman.observability;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Renders a stable, single-line, value-free summary of method arguments for use as the {@code
 * sie.aop.args.summary} span attribute and as part of structured log lines emitted by {@code
 * BroadInstrumentationAspect}.
 *
 * <p>The output carries argument types and sizes only. Per ADR-001 D-7 the final string is capped
 * at 16 KiB ({@value #MAX_SUMMARY_CHARS} characters) with a {@value #TRUNCATION_SUFFIX} suffix. To
 * enforce AC-5 (no value leakage), this helper MUST NEVER stringify any argument value, MUST NEVER
 * invoke any value-rendering method on an argument, and MUST NEVER reflectively read any argument
 * field.
 */
public final class ArgsSummary {

  /** Maximum length of the rendered summary string (16 KiB per ADR-001 D-7). */
  public static final int MAX_SUMMARY_CHARS = 16 * 1024;

  /** Truncation suffix appended when the rendered summary exceeds the cap. */
  public static final String TRUNCATION_SUFFIX = "\u2026[truncated]";

  private ArgsSummary() {
    // utility
  }

  /**
   * Render the given args array as a single-line, value-free summary string.
   *
   * @param args method arguments captured by AOP advice; may be {@code null}
   * @return summary string of the form {@code "(<arg1>, <arg2>, ...)"}, capped at {@link
   *     #MAX_SUMMARY_CHARS} with the {@link #TRUNCATION_SUFFIX} suffix when the cap is exceeded
   */
  public static String summarize(Object[] args) {
    if (args == null || args.length == 0) {
      return "()";
    }
    StringBuilder sb = new StringBuilder(64);
    sb.append('(');
    for (int i = 0; i < args.length; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(renderArg(args[i]));
    }
    sb.append(')');
    return cap(sb);
  }

  private static String renderArg(Object arg) {
    if (arg == null) {
      return "null";
    }
    Class<?> type = arg.getClass();
    if (arg instanceof String s) {
      return "String[len=" + s.length() + "]";
    }
    if (type.isArray()) {
      String component = type.getComponentType().getSimpleName();
      return component + "[size=" + Array.getLength(arg) + "]";
    }
    if (arg instanceof Collection<?> c) {
      return type.getSimpleName() + "<size=" + c.size() + ">";
    }
    if (arg instanceof Map<?, ?> m) {
      return type.getSimpleName() + "<size=" + m.size() + ">";
    }
    if (arg instanceof Optional<?> o) {
      return "Optional[present=" + o.isPresent() + "]";
    }
    if (type.isRecord()) {
      return type.getSimpleName() + "[components=" + type.getRecordComponents().length + "]";
    }
    return type.getSimpleName();
  }

  private static String cap(StringBuilder sb) {
    if (sb.length() <= MAX_SUMMARY_CHARS) {
      return sb.substring(0, sb.length());
    }
    int keep = MAX_SUMMARY_CHARS - TRUNCATION_SUFFIX.length();
    if (keep < 0) {
      keep = 0;
    }
    return sb.substring(0, keep) + TRUNCATION_SUFFIX;
  }
}
