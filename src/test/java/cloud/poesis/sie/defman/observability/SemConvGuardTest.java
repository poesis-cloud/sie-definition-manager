package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Sem-conv compliance guard (AC-5 of S-006, ADR-003 D-8, R-INV-2).
 *
 * <p>Scans {@code src/main/java} for any code that writes the agent-owned semantic-convention keys
 * {@code trace_id}, {@code span_id}, or {@code service.name}. Under ADR-001 D-3 and ADR-003 D-4,
 * those keys are exclusively owned by the OpenTelemetry Java agent + collector pipeline; Defman
 * production code MUST NOT set them on MDC, OTel attributes, or AttributeKeys.
 *
 * <p>This is a plain JUnit 5 unit test — no Spring context — so it runs in milliseconds and can
 * fail the build before any heavier test starts.
 *
 * <p>Three checks, one per writer surface:
 *
 * <ol>
 *   <li>{@code MDC.put("&lt;key&gt;"} (with/without whitespace) — Slf4j MDC writer.
 *   <li>{@code .setAttribute("&lt;key&gt;"} — programmatic OTel attribute setter.
 *   <li>{@code AttributeKey.stringKey("&lt;key&gt;")} — AttributeKey declaration.
 * </ol>
 *
 * <p>Each method collects ALL violations across ALL files before failing, so a single test run
 * surfaces the full damage. Violations are reported as {@code <file>:<line> — <pattern>}.
 *
 * <p><b>Self-test procedure</b> (verify the guard actually catches what it should):
 *
 * <ol>
 *   <li>Temporarily add {@code MDC.put("trace_id", "x");} to any production class (e.g. {@code
 *       TenantMdcFilter}).
 *   <li>Run {@code mvn -pl . test -Dtest=SemConvGuardTest} — expect failure naming the file + line
 *       + matched pattern.
 *   <li>Revert the change with {@code git checkout -- &lt;file&gt;}; rerun — expect green.
 * </ol>
 *
 * <p>Tests in {@code src/test/java} are deliberately allowed to reference these keys (for
 * assertions verifying their absence in production output); only the {@code src/main/java} tree is
 * scanned.
 */
class SemConvGuardTest {

  private static final Path MAIN_JAVA = Path.of("src/main/java");

  private static final List<String> FORBIDDEN_KEYS = List.of("trace_id", "span_id", "service.name");

  @Test
  void noForbiddenSemConvKeysInProductionMdcCalls() throws IOException {
    List<String> patterns = new ArrayList<>();
    for (String key : FORBIDDEN_KEYS) {
      patterns.add("MDC.put(\"" + key + "\"");
      patterns.add("MDC.put( \"" + key + "\"");
    }
    List<String> violations = scan(patterns);
    assertThat(violations)
        .as(
            "ADR-001 D-3 / ADR-003 D-4 / R-INV-2: trace_id, span_id, and service.name are "
                + "owned by the OTel agent; Defman code MUST NOT write them to MDC. "
                + "Offenders:\n%s",
            String.join("\n", violations))
        .isEmpty();
  }

  @Test
  void noForbiddenSemConvKeysInProductionAttributeSetters() throws IOException {
    List<String> patterns = new ArrayList<>();
    for (String key : FORBIDDEN_KEYS) {
      patterns.add(".setAttribute(\"" + key + "\"");
    }
    List<String> violations = scan(patterns);
    assertThat(violations)
        .as(
            "ADR-001 D-3 / ADR-003 D-4 / R-INV-2: trace_id, span_id, and service.name are "
                + "owned by the OTel agent; Defman code MUST NOT call setAttribute() with "
                + "those keys. Offenders:\n%s",
            String.join("\n", violations))
        .isEmpty();
  }

  @Test
  void noForbiddenSemConvKeysInProductionAttributeKeyDeclarations() throws IOException {
    List<String> patterns = new ArrayList<>();
    for (String key : FORBIDDEN_KEYS) {
      patterns.add("AttributeKey.stringKey(\"" + key + "\")");
    }
    List<String> violations = scan(patterns);
    assertThat(violations)
        .as(
            "ADR-001 D-3 / ADR-003 D-4 / R-INV-2: trace_id, span_id, and service.name are "
                + "owned by the OTel agent; Defman code MUST NOT declare AttributeKeys for "
                + "those keys. Offenders:\n%s",
            String.join("\n", violations))
        .isEmpty();
  }

  /**
   * Walks {@link #MAIN_JAVA}, returning every {@code <file>:<line> — <pattern>} occurrence where a
   * line contains any of the given patterns. Collects ALL matches; never short-circuits.
   */
  private static List<String> scan(List<String> patterns) throws IOException {
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
      List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
      for (Path file : javaFiles) {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
          String line = lines.get(i);
          for (String pattern : patterns) {
            if (line.contains(pattern)) {
              violations.add(file + ":" + (i + 1) + " — " + pattern);
            }
          }
        }
      }
    }
    return violations;
  }
}
