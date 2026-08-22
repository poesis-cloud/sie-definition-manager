package cloud.poesis.sie.defman.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Guards the vendored GSM base-schema snapshot ({@code vendor/gsm/schemas}) that the build maps
 * onto the classpath as {@code statement/}.
 *
 * <p>Source of truth is the {@code gsm-specifications} repo ({@code schemas/}). When that sibling
 * checkout is present (multi-root workspace), the snapshot must match it byte for byte — refresh
 * with {@code make sync-gsm-schemas}. In a single-repo checkout (CI) the drift check is skipped and
 * the completeness check alone gates the build input.
 */
class GsmSchemaVendorSyncTest {

  private static final Path VENDORED = Path.of("src", "main", "resources", "gsm", "schemas");
  private static final Path SPEC = Path.of("..", "..", "gsm", "gsm-specifications", "schemas");

  private static final Set<String> BASE_SCHEMA_FILES =
      Set.of(
          "Structure.schema.json",
          "Mechanism.schema.json",
          "Effector.schema.json",
          "Receptor.schema.json",
          "Interaction.schema.json",
          "Archetype.schema.json",
          "Directive.schema.json",
          "Norm.schema.json");

  @Test
  void vendoredSnapshotCarriesExactlyTheEightGsmBaseSchemas() throws IOException {
    assertEquals(BASE_SCHEMA_FILES, schemaFileNames(VENDORED));
  }

  @Test
  void vendoredSnapshotMatchesSpecRepoByteForByte() throws IOException {
    Assumptions.assumeTrue(
        Files.isDirectory(SPEC),
        "gsm-specifications sibling checkout not present — drift check skipped");

    assertEquals(
        schemaFileNames(SPEC),
        schemaFileNames(VENDORED),
        "vendored schema set differs from gsm-specifications — run `make sync-gsm-schemas`");

    for (String name : schemaFileNames(SPEC)) {
      assertArrayEquals(
          Files.readAllBytes(SPEC.resolve(name)),
          Files.readAllBytes(VENDORED.resolve(name)),
          name + " drifted from gsm-specifications — run `make sync-gsm-schemas`");
    }
  }

  private static Set<String> schemaFileNames(Path dir) throws IOException {
    assertTrue(Files.isDirectory(dir), "missing schema directory: " + dir);
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .map(p -> p.getFileName().toString())
          .filter(n -> n.endsWith(".schema.json"))
          .collect(Collectors.toSet());
    }
  }
}
