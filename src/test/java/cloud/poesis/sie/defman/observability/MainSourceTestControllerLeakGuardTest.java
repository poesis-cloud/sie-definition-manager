package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AC-5 guard for S-006b: production sources must not contain test-only controllers/endpoints. */
class MainSourceTestControllerLeakGuardTest {

  private static final Path MAIN_JAVA = Path.of("src/main/java");

  @Test
  @DisplayName("AC-5: src/main has no test-only controllers or /__test endpoints")
  void mainSourcesContainNoTestOnlyControllers() throws IOException {
    List<String> violations = new ArrayList<>();

    try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
      List<Path> javaFiles =
          files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
      for (Path file : javaFiles) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        List<String> lines = Files.readAllLines(file);

        if (fileName.contains("testcontroller") || fileName.contains("mockcontroller")) {
          violations.add(file + " -> suspicious test-only controller class name");
        }

        for (int i = 0; i < lines.size(); i++) {
          String line = lines.get(i);
          String lower = line.toLowerCase(Locale.ROOT);
          if ((lower.contains("@restcontroller") || lower.contains("@controller"))
              && fileName.contains("test")) {
            violations.add(file + ":" + (i + 1) + " -> controller annotation in test-named class");
          }
          if (lower.contains("/__test") || lower.contains("/test/") || lower.contains("mockmvc")) {
            violations.add(file + ":" + (i + 1) + " -> test-only endpoint marker in src/main");
          }
        }
      }
    }

    assertThat(violations)
        .as(
            "AC-5: test-only controllers/endpoints must live under src/test only. Offenders:%n%s",
            String.join(System.lineSeparator(), violations))
        .isEmpty();
  }
}
