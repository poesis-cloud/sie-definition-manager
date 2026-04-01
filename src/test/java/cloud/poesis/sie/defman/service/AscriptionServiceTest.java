package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AscriptionServiceTest {

  @Mock private AscriptionRepository ascriptionRepository;

  private AscriptionService service;

  @BeforeEach
  void setUp() {
    service = new AscriptionService(ascriptionRepository);
  }

  @Test
  void getById_returnsEntity_whenFound() {
    UUID id = UUID.randomUUID();
    AscriptionEntity entity = org.mockito.Mockito.mock(AscriptionEntity.class);
    when(ascriptionRepository.findById(id)).thenReturn(Optional.of(entity));

    AscriptionEntity result = service.getById(id);

    assertNotNull(result);
    assertEquals(entity, result);
  }

  @Test
  void getById_throwsResourceNotFound_whenNotFound() {
    UUID id = UUID.randomUUID();
    when(ascriptionRepository.findById(id)).thenReturn(Optional.empty());

    ResourceNotFoundException ex =
        assertThrows(ResourceNotFoundException.class, () -> service.getById(id));
    assertEquals(id, ex.getResourceId());
  }

  @Test
  void findAllByArchetypeIdAndStatusInAndDefinitionIdNot_delegatesToRepo() {
    UUID archetypeId = UUID.randomUUID();
    UUID excludeDefId = UUID.randomUUID();
    List<AscriptionStatusType> statuses =
        List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);
    AscriptionEntity entity = org.mockito.Mockito.mock(AscriptionEntity.class);
    when(ascriptionRepository.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
            archetypeId, statuses, excludeDefId))
        .thenReturn(List.of(entity));

    List<AscriptionEntity> result =
        service.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
            archetypeId, statuses, excludeDefId);

    assertEquals(1, result.size());
    assertEquals(entity, result.get(0));
  }
}
