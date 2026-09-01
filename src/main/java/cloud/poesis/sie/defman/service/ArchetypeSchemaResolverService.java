package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * The single Archetype {@code gsmarc://} URI → JSON Schema resolver, and the resolver of an
 * Archetype's <b>resolved composition chain</b> (GSM §11.1 annotation inheritance).
 *
 * <p>Every caller that walks a composition chain — composition validation, annotation validation,
 * Norm applicability, index provisioning — MUST resolve URIs through {@link #resolveUri}, so that
 * one Archetype yields one resolved property set per request. Property-scoped {@code $gsm:*}
 * keywords are inherited through schema composition, so annotation-driven behaviour (queryability,
 * aliasing, uniqueness, identity binding, data protection, indexation) MUST read {@link
 * #resolvedProperties} rather than the Archetype's own {@code properties} block, which sees only
 * locally declared members.
 *
 * <p>Because an Archetype {@code $ref} names a version-pinned URI and an approved version is
 * immutable, a resolved property set is stable for the lifetime of the referenced Ascription and is
 * cached by Ascription id.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class ArchetypeSchemaResolverService {

  private final ArchetypeParsingService archetypeParsing;
  private final ArchetypeCompositionValidationService compositionValidation;
  private final ObjectMapper objectMapper;

  private final Map<UUID, ObjectNode> resolvedPropertiesCache = new ConcurrentHashMap<>();
  private final Map<String, JsonNode> gsmBaseSchemaCache = new ConcurrentHashMap<>();

  ArchetypeSchemaResolverService(
      ArchetypeParsingService archetypeParsing,
      ArchetypeCompositionValidationService compositionValidation,
      ObjectMapper objectMapper) {
    this.archetypeParsing = archetypeParsing;
    this.compositionValidation = compositionValidation;
    this.objectMapper = objectMapper;
  }

  /**
   * Returns the resolved {@code properties} node of an Archetype: its own declared properties
   * composed with every property inherited through its {@code $ref} chain and {@code allOf} facets.
   *
   * @param archetype the typing, qualifier or data Archetype
   * @return a {@code properties}-shaped node; empty when the archetype declares and inherits none
   */
  public ObjectNode resolvedProperties(ArchetypeEntity archetype) {
    if (archetype == null || archetype.getStatement() == null) {
      return objectMapper.createObjectNode();
    }
    return resolvedPropertiesCache.computeIfAbsent(
        archetype.getId(), id -> resolve(archetype.getStatement()));
  }

  /**
   * Returns the resolved {@code properties} node of an unpersisted Archetype schema. Used at
   * authoring time, before the Archetype has an Ascription id to cache against.
   *
   * @param schema the archetype JSON Schema being authored
   * @return a {@code properties}-shaped node
   */
  public ObjectNode resolvedProperties(JsonNode schema) {
    if (schema == null || !schema.isObject()) {
      return objectMapper.createObjectNode();
    }
    return resolve(schema);
  }

  /**
   * Resolves a version-pinned Archetype URI to the JSON Schema it names.
   *
   * <p>The governed Ascription is authoritative. GSM base schemas are seeded from the vendored
   * classpath snapshot, so the snapshot is consulted only before seeding has materialized their
   * rows — never to override a governed statement.
   *
   * @param uri the {@code gsmarc://} Archetype URI
   * @return the referenced JSON Schema, or {@code null} when the URI resolves to nothing
   */
  public JsonNode resolveUri(String uri) {
    JsonNode governed =
        archetypeParsing.findResolvableByUri(uri).map(ArchetypeEntity::getStatement).orElse(null);
    if (governed != null) {
      return governed;
    }
    return ArchetypeParsingService.isGsmBaseId(uri)
        ? gsmBaseSchemaCache.computeIfAbsent(uri, this::loadGsmBaseSchema)
        : null;
  }

  private ObjectNode resolve(JsonNode schema) {
    Map<String, JsonNode> resolved =
        compositionValidation.resolvedProperties(schema, this::resolveUri);
    ObjectNode node = objectMapper.createObjectNode();
    resolved.forEach(node::set);
    return node;
  }

  private JsonNode loadGsmBaseSchema(String uri) {
    String title = ArchetypeParsingService.parseIdentity(uri).title();
    ClassPathResource resource = new ClassPathResource("gsm/schemas/" + title + ".schema.json");
    try (InputStream stream = resource.getInputStream()) {
      return objectMapper.readTree(stream);
    } catch (IOException exception) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY,
          "Cannot load vendored GSM base Archetype schema for '" + uri + "'",
          exception,
          "ref",
          uri);
    }
  }
}
