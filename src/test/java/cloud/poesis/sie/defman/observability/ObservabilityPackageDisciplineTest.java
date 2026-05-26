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
    // Given: The observability source directory
    Path observabilitySourcePath = Paths.get("src/main/java", EXPECTED_PACKAGE_PATH);

    if (!Files.exists(observabilitySourcePath)) {
      // If the directory doesn't exist, fail with a clear message
      throw new AssertionError(
          "Observability package directory does not exist: " + observabilitySourcePath);
    }

    // When: We collect all Java files under observability/
    List<String> javaFiles = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(observabilitySourcePath)) {
      paths
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(p -> javaFiles.add(p.toString()));
    }

    // Then: At least the known classes exist
    assertThat(javaFiles)
        .isNotEmpty()
        .anyMatch(f -> f.contains("DomainOperation.java"))
        .anyMatch(f -> f.contains("DomainOperationAspect.java"))
        .anyMatch(f -> f.contains("StartupSpanEmitter.java"));

    // Then: All collected Java files declare the correct package
    for (String javaFile : javaFiles) {
      Path filePath = Paths.get(javaFile);
      List<String> lines = Files.readAllLines(filePath);

      // Find the package declaration line
      String packageDeclaration =
          lines.stream()
              .map(String::trim)
              .filter(line -> line.startsWith("package "))
              .findFirst()
              .orElseThrow(() -> new AssertionError("No package declaration found in " + javaFile));

      // Extract the package name (between "package " and ";")
      String declaredPackage = packageDeclaration.replace("package ", "").replace(";", "").trim();

      // Verify it's under cloud.poesis.sie.defman.observability.*
      assertThat(declaredPackage)
          .withFailMessage(
              "File %s declares package '%s' but should be under '%s.*'",
              javaFile, declaredPackage, EXPECTED_PACKAGE)
          .startsWith(EXPECTED_PACKAGE);
    }
  }
}
