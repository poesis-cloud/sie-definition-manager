package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Archetype schema utility service for the ascription layer.
 *
 * <p>
 * Centralizes schema inspection helpers ({@code $gsm:*} annotation checks,
 * {@code $ref → title}
 * extraction, GSM base title queries) and tenant archetype schema resolution
 * from the database.
 *
 * <p>
 * This service owns the {@link ArchetypeRepository} dependency for
 * <strong>read-only schema
 * resolution</strong> needed by ascription-layer services that cannot inject
 * {@link
 * ArchetypeService} (which implements {@link AscriptionSubtypeService}). This
 * is a documented
 * exception to the repository-service exclusivity rule: the exception
 * concentrates in this single
 * schema-focused service rather than leaking into multiple consumers.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class ArchetypeParsingService {

  private static final Pattern GSMARC_URI_PATTERN = Pattern.compile(
      "^gsmarc://([a-z0-9]+(?:-[a-z0-9]+)*)/"
          + "((?:[a-z0-9]+(?:-[a-z0-9]+)*/)*)([A-Z][A-Za-z0-9]*)/v([1-9][0-9]*)$");
  private static final Set<String> GSM_BASE_IDS = DefinitionSubjectType.archetypeTitles().stream()
      .map(title -> "gsmarc://gsm/" + title + "/v1")
      .collect(java.util.stream.Collectors.toUnmodifiableSet());

  private final ArchetypeRepository archetypeRepository;

  public ArchetypeParsingService(ArchetypeRepository archetypeRepository) {
    this.archetypeRepository = archetypeRepository;
  }

  // ======================================================================
  // Schema annotation utilities
  // ======================================================================

  /**
   * Checks whether a JSON Schema node carries a boolean {@code $gsm:*} annotation
   * set to {@code
   * true}.
   *
   * @param node       the JSON Schema node (typically a property definition)
   * @param annotation the annotation keyword (e.g., {@code "$gsm:queryable"})
   * @return {@code true} if the annotation is present and {@code true}
   */
  public static boolean hasAnnotation(JsonNode node, String annotation) {
    return node.has(annotation) && node.get(annotation).asBoolean(false);
  }

  // ======================================================================
  // $ref / URI utilities
  // ======================================================================

  public record ArchetypeIdentity(
      String authority, String namespacePath, String title, int version, String stem) {
  }

  public static ArchetypeIdentity parseIdentity(String id) {
    if (id == null) {
      throw new IllegalArgumentException("Archetype identity must not be null");
    }
    Matcher matcher = GSMARC_URI_PATTERN.matcher(id);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid Archetype identity: " + id);
    }
    int version;
    try {
      version = Integer.parseInt(matcher.group(4));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          "Archetype identity version exceeds integer range: " + id, exception);
    }
    return new ArchetypeIdentity(
        matcher.group(1),
        matcher.group(2),
        matcher.group(3),
        version,
        id.substring(0, id.lastIndexOf("/v")));
  }

  static boolean hasCoherentIdentityTitle(String id, String title) {
    try {
      return parseIdentity(id).title().equals(title);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  /**
   * Extracts the archetype title from a
   * {@code gsmarc://{authority}/{segments}/{title}/v{version}}
   * URI. The title is the last path segment before the {@code /v{version}}
   * suffix.
   *
   * @param ref the {@code $ref} URI string
   * @return the extracted title, or {@code null} if the URI does not match the
   *         convention
   */
  public static String extractTitleFromRef(String ref) {
    try {
      return parseIdentity(ref).title();
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  /**
   * Checks whether a {@code $ref} URI is allowed by the archetype URI policy:
   * local URI fragments
   * ({@code #...}) or {@code gsmarc://{authority}/{segments}/{title}/v{version}}
   * URIs.
   *
   * @param ref the {@code $ref} URI string to check
   * @return {@code true} if the URI is allowed
   */
  public static boolean isAllowedRef(String ref) {
    if (ref.startsWith("#")) {
      return true;
    }
    try {
      parseIdentity(ref);
      return true;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  public static boolean isGsmBaseId(String id) {
    return GSM_BASE_IDS.contains(id);
  }

  /**
   * The version-pinned URIs of the GSM base Archetypes.
   *
   * @return an unmodifiable set of base Archetype URIs
   */
  public static Set<String> gsmBaseIds() {
    return GSM_BASE_IDS;
  }

  // ======================================================================
  // Schema resolution
  // ======================================================================

  /**
   * Finds the Archetype an authored URI resolves to. Lifecycle eligibility is
   * checked by the
   * consuming operation after resolution.
   *
   * @param uri the complete version-pinned {@code gsmarc://} Archetype URI
   * @return the resolved Archetype if its persisted governance version is
   *         positive
   */
  public Optional<ArchetypeEntity> findResolvableByUri(String uri) {
    return archetypeRepository.findResolvableByUri(uri);
  }
}
