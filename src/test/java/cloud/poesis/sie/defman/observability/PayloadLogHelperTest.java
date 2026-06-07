package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PayloadLogHelperTest {

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
}
