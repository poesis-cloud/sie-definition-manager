package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AscriptionStatusTransitionRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AscriptionLifecycleOrchestrationTest {

  @Mock private AscriptionStatusTransitionRepository transitionRepo;

  @Mock private EntityManager entityManager;

  @Mock private SubtypeHandler<? extends AscriptionEntity> structureSubtype;

  @Mock private SubtypeHandler<? extends AscriptionEntity> mechanismSubtype;

  private AscriptionStatusTransitionService transitionService;
  private AscriptionStateMachineService stateMachine;
  private AscriptionLifecycleOrchestrationService orchestrator;

  @BeforeEach
  void setUp() {
    when(structureSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    when(structureSubtype.getCascadeTargetRoles()).thenReturn(Map.of());

    when(mechanismSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
    when(mechanismSubtype.getCascadeTargetRoles())
        .thenReturn(
            Map.of(
                DefinitionSubjectType.STRUCTURE, AscriptionStatusTransitionCascadeType.GOVERNING));

    transitionService = new AscriptionStatusTransitionService(transitionRepo, entityManager);
    stateMachine = new AscriptionStateMachineService(transitionService);
    orchestrator =
        new AscriptionLifecycleOrchestrationService(
            stateMachine, entityManager, List.of(structureSubtype, mechanismSubtype));
    orchestrator.afterSingletonsInstantiated();
  }

  // ========================================================================
  // State machine: valid transitions (through orchestrator.transition())
  // ========================================================================

  @Nested
  class ValidTransitions {

    @ParameterizedTest
    @CsvSource({
      "DRAFT, PROPOSED",
      "DRAFT, ABANDONED",
      "PROPOSED, APPROVED",
      "PROPOSED, REJECTED",
      "APPROVED, ACTIVE",
      "ACTIVE, SUSPENDED",
      "ACTIVE, DEPRECATED",
      "SUSPENDED, ACTIVE",
      "SUSPENDED, DEPRECATED",
      "DEPRECATED, SUSPENDED",
      "DEPRECATED, RETIRED"
    })
    void validTransition_succeeds(String from, String to) {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.valueOf(from));

      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);
      stubRepoSave();

      assertDoesNotThrow(() -> orchestrator.transition(id, to));
    }
  }

  // ========================================================================
  // State machine: invalid transitions
  // ========================================================================

  @Nested
  class InvalidTransitions {

    @ParameterizedTest
    @CsvSource({
      "DRAFT, ACTIVE",
      "DRAFT, REJECTED",
      "PROPOSED, ACTIVE",
      "PROPOSED, ABANDONED",
      "APPROVED, DEPRECATED",
      "APPROVED, SUSPENDED",
      "ACTIVE, DRAFT",
      "ACTIVE, RETIRED",
      "RETIRED, ACTIVE",
      "ABANDONED, DRAFT",
      "REJECTED, PROPOSED"
    })
    void invalidTransition_rejected(String from, String to) {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.valueOf(from));

      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> orchestrator.transition(id, to));
      assertTrue(
          ex.getMessage().contains("Invalid transition")
              || ex.getMessage().contains("terminal state"),
          "Expected 'Invalid transition' or 'terminal state' but got: " + ex.getMessage());
      AscriptionStatusTransitionRuleType expectedRule =
          Set.of("RETIRED", "ABANDONED", "REJECTED").contains(from)
              ? AscriptionStatusTransitionRuleType
                  .ASCRIPTION_STATUS_TRANSITION_TERMINAL_IMMUTABILITY
              : AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_PATH;
      assertEquals(expectedRule, ex.getRuleType());
    }
  }

  // ========================================================================
  // Entity not found
  // ========================================================================

  @Test
  void transitionOnUnknownId_rejected() {
    UUID id = UUID.randomUUID();
    when(entityManager.find(AscriptionEntity.class, id)).thenReturn(null);

    ResourceNotFoundException ex =
        assertThrows(
            ResourceNotFoundException.class, () -> orchestrator.transition(id, "PROPOSED"));
    assertTrue(ex.getMessage().contains("not found"));
  }

  // ========================================================================
  // Referee preconditions (through transition)
  // ========================================================================

  @Nested
  class RefereePreconditions {

    @Test
    void approvedToActive_refActive_allowed() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.MECHANISM, AscriptionStatusType.APPROVED);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      AscriptionEntity structureRef =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE);
      when(mechanismSubtype.getRefereeReferences(entity))
          .thenReturn(List.of(Map.entry(structureRef, "structure")));

      stubRepoSave();

      assertDoesNotThrow(() -> orchestrator.transition(id, "ACTIVE"));
    }

    @Test
    void approvedToActive_refDraft_blocked() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.MECHANISM, AscriptionStatusType.APPROVED);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      AscriptionEntity structureRef =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT);
      when(mechanismSubtype.getRefereeReferences(entity))
          .thenReturn(List.of(Map.entry(structureRef, "structure")));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> orchestrator.transition(id, "ACTIVE"));
      assertTrue(ex.getMessage().contains("Referee"));
      assertTrue(ex.getMessage().contains("structure"));
      assertEquals(
          AscriptionStatusTransitionRuleType
              .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
    }

    @Test
    void draftToProposed_refProposed_allowed() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.MECHANISM, AscriptionStatusType.DRAFT);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      AscriptionEntity structureRef =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.PROPOSED);
      when(mechanismSubtype.getRefereeReferences(entity))
          .thenReturn(List.of(Map.entry(structureRef, "structure")));

      stubRepoSave();

      assertDoesNotThrow(() -> orchestrator.transition(id, "PROPOSED"));
    }

    @Test
    void draftToProposed_refDraft_blocked() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.MECHANISM, AscriptionStatusType.DRAFT);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      AscriptionEntity structureRef =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT);
      when(mechanismSubtype.getRefereeReferences(entity))
          .thenReturn(List.of(Map.entry(structureRef, "structure")));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> orchestrator.transition(id, "PROPOSED"));
      assertTrue(ex.getMessage().contains("Referee"));
      assertEquals(
          AscriptionStatusTransitionRuleType
              .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
    }

    @Test
    void proposedToApproved_refApproved_allowed() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.MECHANISM, AscriptionStatusType.PROPOSED);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      AscriptionEntity structureRef =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.APPROVED);
      when(mechanismSubtype.getRefereeReferences(entity))
          .thenReturn(List.of(Map.entry(structureRef, "structure")));

      stubRepoSave();

      assertDoesNotThrow(() -> orchestrator.transition(id, "APPROVED"));
    }

    @Test
    void proposedToApproved_refProposed_blocked() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.MECHANISM, AscriptionStatusType.PROPOSED);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      AscriptionEntity structureRef =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.PROPOSED);
      when(mechanismSubtype.getRefereeReferences(entity))
          .thenReturn(List.of(Map.entry(structureRef, "structure")));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> orchestrator.transition(id, "APPROVED"));
      assertTrue(ex.getMessage().contains("Referee"));
      assertEquals(
          AscriptionStatusTransitionRuleType
              .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
    }

    @Test
    void activeToSuspended_refSuspended_allowed() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      AscriptionEntity structureRef =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.SUSPENDED);
      when(mechanismSubtype.getRefereeReferences(entity))
          .thenReturn(List.of(Map.entry(structureRef, "structure")));

      stubRepoSave();

      assertDoesNotThrow(() -> orchestrator.transition(id, "SUSPENDED"));
    }

    @Test
    void activeToSuspended_refRetired_blocked() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      AscriptionEntity structureRef =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.RETIRED);
      when(mechanismSubtype.getRefereeReferences(entity))
          .thenReturn(List.of(Map.entry(structureRef, "structure")));

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> orchestrator.transition(id, "SUSPENDED"));
      assertTrue(ex.getMessage().contains("Referee"));
      assertEquals(
          AscriptionStatusTransitionRuleType
              .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
    }

    @Test
    void suspendedToActive_refActive_allowed() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.MECHANISM, AscriptionStatusType.SUSPENDED);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      AscriptionEntity structureRef =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE);
      when(mechanismSubtype.getRefereeReferences(entity))
          .thenReturn(List.of(Map.entry(structureRef, "structure")));

      stubRepoSave();

      assertDoesNotThrow(() -> orchestrator.transition(id, "ACTIVE"));
    }

    @Test
    void suspendedToActive_refSuspended_blocked() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.MECHANISM, AscriptionStatusType.SUSPENDED);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      AscriptionEntity structureRef =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.SUSPENDED);
      when(mechanismSubtype.getRefereeReferences(entity))
          .thenReturn(List.of(Map.entry(structureRef, "structure")));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> orchestrator.transition(id, "ACTIVE"));
      assertTrue(ex.getMessage().contains("Referee"));
      assertEquals(
          AscriptionStatusTransitionRuleType
              .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
    }

    @Test
    void deprecatedToRetired_refRetired_allowed() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.MECHANISM, AscriptionStatusType.DEPRECATED);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      AscriptionEntity structureRef =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.RETIRED);
      when(mechanismSubtype.getRefereeReferences(entity))
          .thenReturn(List.of(Map.entry(structureRef, "structure")));

      stubRepoSave();

      assertDoesNotThrow(() -> orchestrator.transition(id, "RETIRED"));
    }

    @Test
    void deprecatedToRetired_refAbandoned_blocked() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.MECHANISM, AscriptionStatusType.DEPRECATED);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      AscriptionEntity structureRef =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ABANDONED);
      when(mechanismSubtype.getRefereeReferences(entity))
          .thenReturn(List.of(Map.entry(structureRef, "structure")));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> orchestrator.transition(id, "RETIRED"));
      assertTrue(ex.getMessage().contains("Referee"));
      assertEquals(
          AscriptionStatusTransitionRuleType
              .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
    }

    @Test
    void proposedToRejected_refDraft_blocked() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.MECHANISM, AscriptionStatusType.PROPOSED);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      AscriptionEntity structureRef =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT);
      when(mechanismSubtype.getRefereeReferences(entity))
          .thenReturn(List.of(Map.entry(structureRef, "structure")));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> orchestrator.transition(id, "REJECTED"));
      assertTrue(ex.getMessage().contains("Referee"));
      assertEquals(
          AscriptionStatusTransitionRuleType
              .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
    }

    @Test
    void proposedToRejected_refRetired_allowed() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.MECHANISM, AscriptionStatusType.PROPOSED);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      AscriptionEntity structureRef =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.RETIRED);
      when(mechanismSubtype.getRefereeReferences(entity))
          .thenReturn(List.of(Map.entry(structureRef, "structure")));

      stubRepoSave();

      assertDoesNotThrow(() -> orchestrator.transition(id, "REJECTED"));
    }

    @Test
    void draftToAbandoned_anyRefStatus_allowed() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.MECHANISM, AscriptionStatusType.DRAFT);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      AscriptionEntity structureRef =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.RETIRED);
      when(mechanismSubtype.getRefereeReferences(entity))
          .thenReturn(List.of(Map.entry(structureRef, "structure")));

      stubRepoSave();

      assertDoesNotThrow(() -> orchestrator.transition(id, "ABANDONED"));
    }
  }

  // ========================================================================
  // Governance convergence: APPROVED → sibling termination
  // ========================================================================

  @Nested
  class GovernanceConvergence {

    @Test
    void approval_terminatesDraftSiblings() {
      UUID id = UUID.randomUUID();
      UUID defId = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.PROPOSED, defId);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);
      stubRepoSave();

      UUID sibId = UUID.randomUUID();
      AscriptionEntity draftSibling =
          stubEntity(sibId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT, defId);
      doReturn(List.of(entity, draftSibling)).when(structureSubtype).findAllByDefinitionId(defId);

      orchestrator.transition(id, "APPROVED");

      verify(transitionRepo, atLeast(2)).save(any(AscriptionStatusTransitionEntity.class));
    }

    @Test
    void approval_terminatesProposedSiblings() {
      UUID id = UUID.randomUUID();
      UUID defId = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.PROPOSED, defId);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);
      stubRepoSave();

      UUID sibId = UUID.randomUUID();
      AscriptionEntity proposedSibling =
          stubEntity(sibId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.PROPOSED, defId);
      doReturn(List.of(entity, proposedSibling))
          .when(structureSubtype)
          .findAllByDefinitionId(defId);

      orchestrator.transition(id, "APPROVED");

      verify(transitionRepo, atLeast(2)).save(any(AscriptionStatusTransitionEntity.class));
    }

    @Test
    void approval_ignoresActiveSiblings() {
      UUID id = UUID.randomUUID();
      UUID defId = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.PROPOSED, defId);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);
      stubRepoSave();

      UUID sibId = UUID.randomUUID();
      AscriptionEntity activeSibling =
          stubEntity(sibId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE, defId);
      doReturn(List.of(entity, activeSibling)).when(structureSubtype).findAllByDefinitionId(defId);

      orchestrator.transition(id, "APPROVED");

      verify(transitionRepo, times(1)).save(any(AscriptionStatusTransitionEntity.class));
    }
  }

  // ========================================================================
  // Activation: previous ACTIVE → DEPRECATED
  // ========================================================================

  @Nested
  class ActivationHandoff {

    @Test
    void activation_deprecatesPreviousActive() {
      UUID id = UUID.randomUUID();
      UUID defId = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.APPROVED, defId);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);
      stubRepoSave();

      UUID prevId = UUID.randomUUID();
      AscriptionEntity previous =
          stubEntity(prevId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE, defId);
      doReturn(List.of(previous))
          .when(structureSubtype)
          .findAllByDefinitionIdAndStatus(eq(defId), anyList());

      orchestrator.transition(id, "ACTIVE");

      verify(transitionRepo, atLeast(2)).save(any(AscriptionStatusTransitionEntity.class));
    }
  }

  // ========================================================================
  // Cascade execution
  // ========================================================================

  @Nested
  class CascadeExecution {

    @Test
    void governingCascade_propagatesTransition() {
      UUID structureId = UUID.randomUUID();
      UUID defId = UUID.randomUUID();
      AscriptionEntity structure =
          stubEntity(
              structureId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE, defId);
      when(entityManager.find(AscriptionEntity.class, structureId)).thenReturn(structure);
      stubRepoSave();

      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE);
      doReturn(List.of(mechanism))
          .when(mechanismSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, structureId);

      orchestrator.transition(structureId, "DEPRECATED");

      verify(transitionRepo, atLeast(2)).save(any(AscriptionStatusTransitionEntity.class));
    }

    @Test
    void governingCascade_statusMismatch_noOp() {
      UUID structureId = UUID.randomUUID();
      UUID defId = UUID.randomUUID();
      AscriptionEntity structure =
          stubEntity(
              structureId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE, defId);
      when(entityManager.find(AscriptionEntity.class, structureId)).thenReturn(structure);
      stubRepoSave();

      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.DRAFT);
      doReturn(List.of(mechanism))
          .when(mechanismSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, structureId);

      orchestrator.transition(structureId, "DEPRECATED");

      verify(transitionRepo, times(1)).save(any(AscriptionStatusTransitionEntity.class));
    }
  }

  // ========================================================================
  // Dependent cascade scope
  // ========================================================================

  @Nested
  class DependentCascadeScope {

    private SubtypeHandler<? extends AscriptionEntity> effectorSubtype;
    private AscriptionLifecycleOrchestrationService orchestratorWithDependent;

    @BeforeEach
    @SuppressWarnings("unchecked") // Mockito mock() erases SubtypeHandler generic;
    // unavoidable — Java generics are not reified at runtime
    void setUpDependentCascade() {
      SubtypeHandler<? extends AscriptionEntity> mechSvc = mock(SubtypeHandler.class);
      when(mechSvc.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
      when(mechSvc.getCascadeTargetRoles())
          .thenReturn(
              Map.of(
                  DefinitionSubjectType.STRUCTURE,
                  AscriptionStatusTransitionCascadeType.GOVERNING));

      effectorSubtype = mock(SubtypeHandler.class);
      when(effectorSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.EFFECTOR);
      when(effectorSubtype.getCascadeTargetRoles())
          .thenReturn(
              Map.of(
                  DefinitionSubjectType.MECHANISM,
                  AscriptionStatusTransitionCascadeType.DEPENDENT));

      SubtypeHandler<? extends AscriptionEntity> structSvc = mock(SubtypeHandler.class);
      when(structSvc.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
      when(structSvc.getCascadeTargetRoles()).thenReturn(Map.of());

      orchestratorWithDependent =
          new AscriptionLifecycleOrchestrationService(
              stateMachine, entityManager, List.of(structSvc, mechSvc, effectorSubtype));
      orchestratorWithDependent.afterSingletonsInstantiated();
    }

    @Test
    void activeToDeprecated_dependentCascadeFires() {
      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE);
      when(entityManager.find(AscriptionEntity.class, mechId)).thenReturn(mechanism);
      stubRepoSave();

      UUID effId = UUID.randomUUID();
      AscriptionEntity effector =
          stubEntity(effId, DefinitionSubjectType.EFFECTOR, AscriptionStatusType.ACTIVE);
      doReturn(List.of(effector))
          .when(effectorSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechId);

      orchestratorWithDependent.transition(mechId, "DEPRECATED");

      verify(transitionRepo, atLeast(2)).save(any(AscriptionStatusTransitionEntity.class));
    }

    @Test
    void activeToSuspended_dependentCascadeFires() {
      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE);
      when(entityManager.find(AscriptionEntity.class, mechId)).thenReturn(mechanism);
      stubRepoSave();

      UUID effId = UUID.randomUUID();
      AscriptionEntity effector =
          stubEntity(effId, DefinitionSubjectType.EFFECTOR, AscriptionStatusType.ACTIVE);
      doReturn(List.of(effector))
          .when(effectorSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechId);

      orchestratorWithDependent.transition(mechId, "SUSPENDED");

      verify(transitionRepo, atLeast(2)).save(any(AscriptionStatusTransitionEntity.class));
    }

    @Test
    void proposedToApproved_dependentCascadeDoesNotFire() {
      UUID mechId = UUID.randomUUID();
      UUID defId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.PROPOSED, defId);
      when(entityManager.find(AscriptionEntity.class, mechId)).thenReturn(mechanism);
      stubRepoSave();

      UUID effId = UUID.randomUUID();
      AscriptionEntity effector =
          stubEntity(effId, DefinitionSubjectType.EFFECTOR, AscriptionStatusType.PROPOSED);
      doReturn(List.of(effector))
          .when(effectorSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechId);

      orchestratorWithDependent.transition(mechId, "APPROVED");

      verify(transitionRepo, times(1)).save(any(AscriptionStatusTransitionEntity.class));
    }

    @Test
    void draftToAbandoned_dependentCascadeFires() {
      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.DRAFT);
      when(entityManager.find(AscriptionEntity.class, mechId)).thenReturn(mechanism);
      stubRepoSave();

      UUID effId = UUID.randomUUID();
      AscriptionEntity effector =
          stubEntity(effId, DefinitionSubjectType.EFFECTOR, AscriptionStatusType.DRAFT);
      doReturn(List.of(effector))
          .when(effectorSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechId);

      orchestratorWithDependent.transition(mechId, "ABANDONED");

      verify(transitionRepo, atLeast(2)).save(any(AscriptionStatusTransitionEntity.class));
    }

    @Test
    void dependentCascade_statusMismatch_noOp() {
      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE);
      when(entityManager.find(AscriptionEntity.class, mechId)).thenReturn(mechanism);
      stubRepoSave();

      UUID effId = UUID.randomUUID();
      AscriptionEntity effector =
          stubEntity(effId, DefinitionSubjectType.EFFECTOR, AscriptionStatusType.DRAFT);
      doReturn(List.of(effector))
          .when(effectorSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechId);

      orchestratorWithDependent.transition(mechId, "DEPRECATED");

      verify(transitionRepo, times(1)).save(any(AscriptionStatusTransitionEntity.class));
    }
  }

  // ========================================================================
  // Constitutive cascade
  // ========================================================================

  @Nested
  class ConstitutiveCascade {

    private SubtypeHandler<? extends AscriptionEntity> effectorSubtype;
    private AscriptionLifecycleOrchestrationService orchestratorWithConstitutive;

    @BeforeEach
    @SuppressWarnings("unchecked") // Mockito mock() erases SubtypeHandler generic;
    // unavoidable — Java generics are not reified at runtime
    void setUpConstitutiveCascade() {
      SubtypeHandler<? extends AscriptionEntity> mechSvc = mock(SubtypeHandler.class);
      when(mechSvc.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
      when(mechSvc.getCascadeTargetRoles()).thenReturn(Map.of());

      effectorSubtype = mock(SubtypeHandler.class);
      when(effectorSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.EFFECTOR);
      when(effectorSubtype.getCascadeTargetRoles())
          .thenReturn(
              Map.of(
                  DefinitionSubjectType.MECHANISM,
                  AscriptionStatusTransitionCascadeType.CONSTITUTIVE));

      SubtypeHandler<? extends AscriptionEntity> structSvc = mock(SubtypeHandler.class);
      when(structSvc.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
      when(structSvc.getCascadeTargetRoles()).thenReturn(Map.of());

      orchestratorWithConstitutive =
          new AscriptionLifecycleOrchestrationService(
              stateMachine, entityManager, List.of(structSvc, mechSvc, effectorSubtype));
      orchestratorWithConstitutive.afterSingletonsInstantiated();
    }

    @Test
    void constitutiveCascade_statusMismatch_throws() {
      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE);
      when(entityManager.find(AscriptionEntity.class, mechId)).thenReturn(mechanism);
      stubRepoSave();

      UUID effId = UUID.randomUUID();
      AscriptionEntity effector =
          stubEntity(effId, DefinitionSubjectType.EFFECTOR, AscriptionStatusType.DRAFT);
      doReturn(List.of(effector))
          .when(effectorSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechId);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> orchestratorWithConstitutive.transition(mechId, "DEPRECATED"));
      assertTrue(ex.getMessage().contains("Constitutive cascade failed"));
      assertEquals(
          AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_CONSTITUENTS,
          ex.getRuleType());
    }

    @Test
    void constitutiveCascade_statusMatch_propagates() {
      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE);
      when(entityManager.find(AscriptionEntity.class, mechId)).thenReturn(mechanism);
      stubRepoSave();

      UUID effId = UUID.randomUUID();
      AscriptionEntity effector =
          stubEntity(effId, DefinitionSubjectType.EFFECTOR, AscriptionStatusType.ACTIVE);
      doReturn(List.of(effector))
          .when(effectorSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechId);

      orchestratorWithConstitutive.transition(mechId, "DEPRECATED");

      verify(transitionRepo, atLeast(2)).save(any(AscriptionStatusTransitionEntity.class));
    }

    @Test
    void constitutiveCascade_refereePreconditionFails_rethrows() {
      UUID mechId = UUID.randomUUID();
      UUID defId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE, defId);
      when(entityManager.find(AscriptionEntity.class, mechId)).thenReturn(mechanism);
      stubRepoSave();

      UUID effId = UUID.randomUUID();
      AscriptionEntity effector =
          stubEntity(effId, DefinitionSubjectType.EFFECTOR, AscriptionStatusType.ACTIVE, defId);
      doReturn(List.of(effector))
          .when(effectorSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechId);

      // Make referee precondition fail for the target (effector has a referee in bad
      // status)
      AscriptionEntity badReferee =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT);
      doReturn(List.of(Map.entry(badReferee, "structure")))
          .when(effectorSubtype)
          .getRefereeReferences(effector);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> orchestratorWithConstitutive.transition(mechId, "DEPRECATED"));
      assertTrue(ex.getMessage().contains("Constitutive cascade blocked"));
    }
  }

  // ========================================================================
  // Deactivation hook
  // ========================================================================

  @Nested
  class DeactivationHook {

    @Test
    @SuppressWarnings("unchecked")
    void activeToSuspended_callsOnDeactivation() {
      UUID entityId = UUID.randomUUID();
      UUID defId = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(entityId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE, defId);
      when(entityManager.find(AscriptionEntity.class, entityId)).thenReturn(entity);
      stubRepoSave();

      orchestrator.transition(entityId, "SUSPENDED");

      verify(structureSubtype).onDeactivation(entity);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deprecatedToRetired_callsOnDeactivation() {
      UUID entityId = UUID.randomUUID();
      UUID defId = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(
              entityId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DEPRECATED, defId);
      when(entityManager.find(AscriptionEntity.class, entityId)).thenReturn(entity);
      stubRepoSave();

      orchestrator.transition(entityId, "RETIRED");

      verify(structureSubtype).onDeactivation(entity);
    }
  }

  // ========================================================================
  // Governing cascade — referee precondition skips
  // ========================================================================

  @Nested
  class GoverningCascadeRefereePrecondition {

    @Test
    @SuppressWarnings("unchecked")
    void governingCascade_refereePreconditionFails_skipsTarget() {
      UUID structureId = UUID.randomUUID();
      UUID defId = UUID.randomUUID();
      AscriptionEntity structure =
          stubEntity(
              structureId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE, defId);
      when(entityManager.find(AscriptionEntity.class, structureId)).thenReturn(structure);
      stubRepoSave();

      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE);
      doReturn(List.of(mechanism))
          .when(mechanismSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, structureId);

      // mechanism has a referee in bad status → precondition fails → skip
      AscriptionEntity badReferee =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT);
      doReturn(List.of(Map.entry(badReferee, "other-structure")))
          .when(mechanismSubtype)
          .getRefereeReferences(mechanism);

      // Should not throw — governing cascade is skipped on referee failure
      assertDoesNotThrow(() -> orchestrator.transition(structureId, "DEPRECATED"));

      // Only 1 transition recorded (the source), not 2
      verify(transitionRepo, times(1)).save(any(AscriptionStatusTransitionEntity.class));
    }
  }

  // ========================================================================
  // Dependent cascade — referee precondition skips
  // ========================================================================

  @Nested
  class DependentCascadeRefereePrecondition {

    private SubtypeHandler<? extends AscriptionEntity> effectorSubtype;
    private AscriptionLifecycleOrchestrationService orchestratorWithDependent;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
      SubtypeHandler<? extends AscriptionEntity> mechSvc = mock(SubtypeHandler.class);
      when(mechSvc.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
      when(mechSvc.getCascadeTargetRoles())
          .thenReturn(
              Map.of(
                  DefinitionSubjectType.STRUCTURE,
                  AscriptionStatusTransitionCascadeType.GOVERNING));

      effectorSubtype = mock(SubtypeHandler.class);
      when(effectorSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.EFFECTOR);
      when(effectorSubtype.getCascadeTargetRoles())
          .thenReturn(
              Map.of(
                  DefinitionSubjectType.MECHANISM,
                  AscriptionStatusTransitionCascadeType.DEPENDENT));

      SubtypeHandler<? extends AscriptionEntity> structSvc = mock(SubtypeHandler.class);
      when(structSvc.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
      when(structSvc.getCascadeTargetRoles()).thenReturn(Map.of());

      orchestratorWithDependent =
          new AscriptionLifecycleOrchestrationService(
              stateMachine, entityManager, List.of(structSvc, mechSvc, effectorSubtype));
      orchestratorWithDependent.afterSingletonsInstantiated();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dependentCascade_refereePreconditionFails_skipsTarget() {
      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE);
      when(entityManager.find(AscriptionEntity.class, mechId)).thenReturn(mechanism);
      stubRepoSave();

      UUID effId = UUID.randomUUID();
      AscriptionEntity effector =
          stubEntity(effId, DefinitionSubjectType.EFFECTOR, AscriptionStatusType.ACTIVE);
      doReturn(List.of(effector))
          .when(effectorSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechId);

      // effector has a referee in bad status → precondition fails → skip
      AscriptionEntity badReferee =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.MECHANISM, AscriptionStatusType.DRAFT);
      doReturn(List.of(Map.entry(badReferee, "mechanism")))
          .when(effectorSubtype)
          .getRefereeReferences(effector);

      assertDoesNotThrow(() -> orchestratorWithDependent.transition(mechId, "DEPRECATED"));

      // Only 1 transition (source), not 2
      verify(transitionRepo, times(1)).save(any(AscriptionStatusTransitionEntity.class));
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private AscriptionEntity stubEntity(
      UUID id, DefinitionSubjectType subjectType, AscriptionStatusType status) {
    return stubEntity(id, subjectType, status, UUID.randomUUID());
  }

  private AscriptionEntity stubEntity(
      UUID id, DefinitionSubjectType subjectType, AscriptionStatusType status, UUID defId) {
    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(defId);
    when(def.getSubjectType()).thenReturn(subjectType);

    AscriptionEntity entity = mock(AscriptionEntity.class);
    when(entity.getId()).thenReturn(id);
    when(entity.getDefinition()).thenReturn(def);
    when(entity.getStatus()).thenReturn(status);

    return entity;
  }

  /** Stubs transitionRepo.save() and findById() to support recordTransition(). */
  private void stubRepoSave() {
    AscriptionStatusTransitionEntity saved = mock(AscriptionStatusTransitionEntity.class);
    UUID transitionId = UUID.randomUUID();
    when(saved.getId()).thenReturn(transitionId);
    when(transitionRepo.save(any(AscriptionStatusTransitionEntity.class))).thenReturn(saved);
    when(transitionRepo.findById(transitionId)).thenReturn(Optional.of(saved));
  }
}
