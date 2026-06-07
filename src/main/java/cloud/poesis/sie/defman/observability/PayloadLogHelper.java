package cloud.poesis.sie.defman.observability;

import java.nio.charset.StandardCharsets;

/**
 * Builds bounded payload previews for structured logs while preserving UTF-8 byte semantics.
 *
 * <p>S-007 AC-1/AC-2 foundation: payloads under cap are emitted as-is; payloads over cap are
 * truncated and suffixed with {@code …<truncated:N bytes>} where {@code N} is the original UTF-8
 * byte length.
 */
public final class PayloadLogHelper {

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
    if (payload == null || capBytes <= 0) {
      return payload;
    }

    int originalBytes = utf8Bytes(payload);
    if (originalBytes <= capBytes) {
      return payload;
    }

    String marker = truncationMarker(originalBytes);
    int markerBytes = utf8Bytes(marker);
    if (markerBytes >= capBytes) {
      return truncateUtf8(marker, capBytes);
    }

    int previewBudget = capBytes - markerBytes;
    return truncateUtf8(payload, previewBudget) + marker;
  }

  static String truncationMarker(int originalBytes) {
    return "…<truncated:" + originalBytes + " bytes>";
  }

  static int utf8Bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
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
