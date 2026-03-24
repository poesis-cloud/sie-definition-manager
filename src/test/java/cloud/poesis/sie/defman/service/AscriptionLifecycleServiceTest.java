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
import cloud.poesis.sie.defman.service.AbstractAscriptionService.RefereeReference;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.RuleType;
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
class AscriptionLifecycleServiceTest {

  @Mock private AscriptionStatusTransitionService transitionService;

  @Mock private EntityManager entityManager;

  @Mock private AbstractAscriptionService structureSubtype;

  @Mock private AbstractAscriptionService mechanismSubtype;

  private AscriptionLifecycleService service;

  @BeforeEach
  void setUp() {
    // Minimal subtype setup: Structure + Mechanism
    when(structureSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    when(structureSubtype.getCascadeTargetRoles()).thenReturn(Map.of());

    when(mechanismSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
    when(mechanismSubtype.getCascadeTargetRoles())
        .thenReturn(
            Map.of(
                DefinitionSubjectType.STRUCTURE, AscriptionStatusTransitionCascadeType.GOVERNING));

    service =
        new AscriptionLifecycleService(
            List.of(structureSubtype, mechanismSubtype), transitionService, entityManager);
  }

  // ========================================================================
  // State machine: valid transitions
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
      stubTransitionSave();

      assertDoesNotThrow(() -> service.transition(id, to));
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
          assertThrows(RuleViolationException.class, () -> service.transition(id, to));
      assertTrue(
          ex.getMessage().contains("Invalid transition")
              || ex.getMessage().contains("terminal state"),
          "Expected 'Invalid transition' or 'terminal state' but got: " + ex.getMessage());
      RuleType expectedRule =
          Set.of("RETIRED", "ABANDONED", "REJECTED").contains(from)
              ? RuleType.ASCRIPTION_STATUS_TRANSITION_TERMINAL_IMMUTABILITY
              : RuleType.ASCRIPTION_STATUS_TRANSITION_PATH;
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
        assertThrows(ResourceNotFoundException.class, () -> service.transition(id, "PROPOSED"));
    assertTrue(ex.getMessage().contains("not found"));
  }

  // ========================================================================
  // Referee preconditions
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
          .thenReturn(List.of(new RefereeReference(structureRef, "structure")));

      stubTransitionSave();

      assertDoesNotThrow(() -> service.transition(id, "ACTIVE"));
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
          .thenReturn(List.of(new RefereeReference(structureRef, "structure")));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.transition(id, "ACTIVE"));
      assertTrue(ex.getMessage().contains("Referee"));
      assertTrue(ex.getMessage().contains("structure"));
      assertEquals(
          RuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
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
          .thenReturn(List.of(new RefereeReference(structureRef, "structure")));

      stubTransitionSave();

      assertDoesNotThrow(() -> service.transition(id, "PROPOSED"));
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
          .thenReturn(List.of(new RefereeReference(structureRef, "structure")));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.transition(id, "PROPOSED"));
      assertTrue(ex.getMessage().contains("Referee"));
      assertEquals(
          RuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
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
          .thenReturn(List.of(new RefereeReference(structureRef, "structure")));

      stubTransitionSave();

      assertDoesNotThrow(() -> service.transition(id, "APPROVED"));
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
          .thenReturn(List.of(new RefereeReference(structureRef, "structure")));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.transition(id, "APPROVED"));
      assertTrue(ex.getMessage().contains("Referee"));
      assertEquals(
          RuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
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
          .thenReturn(List.of(new RefereeReference(structureRef, "structure")));

      stubTransitionSave();

      assertDoesNotThrow(() -> service.transition(id, "SUSPENDED"));
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
          .thenReturn(List.of(new RefereeReference(structureRef, "structure")));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.transition(id, "SUSPENDED"));
      assertTrue(ex.getMessage().contains("Referee"));
      assertEquals(
          RuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
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
          .thenReturn(List.of(new RefereeReference(structureRef, "structure")));

      stubTransitionSave();

      assertDoesNotThrow(() -> service.transition(id, "ACTIVE"));
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
          .thenReturn(List.of(new RefereeReference(structureRef, "structure")));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.transition(id, "ACTIVE"));
      assertTrue(ex.getMessage().contains("Referee"));
      assertEquals(
          RuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
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
          .thenReturn(List.of(new RefereeReference(structureRef, "structure")));

      stubTransitionSave();

      assertDoesNotThrow(() -> service.transition(id, "RETIRED"));
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
          .thenReturn(List.of(new RefereeReference(structureRef, "structure")));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.transition(id, "RETIRED"));
      assertTrue(ex.getMessage().contains("Referee"));
      assertEquals(
          RuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
    }

