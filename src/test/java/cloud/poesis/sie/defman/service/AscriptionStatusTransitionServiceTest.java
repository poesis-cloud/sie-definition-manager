package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import cloud.poesis.sie.defman.repository.AscriptionStatusTransitionRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link AscriptionStatusTransitionService} — persistence and query operations.
 * Lifecycle orchestration is tested in {@link AscriptionStatusTransitionLifecycleTest}.
 */
@ExtendWith(MockitoExtension.class)
class AscriptionStatusTransitionServiceTest {

  @Mock private AscriptionStatusTransitionRepository transitionRepo;
  @Mock private EntityManager entityManager;
  @Mock private AscriptionStateMachineService stateMachine;

  private AscriptionStatusTransitionService service;

  @BeforeEach
  void setUp() {
    service =
        new AscriptionStatusTransitionService(
            transitionRepo, stateMachine, entityManager, List.of());
  }

  // ========================================================================
  // RecordTransition
  // ========================================================================
  // GetTransitions
  // ========================================================================

  @Nested
  class GetTransitions {

    @Test
    void returnsOrderedList() {
      UUID ascriptionId = UUID.randomUUID();
      List<AscriptionStatusTransitionEntity> expected =
          List.of(mock(AscriptionStatusTransitionEntity.class));
      when(transitionRepo.findAllByAscriptionIdOrderByTimestampAsc(ascriptionId))
          .thenReturn(expected);

      List<AscriptionStatusTransitionEntity> result = service.getTransitions(ascriptionId);

      assertEquals(expected, result);
    }

    @Test
    void returnsEmptyListWhenNone() {
      UUID ascriptionId = UUID.randomUUID();
      when(transitionRepo.findAllByAscriptionIdOrderByTimestampAsc(ascriptionId))
          .thenReturn(List.of());

      List<AscriptionStatusTransitionEntity> result = service.getTransitions(ascriptionId);

      assertTrue(result.isEmpty());
    }
  }

  // ========================================================================
  // GetTransition
  // ========================================================================

  @Nested
  class GetTransition {

    @Test
    void returnsOptionalWhenFound() {
      UUID transitionId = UUID.randomUUID();
      UUID ascriptionId = UUID.randomUUID();
      AscriptionStatusTransitionEntity entity = mock(AscriptionStatusTransitionEntity.class);
      when(transitionRepo.findByIdAndAscriptionId(transitionId, ascriptionId))
          .thenReturn(Optional.of(entity));

      Optional<AscriptionStatusTransitionEntity> result =
          service.getTransition(transitionId, ascriptionId);

      assertTrue(result.isPresent());
      assertEquals(entity, result.get());
    }

    @Test
    void returnsEmptyWhenNotFound() {
      UUID transitionId = UUID.randomUUID();
      UUID ascriptionId = UUID.randomUUID();
      when(transitionRepo.findByIdAndAscriptionId(transitionId, ascriptionId))
          .thenReturn(Optional.empty());

      Optional<AscriptionStatusTransitionEntity> result =
          service.getTransition(transitionId, ascriptionId);

      assertTrue(result.isEmpty());
    }
  }
}
