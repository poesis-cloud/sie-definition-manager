package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArchetypeParsingServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private ArchetypeRepository archetypeRepository;

  private ArchetypeParsingService service;

  @BeforeEach
  void setUp() {
    service = new ArchetypeParsingService(archetypeRepository);
  }

  // ======================================================================
  // findResolvableByUri
  // ======================================================================

  @Nested
  class FindResolvableByUri {

    @Test
    void delegatesExactFullIdentityToRepository() {
      String id = "gsmarc://gsm-ontology/scap/cpe/ScapPlatformIdentifier/v1";
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(archetypeRepository.findResolvableByUri(id)).thenReturn(Optional.of(entity));

      Optional<ArchetypeEntity> result = service.findResolvableByUri(id);

      assertTrue(result.isPresent());
      assertSame(entity, result.get());
      verify(archetypeRepository).findResolvableByUri(id);
    }

    @Test
    void returnsEmptyWithoutTitleOrLifecycleFallback() {
      String id = "gsmarc://gsm/Structure/v99";
      when(archetypeRepository.findResolvableByUri(id)).thenReturn(Optional.empty());

      assertTrue(service.findResolvableByUri(id).isEmpty());

      verify(archetypeRepository).findResolvableByUri(id);
    }
  }

  // ======================================================================
  // hasAnnotation (static)
  // ======================================================================

  @Nested
  class HasAnnotation {

    @Test
    void returnsTrueWhenAnnotationPresentAndTrue() {
      ObjectNode node = MAPPER.createObjectNode().put("$gsm:queryable", true);
      assertTrue(ArchetypeParsingService.hasAnnotation(node, "$gsm:queryable"));
    }

    @Test
    void returnsFalseWhenAnnotationPresentButFalse() {
      ObjectNode node = MAPPER.createObjectNode().put("$gsm:queryable", false);
      assertFalse(ArchetypeParsingService.hasAnnotation(node, "$gsm:queryable"));
    }

    @Test
    void returnsFalseWhenAnnotationAbsent() {
      ObjectNode node = MAPPER.createObjectNode().put("type", "string");
      assertFalse(ArchetypeParsingService.hasAnnotation(node, "$gsm:queryable"));
    }
  }

  // ======================================================================
  // extractTitleFromRef (static)
  // ======================================================================

  @Nested
  class ExtractTitleFromRef {

    @Test
    void extractsTitleFromValidUri() {
      assertEquals(
          "Structure", ArchetypeParsingService.extractTitleFromRef("gsmarc://gsm/Structure/v1"));
    }

    @Test
    void extractsTitleFromMultiSegmentPath() {
      assertEquals(
          "ScapPlatformIdentifier",
          ArchetypeParsingService.extractTitleFromRef(
              "gsmarc://gsm-ontology/scap/cpe/ScapPlatformIdentifier/v1"));
    }

    @Test
    void extractsTitleFromOpsAuthority() {
      assertEquals(
          "GovernanceEvent",
          ArchetypeParsingService.extractTitleFromRef(
              "gsmarc://ops/protocols/governance/GovernanceEvent/v1"));
    }

    @Test
    void returnsNullForInvalidUri() {
      assertNull(ArchetypeParsingService.extractTitleFromRef("https://example.com"));
    }

    @Test
    void returnsNullForLocalPointer() {
      assertNull(ArchetypeParsingService.extractTitleFromRef("#/definitions/Foo"));
    }
  }

  // ======================================================================
  // parseIdentity (static)
  // ======================================================================

  @Nested
  class ParseIdentity {

    @Test
    void parsesNormativeIdentityComponents() {
      ArchetypeParsingService.ArchetypeIdentity identity =
          ArchetypeParsingService.parseIdentity(
              "gsmarc://gsm-ontology/scap/cpe/ScapPlatformIdentifier/v12");

      assertEquals("gsm-ontology", identity.authority());
      assertEquals("scap/cpe/", identity.namespacePath());
      assertEquals("ScapPlatformIdentifier", identity.title());
      assertEquals(12, identity.version());
      assertEquals("gsmarc://gsm-ontology/scap/cpe/ScapPlatformIdentifier", identity.stem());
    }

    @Test
    void rejectsNonNormativeIdentityForms() {
      assertThrows(
          IllegalArgumentException.class,
          () -> ArchetypeParsingService.parseIdentity("gsmarc://GSM/Structure/v1"));
      assertThrows(
          IllegalArgumentException.class,
          () -> ArchetypeParsingService.parseIdentity("gsmarc://gsm/foo_bar/Structure/v1"));
      assertThrows(
          IllegalArgumentException.class,
          () -> ArchetypeParsingService.parseIdentity("gsmarc://gsm/structure/v1"));
      assertThrows(
          IllegalArgumentException.class,
          () -> ArchetypeParsingService.parseIdentity("gsmarc://gsm/Structure/v0"));
      assertThrows(
          IllegalArgumentException.class,
          () -> ArchetypeParsingService.parseIdentity("gsmarc://gsm/Structure/v01"));
    }

    @Test
    void requiresExactTitleCoherence() {
      assertTrue(
          ArchetypeParsingService.hasCoherentIdentityTitle(
              "gsmarc://gsm/Structure/v1", "Structure"));
      assertFalse(
          ArchetypeParsingService.hasCoherentIdentityTitle(
              "gsmarc://gsm/Structure/v1", "structure"));
    }
  }

  // ======================================================================
  // isAllowedRef (static)
  // ======================================================================

  @Nested
  class IsAllowedRef {

    @Test
    void allowsLocalJsonPointer() {
      assertTrue(ArchetypeParsingService.isAllowedRef("#/definitions/Foo"));
    }

    @Test
    void allowsGsmUri() {
      assertTrue(ArchetypeParsingService.isAllowedRef("gsmarc://gsm/Mechanism/v1"));
    }

    @Test
    void allowsNonGsmAuthorityUri() {
      assertTrue(
          ArchetypeParsingService.isAllowedRef(
              "gsmarc://gsm-ontology/scap/cpe/ScapPlatformIdentifier/v1"));
    }

    @Test
    void rejectsExternalUri() {
      assertFalse(ArchetypeParsingService.isAllowedRef("https://example.com/schema"));
    }
  }
}