    @Test
    void proposedToRejected_refDraft_blocked() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.MECHANISM, AscriptionStatusType.PROPOSED);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      // SC-23: DRAFT referee blocks PROPOSED→REJECTED
      AscriptionEntity structureRef =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT);
      when(mechanismSubtype.getRefereeReferences(entity))
          .thenReturn(List.of(new RefereeReference(structureRef, "structure")));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.transition(id, "REJECTED"));
      assertTrue(ex.getMessage().contains("Referee"));
      assertEquals(
          RuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
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
          .thenReturn(List.of(new RefereeReference(structureRef, "structure")));

      stubTransitionSave();

      assertDoesNotThrow(() -> service.transition(id, "REJECTED"));
    }

    @Test
    void draftToAbandoned_anyRefStatus_allowed() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.MECHANISM, AscriptionStatusType.DRAFT);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

      // DRAFT→ABANDONED allows ANY referee status
      AscriptionEntity structureRef =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.RETIRED);
      when(mechanismSubtype.getRefereeReferences(entity))
          .thenReturn(List.of(new RefereeReference(structureRef, "structure")));

      stubTransitionSave();

      assertDoesNotThrow(() -> service.transition(id, "ABANDONED"));
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
      stubTransitionSave();

      // Sibling DRAFT → should be ABANDONED
      UUID sibId = UUID.randomUUID();
      AscriptionEntity draftSibling =
          stubEntity(sibId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT, defId);
      doReturn(List.of(entity, draftSibling)).when(structureSubtype).findAllByDefinitionId(defId);

      service.transition(id, "APPROVED");

      // transitionService.recordTransition should be called for the main transition +
      // the sibling
      // termination
      verify(transitionService, atLeast(2)).recordTransition(any(), any(), any());
    }

    @Test
    void approval_terminatesProposedSiblings() {
      UUID id = UUID.randomUUID();
      UUID defId = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.PROPOSED, defId);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);
      stubTransitionSave();

      // Sibling PROPOSED → should be REJECTED
      UUID sibId = UUID.randomUUID();
      AscriptionEntity proposedSibling =
          stubEntity(sibId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.PROPOSED, defId);
      doReturn(List.of(entity, proposedSibling))
          .when(structureSubtype)
          .findAllByDefinitionId(defId);

      service.transition(id, "APPROVED");

      verify(transitionService, atLeast(2)).recordTransition(any(), any(), any());
    }

    @Test
    void approval_ignoresActiveSiblings() {
      UUID id = UUID.randomUUID();
      UUID defId = UUID.randomUUID();
      AscriptionEntity entity =
          stubEntity(id, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.PROPOSED, defId);
      when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);
      stubTransitionSave();

      // Sibling ACTIVE → not terminated
      UUID sibId = UUID.randomUUID();
      AscriptionEntity activeSibling =
          stubEntity(sibId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE, defId);
      doReturn(List.of(entity, activeSibling)).when(structureSubtype).findAllByDefinitionId(defId);

      service.transition(id, "APPROVED");

      // Only 1 transition — the active sibling is not terminated
      verify(transitionService, times(1)).recordTransition(any(), any(), any());
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
      stubTransitionSave();

      // Previous ACTIVE ascription for same definition
      UUID prevId = UUID.randomUUID();
      AscriptionEntity previous =
          stubEntity(prevId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE, defId);
      doReturn(List.of(previous))
          .when(structureSubtype)
          .findAllByDefinitionIdAndStatus(eq(defId), anyList());

      service.transition(id, "ACTIVE");

      // At least 2 transitions: main + previous DEPRECATED
      verify(transitionService, atLeast(2)).recordTransition(any(), any(), any());
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
      stubTransitionSave();

      // Mechanism as governing cascade target of Structure
      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE);
      doReturn(List.of(mechanism))
          .when(mechanismSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, structureId);

      // ACTIVE→DEPRECATED triggers governing cascade
      service.transition(structureId, "DEPRECATED");

      // 2 transitions: main + cascaded mechanism
      verify(transitionService, atLeast(2)).recordTransition(any(), any(), any());
    }

    @Test
    void governingCascade_statusMismatch_noOp() {
      UUID structureId = UUID.randomUUID();
      UUID defId = UUID.randomUUID();
      AscriptionEntity structure =
          stubEntity(
              structureId, DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE, defId);
      when(entityManager.find(AscriptionEntity.class, structureId)).thenReturn(structure);
      stubTransitionSave();

      // Mechanism is DRAFT, not ACTIVE — does not match fromStatus
      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.DRAFT);
      doReturn(List.of(mechanism))
          .when(mechanismSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, structureId);

      service.transition(structureId, "DEPRECATED");

      // Only 1 transition (no cascade — status mismatch, no-op for GOVERNING)
      verify(transitionService, times(1)).recordTransition(any(), any(), any());
    }
  }

  // ========================================================================
  // Dependent cascade scope
  // ========================================================================

  @Nested
  class DependentCascadeScope {

    private AbstractAscriptionService effectorSubtype;
    private AscriptionLifecycleService serviceWithDependent;

    @BeforeEach
    void setUpDependentCascade() {
      // Set up Mechanism as source + Effector as CONSTITUTIVE dependent
      AbstractAscriptionService mechSvc = mock(AbstractAscriptionService.class);
      when(mechSvc.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
      when(mechSvc.getCascadeTargetRoles())
          .thenReturn(
              Map.of(
                  DefinitionSubjectType.STRUCTURE,
                  AscriptionStatusTransitionCascadeType.GOVERNING));

      effectorSubtype = mock(AbstractAscriptionService.class);
      when(effectorSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.EFFECTOR);
      when(effectorSubtype.getCascadeTargetRoles())
          .thenReturn(
              Map.of(
                  DefinitionSubjectType.MECHANISM,
                  AscriptionStatusTransitionCascadeType.DEPENDENT));

      AbstractAscriptionService structSvc = mock(AbstractAscriptionService.class);
      when(structSvc.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
      when(structSvc.getCascadeTargetRoles()).thenReturn(Map.of());

      serviceWithDependent =
          new AscriptionLifecycleService(
              List.of(structSvc, mechSvc, effectorSubtype), transitionService, entityManager);
    }

    @Test
    void activeToDeprecated_dependentCascadeFires() {
      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE);
      when(entityManager.find(AscriptionEntity.class, mechId)).thenReturn(mechanism);
      stubTransitionSave();

      UUID effId = UUID.randomUUID();
      AscriptionEntity effector =
          stubEntity(effId, DefinitionSubjectType.EFFECTOR, AscriptionStatusType.ACTIVE);
      doReturn(List.of(effector))
          .when(effectorSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechId);

      serviceWithDependent.transition(mechId, "DEPRECATED");

      // 2 transitions: main + cascaded effector
      verify(transitionService, atLeast(2)).recordTransition(any(), any(), any());
    }

    @Test
    void activeToSuspended_dependentCascadeFires() {
      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE);
      when(entityManager.find(AscriptionEntity.class, mechId)).thenReturn(mechanism);
      stubTransitionSave();

      UUID effId = UUID.randomUUID();
      AscriptionEntity effector =
          stubEntity(effId, DefinitionSubjectType.EFFECTOR, AscriptionStatusType.ACTIVE);
      doReturn(List.of(effector))
          .when(effectorSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechId);

      serviceWithDependent.transition(mechId, "SUSPENDED");

      verify(transitionService, atLeast(2)).recordTransition(any(), any(), any());
    }

    @Test
    void proposedToApproved_dependentCascadeDoesNotFire() {
      UUID mechId = UUID.randomUUID();
      UUID defId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.PROPOSED, defId);
      when(entityManager.find(AscriptionEntity.class, mechId)).thenReturn(mechanism);
      stubTransitionSave();

      // Even if effectors exist, dependent cascade should NOT fire on positive
      // transitions
      UUID effId = UUID.randomUUID();
      AscriptionEntity effector =
          stubEntity(effId, DefinitionSubjectType.EFFECTOR, AscriptionStatusType.PROPOSED);
      doReturn(List.of(effector))
          .when(effectorSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechId);

      // Just transition — no convergence siblings needed for this assertion
      serviceWithDependent.transition(mechId, "APPROVED");

      // Only 1 transition (main) — dependent cascade not applicable
      verify(transitionService, times(1)).recordTransition(any(), any(), any());
    }

    @Test
    void draftToAbandoned_dependentCascadeFires() {
      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.DRAFT);
      when(entityManager.find(AscriptionEntity.class, mechId)).thenReturn(mechanism);
      stubTransitionSave();

      UUID effId = UUID.randomUUID();
      AscriptionEntity effector =
          stubEntity(effId, DefinitionSubjectType.EFFECTOR, AscriptionStatusType.DRAFT);
      doReturn(List.of(effector))
          .when(effectorSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechId);

      serviceWithDependent.transition(mechId, "ABANDONED");

      verify(transitionService, atLeast(2)).recordTransition(any(), any(), any());
    }

    @Test
    void dependentCascade_statusMismatch_noOp() {
      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE);
      when(entityManager.find(AscriptionEntity.class, mechId)).thenReturn(mechanism);
      stubTransitionSave();

      // Effector is DRAFT, not ACTIVE → status mismatch → no-op for DEPENDENT
      UUID effId = UUID.randomUUID();
      AscriptionEntity effector =
          stubEntity(effId, DefinitionSubjectType.EFFECTOR, AscriptionStatusType.DRAFT);
      doReturn(List.of(effector))
          .when(effectorSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechId);

      serviceWithDependent.transition(mechId, "DEPRECATED");

      // Only 1 transition (main) — effector status mismatch → no-op
      verify(transitionService, times(1)).recordTransition(any(), any(), any());
    }
  }

  // ========================================================================
  // Constitutive cascade
  // ========================================================================

  @Nested
  class ConstitutiveCascade {

    private AbstractAscriptionService effectorSubtype;
    private AscriptionLifecycleService serviceWithConstitutive;

    @BeforeEach
    void setUpConstitutiveCascade() {
      AbstractAscriptionService mechSvc = mock(AbstractAscriptionService.class);
      when(mechSvc.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
      when(mechSvc.getCascadeTargetRoles()).thenReturn(Map.of());

      // Effector → CONSTITUTIVE from Mechanism
      effectorSubtype = mock(AbstractAscriptionService.class);
      when(effectorSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.EFFECTOR);
      when(effectorSubtype.getCascadeTargetRoles())
          .thenReturn(
              Map.of(
                  DefinitionSubjectType.MECHANISM,
                  AscriptionStatusTransitionCascadeType.CONSTITUTIVE));

      AbstractAscriptionService structSvc = mock(AbstractAscriptionService.class);
      when(structSvc.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
      when(structSvc.getCascadeTargetRoles()).thenReturn(Map.of());

      serviceWithConstitutive =
          new AscriptionLifecycleService(
              List.of(structSvc, mechSvc, effectorSubtype), transitionService, entityManager);
    }

    @Test
    void constitutiveCascade_statusMismatch_throws() {
      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE);
      when(entityManager.find(AscriptionEntity.class, mechId)).thenReturn(mechanism);
      stubTransitionSave();

      // Effector is DRAFT, not ACTIVE → constitutive cascade MUST fail
      UUID effId = UUID.randomUUID();
      AscriptionEntity effector =
          stubEntity(effId, DefinitionSubjectType.EFFECTOR, AscriptionStatusType.DRAFT);
      doReturn(List.of(effector))
          .when(effectorSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechId);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> serviceWithConstitutive.transition(mechId, "DEPRECATED"));
      assertTrue(ex.getMessage().contains("Constitutive cascade failed"));
      assertEquals(RuleType.ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_CONSTITUENTS, ex.getRuleType());
    }

    @Test
    void constitutiveCascade_statusMatch_propagates() {
      UUID mechId = UUID.randomUUID();
      AscriptionEntity mechanism =
          stubEntity(mechId, DefinitionSubjectType.MECHANISM, AscriptionStatusType.ACTIVE);
      when(entityManager.find(AscriptionEntity.class, mechId)).thenReturn(mechanism);
      stubTransitionSave();

      UUID effId = UUID.randomUUID();
      AscriptionEntity effector =
          stubEntity(effId, DefinitionSubjectType.EFFECTOR, AscriptionStatusType.ACTIVE);
      doReturn(List.of(effector))
          .when(effectorSubtype)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechId);

      serviceWithConstitutive.transition(mechId, "DEPRECATED");

      verify(transitionService, atLeast(2)).recordTransition(any(), any(), any());
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

  /** Stubs transitionService.recordTransition() to return a mock transition entity. */
  private void stubTransitionSave() {
    AscriptionStatusTransitionEntity savedTransition = mock(AscriptionStatusTransitionEntity.class);
    when(transitionService.recordTransition(any(), any(), any())).thenReturn(savedTransition);
  }

  // ========================================================================
  // GetTransitions / GetTransition
  // ========================================================================

  @Nested
  class TransitionLookup {

    @Test
    void getTransitions_delegatesToService() {
      UUID ascriptionId = UUID.randomUUID();
      AscriptionStatusTransitionEntity t1 = mock(AscriptionStatusTransitionEntity.class);
      when(transitionService.findByAscriptionId(ascriptionId)).thenReturn(List.of(t1));

      var result = service.getTransitions(ascriptionId);
      assertEquals(1, result.size());
      assertEquals(t1, result.get(0));
    }

    @Test
    void getTransition_found() {
      UUID transitionId = UUID.randomUUID();
      UUID ascriptionId = UUID.randomUUID();
      AscriptionStatusTransitionEntity t = mock(AscriptionStatusTransitionEntity.class);
      when(transitionService.findByIdAndAscriptionId(transitionId, ascriptionId))
          .thenReturn(Optional.of(t));

      var result = service.getTransition(transitionId, ascriptionId);
      assertTrue(result.isPresent());
      assertEquals(t, result.get());
    }

    @Test
    void getTransition_notFound() {
      UUID transitionId = UUID.randomUUID();
      UUID ascriptionId = UUID.randomUUID();
      when(transitionService.findByIdAndAscriptionId(transitionId, ascriptionId))
          .thenReturn(Optional.empty());

      var result = service.getTransition(transitionId, ascriptionId);
      assertTrue(result.isEmpty());
    }
  }
}
