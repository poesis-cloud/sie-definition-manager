package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.*;
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
  // findInEffectByTitle
  // ======================================================================

  @Nested
  class FindInEffectByTitle {

    @Test
    void delegatesToRepository() {
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(archetypeRepository.findInEffectByTitle("StructureArchetype"))
          .thenReturn(Optional.of(entity));

      Optional<ArchetypeEntity> result = service.findInEffectByTitle("StructureArchetype");

      assertTrue(result.isPresent());
      assertSame(entity, result.get());
      verify(archetypeRepository).findInEffectByTitle("StructureArchetype");
    }

    @Test
    void returnsEmptyWhenNotFound() {
      when(archetypeRepository.findInEffectByTitle("Unknown")).thenReturn(Optional.empty());

      Optional<ArchetypeEntity> result = service.findInEffectByTitle("Unknown");

      assertTrue(result.isEmpty());
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
          "StructureArchetype",
          ArchetypeParsingService.extractTitleFromRef("gsm://archetypes/StructureArchetype/v1"));
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
      assertTrue(ArchetypeParsingService.isAllowedRef("gsm://archetypes/MechanismArchetype/v1"));
    }

    @Test
    void rejectsExternalUri() {
      assertFalse(ArchetypeParsingService.isAllowedRef("https://example.com/schema"));
    }
  }

  // ======================================================================
  // isGsmBaseTitle (static)
  // ======================================================================

  @Nested
  class IsGsmBaseTitle {

    @Test
    void returnsTrueForBaseTitle() {
      assertTrue(ArchetypeParsingService.isGsmBaseTitle("StructureArchetype"));
      assertTrue(ArchetypeParsingService.isGsmBaseTitle("Archetype"));
    }

    @Test
    void returnsFalseForNonBaseTitle() {
      assertFalse(ArchetypeParsingService.isGsmBaseTitle("CustomArchetype"));
    }
  }
}
