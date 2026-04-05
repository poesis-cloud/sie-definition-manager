package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.InternalException;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.repository.DefinitionRepository;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefinitionServiceTest {

  @Mock private DefinitionRepository definitionRepository;

  private DefinitionService service;

  @BeforeEach
  void setUp() {
    service = new DefinitionService(definitionRepository);
  }

  private static DefinitionEntity stubDefinition(
      UUID id, DefinitionSubjectType type, boolean withAscriptions) {
    DefinitionEntity entity = mock(DefinitionEntity.class);
    org.mockito.Mockito.lenient().when(entity.getId()).thenReturn(id);
    org.mockito.Mockito.lenient().when(entity.getSubjectType()).thenReturn(type);
    if (withAscriptions) {
      when(entity.getAscriptions()).thenReturn(List.of(mock(AscriptionEntity.class)));
    } else {
      when(entity.getAscriptions()).thenReturn(Collections.emptyList());
    }
    return entity;
  }

  @Nested
  class GetById {

    @Test
    void returnsEntity_whenFoundWithAscriptions() {
      UUID id = UUID.randomUUID();
      DefinitionEntity entity = stubDefinition(id, DefinitionSubjectType.STRUCTURE, true);
      when(definitionRepository.findById(id)).thenReturn(Optional.of(entity));

      DefinitionEntity result = service.getById(id);

      assertNotNull(result);
      assertEquals(entity, result);
    }

    @Test
    void throwsResourceNotFound_whenNotFound() {
      UUID id = UUID.randomUUID();
      when(definitionRepository.findById(id)).thenReturn(Optional.empty());

      assertThrows(ResourceNotFoundException.class, () -> service.getById(id));
    }

    @Test
    void throwsInternalException_whenNoAscriptions() {
      UUID id = UUID.randomUUID();
      DefinitionEntity entity = stubDefinition(id, DefinitionSubjectType.STRUCTURE, false);
      when(definitionRepository.findById(id)).thenReturn(Optional.of(entity));

      InternalException ex = assertThrows(InternalException.class, () -> service.getById(id));
      assertTrue(ex.getMessage().contains("no ascriptions"));
    }
  }

  @Nested
  class GetByIdWithArchetypes {

    @Test
    void returnsEntity_whenFoundWithAscriptions() {
      UUID id = UUID.randomUUID();
      DefinitionEntity entity = stubDefinition(id, DefinitionSubjectType.ARCHETYPE, true);
      when(definitionRepository.findWithAscriptionArchetypesById(id))
          .thenReturn(Optional.of(entity));

      DefinitionEntity result = service.getByIdWithArchetypes(id);

      assertNotNull(result);
      assertEquals(entity, result);
    }

    @Test
    void throwsResourceNotFound_whenNotFound() {
      UUID id = UUID.randomUUID();
      when(definitionRepository.findWithAscriptionArchetypesById(id)).thenReturn(Optional.empty());

      assertThrows(ResourceNotFoundException.class, () -> service.getByIdWithArchetypes(id));
    }

    @Test
    void throwsInternalException_whenNoAscriptions() {
      UUID id = UUID.randomUUID();
      DefinitionEntity entity = stubDefinition(id, DefinitionSubjectType.MECHANISM, false);
      when(definitionRepository.findWithAscriptionArchetypesById(id))
          .thenReturn(Optional.of(entity));

      InternalException ex =
          assertThrows(InternalException.class, () -> service.getByIdWithArchetypes(id));
      assertTrue(ex.getMessage().contains("no ascriptions"));
    }
  }

  @Nested
  class Resolve {

    @Test
    void resolvesExisting_whenIdProvided() {
      UUID id = UUID.randomUUID();
      DefinitionEntity entity = stubDefinition(id, DefinitionSubjectType.DIRECTIVE, true);
      when(definitionRepository.findById(id)).thenReturn(Optional.of(entity));

      DefinitionEntity result = service.resolve(id, DefinitionSubjectType.DIRECTIVE);

      assertEquals(entity, result);
    }

    @Test
    void throwsResourceNotFound_whenIdProvidedButNotFound() {
      UUID id = UUID.randomUUID();
      when(definitionRepository.findById(id)).thenReturn(Optional.empty());

      assertThrows(
          ResourceNotFoundException.class,
          () -> service.resolve(id, DefinitionSubjectType.STRUCTURE));
    }

    @Test
    void createsNew_whenIdIsNull() {
      DefinitionEntity saved = new DefinitionEntity(DefinitionSubjectType.INTERACTION);
      when(definitionRepository.save(any(DefinitionEntity.class))).thenReturn(saved);

      DefinitionEntity result = service.resolve(null, DefinitionSubjectType.INTERACTION);

      assertNotNull(result);
      verify(definitionRepository).save(any(DefinitionEntity.class));
    }
  }
}
