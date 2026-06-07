package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ArgsSummaryTest {

  // A POJO whose toString() returns a sentinel; the helper must NEVER call it.
  private static final class LeakyPojo {
    @Override
    public String toString() {
      return "SECRET_LEAK_MARKER";
    }
  }

  private record SamplePoint(int x, int y, String label) {}

  private static final class RésuméToken {}

  @Test
  void nullArgsArrayRendersEmptyParens() {
    assertThat(ArgsSummary.summarize(null)).isEqualTo("()");
  }

  @Test
  void emptyArgsArrayRendersEmptyParens() {
    assertThat(ArgsSummary.summarize(new Object[0])).isEqualTo("()");
  }

  @Test
  void nullArgRendersAsLiteralNull() {
    assertThat(ArgsSummary.summarize(new Object[] {null})).isEqualTo("(null)");
  }

  @Test
  void primitivesRenderAsBoxedSimpleNameOnly() {
    String out = ArgsSummary.summarize(new Object[] {1, 2L, true, 3.14d});
    assertThat(out).isEqualTo("(Integer, Long, Boolean, Double)");
  }

  @Test
  void stringRendersWithLength() {
    assertThat(ArgsSummary.summarize(new Object[] {""})).isEqualTo("(String[len=0])");
    assertThat(ArgsSummary.summarize(new Object[] {"hello"})).isEqualTo("(String[len=5])");
  }

  @Test
  void byteArrayRendersWithSize() {
    assertThat(ArgsSummary.summarize(new Object[] {new byte[0]})).isEqualTo("(byte[size=0])");
    assertThat(ArgsSummary.summarize(new Object[] {new byte[42]})).isEqualTo("(byte[size=42])");
  }

  @Test
  void objectArraysRenderWithComponentTypeAndSize() {
    assertThat(ArgsSummary.summarize(new Object[] {new Object[3]})).isEqualTo("(Object[size=3])");
    assertThat(ArgsSummary.summarize(new Object[] {new String[] {"a", "b"}}))
        .isEqualTo("(String[size=2])");
  }

  @Test
  void collectionsRenderImplSimpleNameAndSize() {
    ArrayList<Integer> list = new ArrayList<>(List.of(1, 2, 3));
    HashSet<String> set = new HashSet<>();
    set.add("a");
    Collection<String> raw = new ArrayList<>();
    assertThat(ArgsSummary.summarize(new Object[] {list})).isEqualTo("(ArrayList<size=3>)");
    assertThat(ArgsSummary.summarize(new Object[] {set})).isEqualTo("(HashSet<size=1>)");
    assertThat(ArgsSummary.summarize(new Object[] {raw})).isEqualTo("(ArrayList<size=0>)");
  }

  @Test
  void mapsRenderImplSimpleNameAndSize() {
    HashMap<String, Integer> h = new HashMap<>();
    h.put("a", 1);
    h.put("b", 2);
    TreeMap<String, Integer> t = new TreeMap<>();
    assertThat(ArgsSummary.summarize(new Object[] {h})).isEqualTo("(HashMap<size=2>)");
    assertThat(ArgsSummary.summarize(new Object[] {t})).isEqualTo("(TreeMap<size=0>)");
  }

  @Test
  void optionalRendersPresenceFlagOnly() {
    assertThat(ArgsSummary.summarize(new Object[] {Optional.empty()}))
        .isEqualTo("(Optional[present=false])");
    assertThat(ArgsSummary.summarize(new Object[] {Optional.of("SECRET_LEAK_MARKER")}))
        .isEqualTo("(Optional[present=true])");
  }

  @Test
  void recordRendersComponentCountOnly() {
    SamplePoint p = new SamplePoint(1, 2, "SECRET_LEAK_MARKER");
    assertThat(ArgsSummary.summarize(new Object[] {p})).isEqualTo("(SamplePoint[components=3])");
  }

  @Test
  void pojoFallbackRendersSimpleNameOnlyAndNeverInvokesToString() {
    LeakyPojo pojo = new LeakyPojo();
    String out = ArgsSummary.summarize(new Object[] {pojo});
    assertThat(out).isEqualTo("(LeakyPojo)");
    assertThat(out).doesNotContain("SECRET_LEAK_MARKER");
  }

  @Test
  void stringContentIsNeverLeaked() {
    String secret = "prefix-SECRET_LEAK_MARKER-suffix";
    String out = ArgsSummary.summarize(new Object[] {secret});
    assertThat(out).isEqualTo("(String[len=" + secret.length() + "])");
    assertThat(out).doesNotContain("SECRET_LEAK_MARKER");
  }

  @Test
  void mixedArgsRenderInOrderJoinedByCommaSpace() {
    ArrayList<Integer> list = new ArrayList<>(List.of(1, 2));
    String out =
        ArgsSummary.summarize(new Object[] {null, 1, "hi", new byte[2], list, Optional.empty()});
    assertThat(out)
        .isEqualTo(
            "(null, Integer, String[len=2], byte[size=2],"
                + " ArrayList<size=2>, Optional[present=false])");
  }

  @Test
  void outputIsTruncatedAtCapWithSuffix() {
    // Each rendered arg is "String[len=10]" (14 chars) + ", " separators.
    // Force the joined output past 16 KiB.
    int n = 2000;
    Object[] args = new Object[n];
    String ten = "0123456789";
    for (int i = 0; i < n; i++) {
      args[i] = ten;
    }
    String out = ArgsSummary.summarize(args);
    assertThat(out.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(ArgsSummary.MAX_SUMMARY_BYTES);
    assertThat(out)
        .matches(Pattern.compile(".*<TRUNCATED:\\d+_bytes>$", Pattern.DOTALL).pattern());
  }

  @Test
  void outputBelowCapIsNotTruncated() {
    String out = ArgsSummary.summarize(new Object[] {"a", 1, true});
    assertThat(out).doesNotContain("<TRUNCATED:");
    assertThat(out.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        .isLessThan(ArgsSummary.MAX_SUMMARY_BYTES);
  }

  @Test
  void utf8ByteCapTriggersWhenCharLengthIsUnderCapButByteLengthExceedsCap() {
    int n = 2500;
    Object[] args = new Object[n];
    for (int i = 0; i < n; i++) {
      args[i] = new RésuméToken();
    }
    String out = ArgsSummary.summarize(args);

    assertThat(out).contains("<TRUNCATED:");
    assertThat(out.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(ArgsSummary.MAX_SUMMARY_BYTES);
  }
}
