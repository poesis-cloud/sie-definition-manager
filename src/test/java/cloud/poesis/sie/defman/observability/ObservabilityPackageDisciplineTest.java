package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Package discipline test for the observability package.
 *
 * <p>Verifies that all custom observability primitives live under {@code
 * cloud.poesis.sie.defman.observability.*} per ADR-001 D-5.
 *
 * <p><strong>Story:</strong> S-004 (AC-7)
 */
class ObservabilityPackageDisciplineTest {

  private static final String EXPECTED_PACKAGE = "cloud.poesis.sie.defman.observability";
  private static final String EXPECTED_PACKAGE_PATH = EXPECTED_PACKAGE.replace('.', '/');

  @Test
  void allObservabilityClassesLiveUnderCorrectPackage() throws Exception {
    // Given: The src/main/java directory (scan all sources, not just observability)
    Path srcMainJava = Paths.get("src/main/java");

    if (!Files.exists(srcMainJava)) {
      throw new AssertionError("src/main/java directory does not exist: " + srcMainJava);
    }

    // When: We collect all Java files that define custom observability primitives
    // (not just classes that import OTel for config/bean wiring)
    List<String> observabilityViolations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(srcMainJava)) {
      paths
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(
              filePath -> {
                try {
                  List<String> lines = Files.readAllLines(filePath);
                  String packageDeclaration =
                      lines.stream()
                          .map(String::trim)
                          .filter(line -> line.startsWith("package "))
                          .findFirst()
                          .orElse(null);

                  if (packageDeclaration == null) {
                    return; // Skip files without package declaration
                  }

                  String declaredPackage =
                      packageDeclaration.replace("package ", "").replace(";", "").trim();

                  // Check if file DEFINES custom observability primitives
                  // (annotations, aspects, span emitters - not just imports OTel for config)
                  String fileContent = String.join("\n", lines);
                  boolean definesObservabilityPrimitive =
                      fileContent.contains("@interface InternalSpan")
                          || fileContent.contains("class InternalSpanAspect")
                          || fileContent.contains("class StartupSpanEmitter")
                          || (fileContent.contains("@Aspect")
                              && fileContent.contains("io.opentelemetry.api.trace.Span"))
                          || (fileContent.contains("@Retention")
                              && fileContent.contains("@Target")
                              && lines.stream()
                                  .anyMatch(
                                      line ->
                                          line.contains("public @interface")
                                              && line.contains("Span")));

                  if (definesObservabilityPrimitive) {
                    // Verify it's under the correct package (exact match or subpackage)
                    if (!declaredPackage.equals(EXPECTED_PACKAGE)
                        && !declaredPackage.startsWith(EXPECTED_PACKAGE + ".")) {
                      observabilityViolations.add(
                          String.format(
                              "File %s defines custom observability primitive but declares package '%s' "
                                  + "(expected '%s' or '%s.*')",
                              filePath, declaredPackage, EXPECTED_PACKAGE, EXPECTED_PACKAGE));
                    }
                  }
                } catch (Exception e) {
                  throw new RuntimeException("Failed to read file: " + filePath, e);
                }
              });
    }

    // Then: No violations found
    assertThat(observabilityViolations)
        .withFailMessage(
            "Found custom observability primitives outside allowed package:\n%s",
            String.join("\n", observabilityViolations))
        .isEmpty();

    // Then: Verify known observability classes exist in correct package
    Path observabilitySourcePath = Paths.get("src/main/java", EXPECTED_PACKAGE_PATH);
    assertThat(observabilitySourcePath).exists();

    List<String> javaFiles = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(observabilitySourcePath)) {
      paths
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(p -> javaFiles.add(p.toString()));
    }

    assertThat(javaFiles)
        .isNotEmpty()
        .anyMatch(f -> f.contains("InternalSpan.java"))
        .anyMatch(f -> f.contains("InternalSpanAspect.java"))
        .anyMatch(f -> f.contains("StartupSpanEmitter.java"));
  }
}
