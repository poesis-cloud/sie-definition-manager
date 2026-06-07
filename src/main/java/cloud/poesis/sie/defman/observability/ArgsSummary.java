package cloud.poesis.sie.defman.observability;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Renders a stable, single-line, value-free summary of method arguments for use as the {@code
 * sie.aop.args.summary} span attribute and as part of structured log lines emitted by {@code
 * BroadInstrumentationAspect}.
 *
 * <p>The output carries argument types and sizes only. Per ADR-001 D-7 the final string is capped
 * at 16 KiB ({@value #MAX_SUMMARY_BYTES} UTF-8 bytes) with a deterministic truncation marker. To
 * enforce AC-5 (no value leakage), this helper MUST NEVER stringify any argument value, MUST NEVER
 * invoke any value-rendering method on an argument, and MUST NEVER reflectively read any argument
 * field.
 */
public final class ArgsSummary {

  /** Maximum UTF-8 byte length of the rendered summary string (16 KiB per ADR-001 D-7). */
  public static final int MAX_SUMMARY_BYTES = 16 * 1024;

  private ArgsSummary() {
    // utility
  }

  /**
   * Render the given args array as a single-line, value-free summary string.
   *
   * @param args method arguments captured by AOP advice; may be {@code null}
   * @return summary string of the form {@code "(<arg1>, <arg2>, ...)"}, capped at {@link
  *     #MAX_SUMMARY_BYTES} UTF-8 bytes with a deterministic marker when the cap is exceeded
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
    return capUtf8(sb);
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

  static String truncationSuffix(int originalBytes) {
    return "<TRUNCATED:" + originalBytes + "_bytes>";
  }

  private static String capUtf8(StringBuilder sb) {
    String rendered = sb.substring(0, sb.length());
    int originalBytes = utf8Bytes(rendered);
    if (originalBytes <= MAX_SUMMARY_BYTES) {
      return rendered;
    }

    String suffix = truncationSuffix(originalBytes);
    int suffixBytes = utf8Bytes(suffix);
    if (suffixBytes >= MAX_SUMMARY_BYTES) {
      return truncateUtf8(suffix, MAX_SUMMARY_BYTES);
    }

    int budget = MAX_SUMMARY_BYTES - suffixBytes;
    return truncateUtf8(rendered, budget) + suffix;
  }

  private static String truncateUtf8(String value, int maxBytes) {
    if (maxBytes <= 0 || value.isEmpty()) {
      return "";
    }

    StringBuilder out = new StringBuilder(value.length());
    int bytes = 0;
    for (int i = 0; i < value.length(); ) {
      int cp = value.codePointAt(i);
      int charCount = Character.charCount(cp);
      String chunk = value.substring(i, Math.min(value.length(), i + charCount));
      int chunkBytes = utf8Bytes(chunk);
      if (bytes + chunkBytes > maxBytes) {
        break;
      }
      out.append(chunk);
      bytes += chunkBytes;
      i += charCount;
    }
    return out.substring(0, out.length());
  }

  private static int utf8Bytes(String value) {
    if (value == null) {
      return 0;
    }
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  @Deprecated(forRemoval = false)
  static int maxSummaryCharsForCompat() {
    // Retained only for tests/migrations that still check old naming.
    return MAX_SUMMARY_BYTES;
  }

  private static String cap(StringBuilder sb) {
    // Legacy delegator kept to avoid touching call sites during transition.
    // New logic is byte-based via capUtf8.
    String rendered = sb.substring(0, sb.length());
    if (utf8Bytes(rendered) <= MAX_SUMMARY_BYTES) {
      return sb.substring(0, sb.length());
    }
    return capUtf8(sb);
  }
}
