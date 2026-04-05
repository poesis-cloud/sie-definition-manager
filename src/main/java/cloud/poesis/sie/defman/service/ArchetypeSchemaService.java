package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Archetype schema utility service for the ascription layer.
 *
 * <p>Centralizes schema inspection helpers ({@code $gsm:*} annotation checks, {@code $ref → title}
 * extraction, GSM base title queries) and tenant archetype schema resolution from the database.
 *
 * <p>This service owns the {@link ArchetypeRepository} dependency for <strong>read-only schema
 * resolution</strong> needed by ascription-layer services that cannot inject {@link
 * ArchetypeService} (which implements {@link SubtypeHandler}). This is a documented exception to
 * the repository-service exclusivity rule: the exception concentrates in this single schema-focused
 * service rather than leaking into multiple consumers.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class ArchetypeSchemaService {

  private static final Pattern GSM_URI_PATTERN =
      Pattern.compile("^gsm://archetypes/([^/]+)/v\\d+$");

  private final ArchetypeRepository archetypeRepository;

  public ArchetypeSchemaService(ArchetypeRepository archetypeRepository) {
    this.archetypeRepository = archetypeRepository;
  }

  // ======================================================================
  // Schema annotation utilities
  // ======================================================================

  /**
   * Checks whether a JSON Schema node carries a boolean {@code $gsm:*} annotation set to {@code
   * true}.
   *
   * @param node the JSON Schema node (typically a property definition)
   * @param annotation the annotation keyword (e.g., {@code "$gsm:queryable"})
   * @return {@code true} if the annotation is present and {@code true}
   */
  public static boolean hasAnnotation(JsonNode node, String annotation) {
    return node.has(annotation) && node.get(annotation).asBoolean(false);
  }

  // ======================================================================
  // $ref / URI utilities
  // ======================================================================

  /**
   * Extracts the archetype title from a {@code gsm://archetypes/{title}/v{version}} URI.
   *
   * @param ref the {@code $ref} URI string
   * @return the extracted title, or {@code null} if the URI does not match the convention
   */
  public static String extractTitleFromRef(String ref) {
    Matcher m = GSM_URI_PATTERN.matcher(ref);
    if (m.matches()) {
      return m.group(1);
    }
    return null;
  }

  /**
   * Checks whether a {@code $ref} URI is allowed by the GSM URI policy: local JSON Pointers ({@code
   * #/...}) or {@code gsm://archetypes/{title}/v{version}} URIs.
   *
   * @param ref the {@code $ref} URI string to check
   * @return {@code true} if the URI is allowed
   */
  public static boolean isAllowedRef(String ref) {
    return ref.startsWith("#") || GSM_URI_PATTERN.matcher(ref).matches();
  }

  /**
   * Returns whether the given title is a GSM base archetype title (one of the 8 sealed primitives).
   *
   * @param title the archetype title to check
   * @return {@code true} if the title matches a GSM base archetype
   */
  public static boolean isGsmBaseTitle(String title) {
    return DefinitionSubjectType.archetypeTitles().contains(title);
  }

  // ======================================================================
  // Schema resolution
  // ======================================================================

  /**
   * Finds the in-effect archetype schema (statement) by title from the database.
   *
   * @param title the archetype title
   * @return the archetype entity if found in-effect, or empty
   */
  public Optional<ArchetypeEntity> findInEffectByTitle(String title) {
    return archetypeRepository.findInEffectByTitle(title);
  }
}
