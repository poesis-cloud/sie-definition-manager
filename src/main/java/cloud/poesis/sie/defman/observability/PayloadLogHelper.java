package cloud.poesis.sie.defman.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds bounded payload previews for structured logs while preserving UTF-8 byte semantics.
 *
 * <p>S-007 AC-1/AC-2 foundation: payloads under cap are emitted as-is; payloads over cap are
 * truncated and suffixed with {@code …<truncated:N bytes>} where {@code N} is the original UTF-8
 * byte length.
 */
public final class PayloadLogHelper {

  private static final String TRUNCATION_EVENT_NAME = "sie.payload.truncated.summary";

  private static final AttributeKey<String> ATTR_OPERATION = AttributeKey.stringKey("operation");
  private static final AttributeKey<String> ATTR_ID = AttributeKey.stringKey("id");
  private static final AttributeKey<Long> ATTR_ORIGINAL_BYTES =
      AttributeKey.longKey("original.byte_length");
  private static final AttributeKey<String> ATTR_TRUNCATION_MARKER =
      AttributeKey.stringKey("truncation.marker");
  private static final AttributeKey<String> ATTR_CAPPED_PREVIEW =
      AttributeKey.stringKey("capped.preview");

  private PayloadLogHelper() {
    // utility
  }

  /**
   * Returns the payload as-is when under cap, otherwise a capped preview plus explicit marker.
   *
   * @param payload input payload string
   * @param capBytes configured maximum UTF-8 bytes for emitted payload field
   * @return bounded payload representation
   */
  public static String boundedPayload(String payload, int capBytes) {
    if (payload == null) {
      return payload;
    }
    if (capBytes <= 0) {
      return "";
    }

    int originalBytes = utf8Bytes(payload);
    if (originalBytes <= capBytes) {
      return payload;
    }

    String marker = truncationMarker(originalBytes);
    int markerBytes = utf8Bytes(marker);
    if (markerBytes > capBytes) {
      return tinyCapMarker(capBytes);
    }

    int previewBudget = capBytes - markerBytes;
    return truncateUtf8(payload, previewBudget) + marker;
  }

  /**
   * Returns a bounded payload and, when truncation occurs under an active span, emits a capped
   * payload summary event on the current span.
   */
  public static String boundedPayloadWithSpanSummary(
      String operation, String id, String payload, int capBytes) {
    if (payload == null) {
      return null;
    }

    int originalBytes = utf8Bytes(payload);
    String bounded = boundedPayload(payload, capBytes);
    boolean truncated = originalBytes > capBytes;
    if (!truncated) {
      return bounded;
    }

    Span current = Span.current();
    if (!current.getSpanContext().isValid()) {
      return bounded;
    }

    current.addEvent(
        TRUNCATION_EVENT_NAME,
        Attributes.of(
            ATTR_OPERATION,
            operation == null ? "unknown" : operation,
            ATTR_ID,
            id == null ? "unknown" : id,
            ATTR_ORIGINAL_BYTES,
            (long) originalBytes,
            ATTR_TRUNCATION_MARKER,
            truncationMarker(originalBytes),
            ATTR_CAPPED_PREVIEW,
            bounded));
    return bounded;
  }

  /**
   * Applies deterministic payload-cap bypass sampling for tests and a random draw for production.
   */
  public static String boundedPayloadWithSampledBypass(
      String payload, int capBytes, double payloadBypassRate) {
    return boundedPayloadWithSampledBypass(
        payload, capBytes, payloadBypassRate, ThreadLocalRandom.current().nextDouble());
  }

  static String boundedPayloadWithSampledBypass(
      String payload, int capBytes, double payloadBypassRate, double sample) {
    if (shouldBypassPayloadCap(payloadBypassRate, sample)) {
      return payload;
    }
    return boundedPayload(payload, capBytes);
  }

  static boolean shouldBypassPayloadCap(double payloadBypassRate, double sample) {
    double boundedRate = normalizeRate(payloadBypassRate);
    if (sample < 0.0d || sample >= 1.0d) {
      return false;
    }
    return sample < boundedRate;
  }

  private static double normalizeRate(double raw) {
    if (Double.isNaN(raw) || raw <= 0.0d) {
      return 0.0d;
    }
    if (raw >= 1.0d) {
      return 1.0d;
    }
    return raw;
  }

  static String truncationMarker(int originalBytes) {
    return "…<truncated:" + originalBytes + " bytes>";
  }

  static int utf8Bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  private static String tinyCapMarker(int capBytes) {
    String minimalMarker = "…";
    return utf8Bytes(minimalMarker) <= capBytes ? minimalMarker : "";
  }

  private static String truncateUtf8(String value, int maxBytes) {
    if (maxBytes <= 0 || value.isEmpty()) {
      return "";
    }

    StringBuilder out = new StringBuilder(value.length());
    int bytes = 0;
    for (int i = 0; i < value.length(); ) {
      int cp = value.codePointAt(i);
      String chunk = new String(Character.toChars(cp));
      int cpBytes = utf8Bytes(chunk);
      if (bytes + cpBytes > maxBytes) {
        break;
      }
      out.appendCodePoint(cp);
      bytes += cpBytes;
      i += Character.charCount(cp);
    }
    return out.toString();
  }
}
