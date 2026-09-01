package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link ArchetypeSchemaResolverService} — GSM §11.1 annotation inheritance. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchetypeSchemaResolverServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String PARENT_URI = "gsmarc://tenant/ParentComponent/v1";
  private static final String CHILD_URI = "gsmarc://tenant/ChildComponent/v1";
  private static final String FACET_URI = "gsmarc://tenant/Facet/v1";

  @Mock private ArchetypeParsingService archetypeParsing;

  private ArchetypeSchemaResolverService service;

  @BeforeEach
  void setUp() {
    service =
        new ArchetypeSchemaResolverService(
            archetypeParsing, new ArchetypeCompositionValidationService(), MAPPER);
  }

  // ========================================================================
  // Own declarations
  // ========================================================================

  @Nested
  class OwnDeclarations {

    @Test
    void nullArchetype_returnsEmpty() {
      assertTrue(service.resolvedProperties((ArchetypeEntity) null).isEmpty());
    }

    @Test
    void nullStatement_returnsEmpty() {
      ArchetypeEntity archetype = archetype(UUID.randomUUID(), null);
      assertTrue(service.resolvedProperties(archetype).isEmpty());
    }

    @Test
    void nonObjectSchema_returnsEmpty() {
      assertTrue(service.resolvedProperties(MAPPER.getNodeFactory().textNode("x")).isEmpty());
    }

    @Test
    void ownPropertiesOnly_returnedAsIs() {
      ObjectNode schema = schema(CHILD_URI, "ChildComponent");
      queryableProperty(schema, "ownProp");

      ObjectNode resolved = service.resolvedProperties(archetype(UUID.randomUUID(), schema));

      assertEquals(1, resolved.size());
      assertTrue(resolved.path("ownProp").path("$gsm:queryable").asBoolean());
    }
  }

  // ========================================================================
  // Inheritance through the $ref chain
  // ========================================================================

  @Nested
  class RefChainInheritance {

    @Test
    void inheritsGsmBaseProperties_fromVendoredClasspath() {
      ObjectNode schema = schema(CHILD_URI, "ChildComponent");
      schema.put("$ref", "gsmarc://gsm/Structure/v1");
      queryableProperty(schema, "ownProp");

      ObjectNode resolved = service.resolvedProperties(archetype(UUID.randomUUID(), schema));

      assertTrue(resolved.has("purpose"), "GSM base property must be inherited");
      assertTrue(
          resolved.path("purpose").path("$gsm:identityBound").asBoolean(),
          "Inherited property must keep its $gsm:* annotation");
      assertTrue(resolved.has("ownProp"));
    }

    @Test
    void inheritsTenantAncestorAnnotations_fromDatabase() {
      ObjectNode parent = schema(PARENT_URI, "ParentComponent");
      queryableProperty(parent, "architectureTier");
      ArchetypeEntity parentEntity = archetype(UUID.randomUUID(), parent);
      when(archetypeParsing.findResolvableByUri(PARENT_URI)).thenReturn(Optional.of(parentEntity));

      ObjectNode child = schema(CHILD_URI, "ChildComponent");
      child.put("$ref", PARENT_URI);

      ObjectNode resolved = service.resolvedProperties(archetype(UUID.randomUUID(), child));

      assertTrue(
          resolved.path("architectureTier").path("$gsm:queryable").asBoolean(),
          "$gsm:queryable declared by the ancestor must be visible on the descendant");
    }

    @Test
    void ownDeclarationOverridesInherited() {
      ObjectNode parent = schema(PARENT_URI, "ParentComponent");
      parent.with("properties").putObject("tier").put("type", "string");
      ArchetypeEntity parentEntity = archetype(UUID.randomUUID(), parent);
      when(archetypeParsing.findResolvableByUri(PARENT_URI)).thenReturn(Optional.of(parentEntity));

      ObjectNode child = schema(CHILD_URI, "ChildComponent");
      child.put("$ref", PARENT_URI);
      queryableProperty(child, "tier");

      ObjectNode resolved = service.resolvedProperties(archetype(UUID.randomUUID(), child));

      assertEquals(1, resolved.size());
      assertTrue(
          resolved.path("tier").path("$gsm:queryable").asBoolean(),
          "Descendant may add an annotation to an inherited property");
    }

    @Test
    void redeclaringInheritedProperty_keepsAncestorAnnotations() {
      ObjectNode parent = schema(PARENT_URI, "ParentComponent");
      queryableProperty(parent, "functionalDomain");
      parent
          .with("properties")
          .with("functionalDomain")
          .putObject("$gsm:dataProtection")
          .putObject("atRest")
          .put("suppression", true);
      ArchetypeEntity parentEntity = archetype(UUID.randomUUID(), parent);
      when(archetypeParsing.findResolvableByUri(PARENT_URI)).thenReturn(Optional.of(parentEntity));

      // Descendant only narrows the value; it declares no $gsm:* of its own.
      ObjectNode child = schema(CHILD_URI, "ChildComponent");
      child.put("$ref", PARENT_URI);
      child
          .with("properties")
          .putObject("functionalDomain")
          .put("type", "string")
          .put("pattern", "^bgm-");

      ObjectNode resolved = service.resolvedProperties(archetype(UUID.randomUUID(), child));

      assertTrue(
          resolved.path("functionalDomain").path("$gsm:queryable").asBoolean(),
          "Narrowing a pattern must not drop the ancestor's $gsm:queryable");
      assertTrue(
          resolved
              .path("functionalDomain")
              .path("$gsm:dataProtection")
              .path("atRest")
              .path("suppression")
              .asBoolean(),
          "Narrowing a pattern must not drop the ancestor's $gsm:dataProtection");
    }

    @Test
    void inheritsThroughAllOfFacet() {
      ObjectNode facet = schema(PARENT_URI, "ParentComponent");
      queryableProperty(facet, "facetProp");
      ArchetypeEntity facetEntity = archetype(UUID.randomUUID(), facet);
      when(archetypeParsing.findResolvableByUri(PARENT_URI)).thenReturn(Optional.of(facetEntity));

      ObjectNode child = schema(CHILD_URI, "ChildComponent");
      child.putArray("allOf").addObject().put("$ref", PARENT_URI);

      ObjectNode resolved = service.resolvedProperties(archetype(UUID.randomUUID(), child));

      assertTrue(resolved.path("facetProp").path("$gsm:queryable").asBoolean());
    }
  }

  // ========================================================================
  // Annotation join semantics — GSM §11.1
  // ========================================================================

  @Nested
  class AnnotationJoin {

    @Test
    void allOfFacetAnnotations_joinWithRefChainProperty() {
      // The $ref chain supplies the property body; a sibling allOf facet annotates the same key.
      ObjectNode parent = schema(PARENT_URI, "ParentComponent");
      parent.with("properties").putObject("sharedProp").put("type", "string");
      ArchetypeEntity parentEntity = archetype(UUID.randomUUID(), parent);
      when(archetypeParsing.findResolvableByUri(PARENT_URI)).thenReturn(Optional.of(parentEntity));

      ObjectNode facet = schema(FACET_URI, "Facet");
      queryableProperty(facet, "sharedProp");
      ArchetypeEntity facetEntity = archetype(UUID.randomUUID(), facet);
      when(archetypeParsing.findResolvableByUri(FACET_URI)).thenReturn(Optional.of(facetEntity));

      ObjectNode child = schema(CHILD_URI, "ChildComponent");
      child.put("$ref", PARENT_URI);
      child.putArray("allOf").addObject().put("$ref", FACET_URI);

      ObjectNode resolved = service.resolvedProperties(archetype(UUID.randomUUID(), child));

      assertTrue(
          resolved.path("sharedProp").path("$gsm:queryable").asBoolean(),
          "An allOf facet's annotation must survive the $ref chain supplying the same key");
    }

    @Test
    void booleanSchemaRedeclaration_keepsAncestorAnnotations() {
      ObjectNode parent = schema(PARENT_URI, "ParentComponent");
      queryableProperty(parent, "flag");
      ArchetypeEntity parentEntity = archetype(UUID.randomUUID(), parent);
      when(archetypeParsing.findResolvableByUri(PARENT_URI)).thenReturn(Optional.of(parentEntity));

      ObjectNode child = schema(CHILD_URI, "ChildComponent");
      child.put("$ref", PARENT_URI);
      child.with("properties").put("flag", true);

      ObjectNode resolved = service.resolvedProperties(archetype(UUID.randomUUID(), child));

      assertTrue(
          resolved.path("flag").path("$gsm:queryable").asBoolean(),
          "A boolean schema carries no annotations of its own; the ancestor's must be kept");
    }

    @Test
    void booleanSchemaAncestor_yieldsToTheDescendantDeclaration() {
      ObjectNode parent = schema(PARENT_URI, "ParentComponent");
      parent.with("properties").put("flag", true);
      ArchetypeEntity parentEntity = archetype(UUID.randomUUID(), parent);
      when(archetypeParsing.findResolvableByUri(PARENT_URI)).thenReturn(Optional.of(parentEntity));

      ObjectNode child = schema(CHILD_URI, "ChildComponent");
      child.put("$ref", PARENT_URI);
      queryableProperty(child, "flag");

      ObjectNode resolved = service.resolvedProperties(archetype(UUID.randomUUID(), child));

      assertTrue(
          resolved.path("flag").path("$gsm:queryable").asBoolean(),
          "A boolean ancestor schema has nothing to join; the descendant declaration stands");
    }

    @Test
    void weakerRedeclaredProtection_isSupersededByTheAncestorMeasure() {
      ObjectNode parent = schema(PARENT_URI, "ParentComponent");
      parent
          .with("properties")
          .putObject("secret")
          .put("type", "string")
          .putObject("$gsm:dataProtection")
          .putObject("atRest")
          .put("hash", true);
      ArchetypeEntity parentEntity = archetype(UUID.randomUUID(), parent);
      when(archetypeParsing.findResolvableByUri(PARENT_URI)).thenReturn(Optional.of(parentEntity));

      ObjectNode child = schema(CHILD_URI, "ChildComponent");
      child.put("$ref", PARENT_URI);
      child
          .with("properties")
          .putObject("secret")
          .put("type", "string")
          .putObject("$gsm:dataProtection")
          .putObject("atRest")
          .put("mask", true);

      ObjectNode resolved = service.resolvedProperties(archetype(UUID.randomUUID(), child));

      JsonNode atRest = resolved.path("secret").path("$gsm:dataProtection").path("atRest");
      assertTrue(atRest.path("hash").asBoolean(), "The stronger ancestor measure must be applied");
      assertFalse(atRest.has("mask"), "The weaker redeclaration must not survive");
    }

    @Test
    void aliasesAreUnioned_acrossTheChain() {
      ObjectNode parent = schema(PARENT_URI, "ParentComponent");
      ObjectNode parentProp = parent.with("properties").putObject("code").put("type", "string");
      parentProp.putArray("$gsm:aliases").add("legacyCode");
      ArchetypeEntity parentEntity = archetype(UUID.randomUUID(), parent);
      when(archetypeParsing.findResolvableByUri(PARENT_URI)).thenReturn(Optional.of(parentEntity));

      ObjectNode child = schema(CHILD_URI, "ChildComponent");
      child.put("$ref", PARENT_URI);
      ObjectNode childProp = child.with("properties").putObject("code").put("type", "string");
      childProp.putArray("$gsm:aliases").add("shortCode");

      ObjectNode resolved = service.resolvedProperties(archetype(UUID.randomUUID(), child));

      JsonNode aliases = resolved.path("code").path("$gsm:aliases");
      assertEquals(2, aliases.size());
      assertTrue(aliases.toString().contains("legacyCode"));
      assertTrue(aliases.toString().contains("shortCode"));
    }
  }

  // ========================================================================
  // URI resolution — single resolution order for every chain-walking caller
  // ========================================================================

  @Nested
  class UriResolution {

    private static final String GSM_BASE_URI = "gsmarc://gsm/Structure/v1";

    @Test
    void governedStoreWins_overTheVendoredClasspathSnapshot() {
      ObjectNode governed = schema(GSM_BASE_URI, "Structure");
      ArchetypeEntity governedEntity = archetype(UUID.randomUUID(), governed);
      when(archetypeParsing.findResolvableByUri(GSM_BASE_URI))
          .thenReturn(Optional.of(governedEntity));

      assertSame(governed, service.resolveUri(GSM_BASE_URI));
    }

    @Test
    void classpathSnapshotIsTheFallback_whenTheGovernedStoreHasNoRow() {
      when(archetypeParsing.findResolvableByUri(GSM_BASE_URI)).thenReturn(Optional.empty());

      JsonNode resolved = service.resolveUri(GSM_BASE_URI);

      assertNotNull(resolved, "A GSM base URI must still resolve from the vendored snapshot");
      assertTrue(resolved.path("properties").has("purpose"));
    }

    @Test
    void unknownTenantUri_resolvesToNull() {
      when(archetypeParsing.findResolvableByUri(PARENT_URI)).thenReturn(Optional.empty());

      assertNull(service.resolveUri(PARENT_URI));
    }
  }

  // ========================================================================
  // Caching
  // ========================================================================

  @Nested
  class Caching {

    @Test
    void resolvesOncePerAscription() {
      ObjectNode parent = schema(PARENT_URI, "ParentComponent");
      queryableProperty(parent, "architectureTier");
      ArchetypeEntity parentEntity = archetype(UUID.randomUUID(), parent);
      when(archetypeParsing.findResolvableByUri(PARENT_URI)).thenReturn(Optional.of(parentEntity));

      ObjectNode child = schema(CHILD_URI, "ChildComponent");
      child.put("$ref", PARENT_URI);
      ArchetypeEntity archetype = archetype(UUID.randomUUID(), child);

      service.resolvedProperties(archetype);
      service.resolvedProperties(archetype);

      verify(archetypeParsing, times(1)).findResolvableByUri(PARENT_URI);
    }

    @Test
    void unpersistedSchemaOverload_isNotCached() {
      ObjectNode schema = schema(CHILD_URI, "ChildComponent");
      queryableProperty(schema, "ownProp");

      assertFalse(
          service.resolvedProperties((com.fasterxml.jackson.databind.JsonNode) schema).isEmpty());
      assertFalse(
          service.resolvedProperties((com.fasterxml.jackson.databind.JsonNode) schema).isEmpty());
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private static ObjectNode schema(String id, String title) {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("$id", id);
    schema.put("title", title);
    schema.put("type", "object");
    schema.putObject("properties");
    return schema;
  }

  private static void queryableProperty(ObjectNode schema, String name) {
    schema.with("properties").putObject(name).put("type", "string").put("$gsm:queryable", true);
  }

  private static ArchetypeEntity archetype(UUID id, com.fasterxml.jackson.databind.JsonNode stmt) {
    ArchetypeEntity archetype = org.mockito.Mockito.mock(ArchetypeEntity.class);
    when(archetype.getId()).thenReturn(id);
    when(archetype.getStatement()).thenReturn(stmt);
    return archetype;
  }
}
