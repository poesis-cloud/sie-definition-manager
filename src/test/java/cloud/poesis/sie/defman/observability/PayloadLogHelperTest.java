package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class PayloadLogHelperTest {

  @RegisterExtension static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

  @Test
  void nonPositiveCapNeverReturnsRawPayload() {
    String payload = "{\"kind\":\"Norm\",\"name\":\"CapSafety\"}";

    assertThat(PayloadLogHelper.boundedPayload(payload, 0)).isEmpty();
    assertThat(PayloadLogHelper.boundedPayload(payload, -5)).isEmpty();
  }

  @Test
  void underCapPayloadIsReturnedAsIs() {
    String payload = "{\"kind\":\"Structure\",\"name\":\"A\"}";
    int cap = PayloadLogHelper.utf8Bytes(payload) + 10;

    String out = PayloadLogHelper.boundedPayload(payload, cap);

    assertThat(out).isEqualTo(payload);
    assertThat(out).doesNotContain("<truncated:");
  }

  @Test
  void overCapPayloadIsTruncatedAndSuffixedWithExplicitMarker() {
    String payload = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";
    String expectedMarker = PayloadLogHelper.truncationMarker(PayloadLogHelper.utf8Bytes(payload));
    int cap = 40;

    String out = PayloadLogHelper.boundedPayload(payload, cap);

    assertThat(out).endsWith(expectedMarker);
    assertThat(PayloadLogHelper.utf8Bytes(out)).isLessThanOrEqualTo(cap);
  }

  @Test
  void truncationUsesUtf8ByteLengthSemantics() {
    String payload = "áááááááááááááááááááá";
    int originalBytes = PayloadLogHelper.utf8Bytes(payload);
    String expectedMarker = PayloadLogHelper.truncationMarker(originalBytes);
    int cap = PayloadLogHelper.utf8Bytes(expectedMarker) + 8;

    String out = PayloadLogHelper.boundedPayload(payload, cap);

    assertThat(out).endsWith(expectedMarker);
    assertThat(PayloadLogHelper.utf8Bytes(out)).isLessThanOrEqualTo(cap);
    assertThat(out).doesNotContain("?");
  }

  @Test
  void tinyCapUsesDeterministicMarkerBehavior() {
    String payload = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";

    assertThat(PayloadLogHelper.boundedPayload(payload, 1)).isEmpty();
    assertThat(PayloadLogHelper.boundedPayload(payload, 2)).isEmpty();
    assertThat(PayloadLogHelper.boundedPayload(payload, 3)).isEqualTo("…");
    assertThat(PayloadLogHelper.boundedPayload(payload, 4)).isEqualTo("…");
  }

  @Test
  void exactMarkerFitUsesFullMarkerWithoutPreview() {
    String payload = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";
    String marker = PayloadLogHelper.truncationMarker(PayloadLogHelper.utf8Bytes(payload));

    String out = PayloadLogHelper.boundedPayload(payload, PayloadLogHelper.utf8Bytes(marker));

    assertThat(out).isEqualTo(marker);
  }

  @Test
  void truncationPreservesUtf8AndSurrogateBoundaries() {
    String payload = "😀".repeat(20);
    int originalBytes = PayloadLogHelper.utf8Bytes(payload);
    String expectedMarker = PayloadLogHelper.truncationMarker(originalBytes);
    int cap = PayloadLogHelper.utf8Bytes(expectedMarker) + 5;

    String out = PayloadLogHelper.boundedPayload(payload, cap);

    assertThat(out).startsWith("😀");
    assertThat(out).doesNotStartWith("😀😀");
    assertThat(out).endsWith(expectedMarker);
    assertThat(out).doesNotContain("\uFFFD");
    assertThat(PayloadLogHelper.utf8Bytes(out)).isLessThanOrEqualTo(cap);
  }

  @Test
  void malformedSurrogateInputDoesNotThrowDuringTruncation() {
    String payload = "\uD800" + "abcdef";

    String out = PayloadLogHelper.boundedPayload(payload, 4);

    assertThat(out).isNotNull();
  }

  @Test
  @DisplayName("AC-3: truncation with active span emits capped payload summary event")
  void truncationWithActiveSpanEmitsSummaryEvent() {
    String payload = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";
    int cap = 40;
    Tracer tracer = otel.getOpenTelemetry().getTracer("payload-helper-test");
    Span span = tracer.spanBuilder("payload-span").startSpan();
    String bounded;
    try (Scope ignored = span.makeCurrent()) {
      bounded =
          PayloadLogHelper.boundedPayloadWithSpanSummary(
              "POST /ascriptions", "asc-42", payload, cap);
    } finally {
      span.end();
    }

    SpanData captured =
        otel.getSpans().stream()
            .filter(s -> s.getName().equals("payload-span"))
            .findFirst()
            .orElseThrow();

    assertThat(captured.getEvents()).hasSize(1);
    EventData event = captured.getEvents().get(0);
    assertThat(event.getName()).isEqualTo("sie.payload.truncated.summary");
    assertThat(event.getAttributes().get(AttributeKey.stringKey("operation")))
        .isEqualTo("POST /ascriptions");
    assertThat(event.getAttributes().get(AttributeKey.stringKey("id"))).isEqualTo("asc-42");
    assertThat(event.getAttributes().get(AttributeKey.longKey("original.byte_length")))
        .isEqualTo((long) PayloadLogHelper.utf8Bytes(payload));
    assertThat(event.getAttributes().get(AttributeKey.stringKey("truncation.marker")))
        .isEqualTo(PayloadLogHelper.truncationMarker(PayloadLogHelper.utf8Bytes(payload)));
    assertThat(event.getAttributes().get(AttributeKey.stringKey("capped.preview")))
        .isEqualTo(bounded);
    assertThat(event.getAttributes().get(AttributeKey.stringKey("capped.preview")))
        .isNotEqualTo(payload);
  }

  @Test
  @DisplayName("AC-3: truncation without active span does not fail")
  void truncationWithoutActiveSpanDoesNotFail() {
    String payload = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";

    String bounded =
        PayloadLogHelper.boundedPayloadWithSpanSummary("POST /ascriptions", "asc-42", payload, 40);

    assertThat(bounded).contains("<truncated:");
  }

  @Test
  @DisplayName("AC-4: sampled bypass selected path returns uncapped payload")
  void sampledBypassSelectedPathReturnsOriginalPayload() {
    String payload = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";

    String out = PayloadLogHelper.boundedPayloadWithSampledBypass(payload, 24, 0.30d, 0.20d);

    assertThat(out).isEqualTo(payload);
  }

  @Test
  @DisplayName("AC-4: sampled bypass not-selected path returns bounded payload")
  void sampledBypassNotSelectedPathReturnsBoundedPayload() {
    String payload = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";

    String out = PayloadLogHelper.boundedPayloadWithSampledBypass(payload, 24, 0.30d, 0.80d);

    assertThat(out).contains("<truncated:");
    assertThat(out).isNotEqualTo(payload);
  }
}
