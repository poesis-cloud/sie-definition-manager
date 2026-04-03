package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import cloud.poesis.sie.defman.type.RefereeReference;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

/**
 * Unit tests for {@link AscriptionStatusTransitionService}.
 *
 * <p>Tests the transition orchestrator: state machine validation, referee preconditions, cascades,
 * governance convergence, activation handoff, and query delegation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AscriptionStatusTransitionServiceTest {

  @Mock private AscriptionStatusTransitionRepository transitionRepo;
  @Mock private EntityManager entityManager;

  @Mock private AbstractAscriptionService<?> structureService;
  @Mock private AbstractAscriptionService<?> mechanismService;
  @Mock private AbstractAscriptionService<?> effectorService;

  private AscriptionStatusTransitionService service;

  @BeforeEach
  void setUp() {
    lenient().when(structureService.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    lenient().when(structureService.getCascadeTargetRoles()).thenReturn(Map.of());
    lenient().when(mechanismService.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
    lenient().when(mechanismService.getCascadeTargetRoles()).thenReturn(Map.of());
    lenient().when(effectorService.getSubjectType()).thenReturn(DefinitionSubjectType.EFFECTOR);
    lenient()
        .when(effectorService.getCascadeTargetRoles())
        .thenReturn(
            Map.of(
                DefinitionSubjectType.MECHANISM,
                AscriptionStatusTransitionCascadeType.CONSTITUTIVE));

    service =
        new AscriptionStatusTransitionService(
            transitionRepo,
            entityManager,
            List.of(structureService, mechanismService, effectorService));
    service.afterSingletonsInstantiated();
  }

  // ========================================================================
  // Transition queries
  // ========================================================================

  @Nested
  class TransitionQueries {

    @Test
    void getTransitions_delegatesToRepo() {
      UUID ascId = UUID.randomUUID();
      var expected = List.of(mock(AscriptionStatusTransitionEntity.class));
      when(transitionRepo.findAllByAscriptionIdOrderByTimestampAsc(ascId)).thenReturn(expected);

      var result = service.getTransitions(ascId);

      assertEquals(expected, result);
      verify(transitionRepo).findAllByAscriptionIdOrderByTimestampAsc(ascId);
    }

    @Test
    void getTransition_delegatesToRepo() {
      UUID transId = UUID.randomUUID();
      UUID ascId = UUID.randomUUID();
      var expected = mock(AscriptionStatusTransitionEntity.class);
      when(transitionRepo.findByIdAndAscriptionId(transId, ascId))
          .thenReturn(Optional.of(expected));

      var result = service.getTransition(transId, ascId);

      assertTrue(result.isPresent());
      assertEquals(expected, result.get());
    }
  }

  // ========================================================================
  // RecordTransition (persistence)
  // ========================================================================

  @Nested
  class RecordTransition {

    @Test
    void recordTransition_persistsFlushesAndReloads() {
      AscriptionEntity entity = mock(AscriptionEntity.class);
      UUID transId = UUID.randomUUID();
      AscriptionStatusTransitionEntity savedTransition =
          mock(AscriptionStatusTransitionEntity.class);
      when(savedTransition.getId()).thenReturn(transId);
      when(transitionRepo.save(any())).thenReturn(savedTransition);

      AscriptionStatusTransitionEntity reloaded = mock(AscriptionStatusTransitionEntity.class);
      when(transitionRepo.findById(transId)).thenReturn(Optional.of(reloaded));

      var result =
          service.recordTransition(
              entity, AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED);

      assertEquals(reloaded, result);
      verify(entityManager).flush();
      verify(entityManager).detach(savedTransition);
      verify(transitionRepo).findById(transId);
    }
  }

  // ========================================================================
  // Validate referee preconditions (public API)
  // ========================================================================

  @Nested
  class RefereePreconditions {

    @Test
    void emptyRefs_passes() {
      assertDoesNotThrow(
          () ->
              service.validateRefereePreconditions(
                  List.of(), AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED));
    }

    @Test
    void draftCreation_activeRefereeAllowed() {
      AscriptionEntity ref = stubEntityWithStatus(AscriptionStatusType.ACTIVE);
      assertDoesNotThrow(
          () ->
              service.validateRefereePreconditions(
                  List.of(new RefereeReference(ref, "structure")),
                  null,
                  AscriptionStatusType.DRAFT));
    }

    @Test
    void draftCreation_retiredRefereeRejected() {
      AscriptionEntity ref = stubEntityWithStatus(AscriptionStatusType.RETIRED);
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateRefereePreconditions(
                      List.of(new RefereeReference(ref, "structure")),
                      null,
                      AscriptionStatusType.DRAFT));
      assertEquals(
          AscriptionStatusTransitionRuleType
              .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("structure"));
      assertTrue(ex.getMessage().contains("RETIRED"));
    }

    @Test
    void approvedToActive_requiresActiveReferee() {
      AscriptionEntity ref = stubEntityWithStatus(AscriptionStatusType.PROPOSED);
      assertThrows(
          RuleViolationException.class,
          () ->
              service.validateRefereePreconditions(
                  List.of(new RefereeReference(ref, "mechanism")),
                  AscriptionStatusType.APPROVED,
                  AscriptionStatusType.ACTIVE));
    }

    @Test
    void approvedToActive_activeRefereeAllowed() {
      AscriptionEntity ref = stubEntityWithStatus(AscriptionStatusType.ACTIVE);
      assertDoesNotThrow(
          () ->
              service.validateRefereePreconditions(
                  List.of(new RefereeReference(ref, "mechanism")),
                  AscriptionStatusType.APPROVED,
                  AscriptionStatusType.ACTIVE));
    }

    @Test
    void draftToAbandoned_anyStatusAllowed() {
      AscriptionEntity ref = stubEntityWithStatus(AscriptionStatusType.RETIRED);
      assertDoesNotThrow(
          () ->
              service.validateRefereePreconditions(
                  List.of(new RefereeReference(ref, "ref")),
                  AscriptionStatusType.DRAFT,
                  AscriptionStatusType.ABANDONED));
    }

    @Test
    void proposedToRejected_draftRefereeBlocked() {
      AscriptionEntity ref = stubEntityWithStatus(AscriptionStatusType.DRAFT);
      assertThrows(
          RuleViolationException.class,
          () ->
              service.validateRefereePreconditions(
                  List.of(new RefereeReference(ref, "structure")),
                  AscriptionStatusType.PROPOSED,
                  AscriptionStatusType.REJECTED));
    }

    @Test
    void unknownTransitionKey_noAllowedDefined_passes() {
      // RETIRED → nothing is valid (terminal), so no REFEREE_ALLOWED entry exists
      AscriptionEntity ref = stubEntityWithStatus(AscriptionStatusType.ACTIVE);
      assertDoesNotThrow(
          () ->
              service.validateRefereePreconditions(
                  List.of(new RefereeReference(ref, "ref")),
                  AscriptionStatusType.RETIRED,
                  AscriptionStatusType.ACTIVE));
    }

    @ParameterizedTest
    @CsvSource({
      "ACTIVE, SUSPENDED, ACTIVE",
      "ACTIVE, SUSPENDED, DEPRECATED",
      "SUSPENDED, ACTIVE, ACTIVE",
      "SUSPENDED, DEPRECATED, ACTIVE",
      "DEPRECATED, SUSPENDED, ACTIVE",
      "DEPRECATED, RETIRED, ACTIVE",
      "DEPRECATED, RETIRED, RETIRED",
    })
    void inEffectTransitions_validRefereePasses(String from, String to, String refStatus) {
      AscriptionEntity ref = stubEntityWithStatus(AscriptionStatusType.valueOf(refStatus));
      assertDoesNotThrow(
          () ->
              service.validateRefereePreconditions(
                  List.of(new RefereeReference(ref, "ref")),
                  AscriptionStatusType.valueOf(from),
                  AscriptionStatusType.valueOf(to)));
    }
  }

  // ========================================================================
  // Transition — state machine validation
  // ========================================================================

  @Nested
  class TransitionStateMachine {

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
      "DEPRECATED, RETIRED",
    })
    void validTransition_succeeds(String from, String to) {
      UUID ascId = UUID.randomUUID();
      AscriptionEntity entity = stubEntity(ascId, AscriptionStatusType.valueOf(from));
      when(entityManager.find(AscriptionEntity.class, ascId)).thenReturn(entity);
      stubRecordTransition();
      when(structureService.getRefereeReferences(any())).thenReturn(List.of());

      assertDoesNotThrow(() -> service.transition(ascId, to));
    }

    @Test
    void terminalState_rejected() {
      UUID ascId = UUID.randomUUID();
      AscriptionEntity entity = stubEntity(ascId, AscriptionStatusType.RETIRED);
      when(entityManager.find(AscriptionEntity.class, ascId)).thenReturn(entity);

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.transition(ascId, "ACTIVE"));
      assertEquals(
          AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_TERMINAL_IMMUTABILITY,
          ex.getRuleType());
    }

    @Test
    void invalidPath_rejected() {
      UUID ascId = UUID.randomUUID();
      AscriptionEntity entity = stubEntity(ascId, AscriptionStatusType.DRAFT);
      when(entityManager.find(AscriptionEntity.class, ascId)).thenReturn(entity);

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.transition(ascId, "ACTIVE"));
      assertEquals(
          AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_PATH, ex.getRuleType());
    }

    @Test
    void notFound_throws() {
      UUID ascId = UUID.randomUUID();
      when(entityManager.find(AscriptionEntity.class, ascId)).thenReturn(null);

      assertThrows(ResourceNotFoundException.class, () -> service.transition(ascId, "PROPOSED"));
    }
  }

  // ========================================================================
  // Transition — activation
  // ========================================================================

  @Nested
  class Activation {

    @Test
    void approvedToActive_callsUniquenessAndOnActivation() {
      UUID ascId = UUID.randomUUID();
      AscriptionEntity entity = stubEntity(ascId, AscriptionStatusType.APPROVED);
      when(entityManager.find(AscriptionEntity.class, ascId)).thenReturn(entity);
      stubRecordTransition();
      when(structureService.getRefereeReferences(any())).thenReturn(List.of());
      when(structureService.findAllByDefinitionIdAndStatus(any(), any())).thenReturn(List.of());

      service.transition(ascId, "ACTIVE");

      verify(structureService).validateActivationUniqueness(entity);
      verify(structureService).onActivation(entity);
    }

    @Test
    void activationHandoff_deprecatesPreviousActive() {
      UUID ascId = UUID.randomUUID();
      UUID prevId = UUID.randomUUID();
      AscriptionEntity entity = stubEntity(ascId, AscriptionStatusType.APPROVED);
      AscriptionEntity prev = stubEntity(prevId, AscriptionStatusType.ACTIVE);
      UUID entityDefId = entity.getDefinition().getId();
      when(entityManager.find(AscriptionEntity.class, ascId)).thenReturn(entity);
      stubRecordTransition();
      when(structureService.getRefereeReferences(any())).thenReturn(List.of());
      doReturn(List.of(prev))
          .when(structureService)
          .findAllByDefinitionIdAndStatus(entityDefId, List.of(AscriptionStatusType.ACTIVE));

      service.transition(ascId, "ACTIVE");

      // Should have recorded 2 transitions: handoff (prev ACTIVE→DEPRECATED) + main
      verify(transitionRepo, times(2)).save(any());
    }
  }

  // ========================================================================
  // Transition — deactivation hook
  // ========================================================================

  @Nested
  class DeactivationHook {

    @Test
    void activeToSuspended_callsOnDeactivation() {
      UUID ascId = UUID.randomUUID();
      AscriptionEntity entity = stubEntity(ascId, AscriptionStatusType.ACTIVE);
      when(entityManager.find(AscriptionEntity.class, ascId)).thenReturn(entity);
      stubRecordTransition();
      when(structureService.getRefereeReferences(any())).thenReturn(List.of());

      service.transition(ascId, "SUSPENDED");

      verify(structureService).onDeactivation(entity);
    }

    @Test
    void deprecatedToRetired_callsOnDeactivation() {
      UUID ascId = UUID.randomUUID();
      AscriptionEntity entity = stubEntity(ascId, AscriptionStatusType.DEPRECATED);
      when(entityManager.find(AscriptionEntity.class, ascId)).thenReturn(entity);
      stubRecordTransition();
      when(structureService.getRefereeReferences(any())).thenReturn(List.of());

      service.transition(ascId, "RETIRED");

      verify(structureService).onDeactivation(entity);
    }

    @Test
    void activeToDeprecated_noDeactivation_remainsInEffect() {
      UUID ascId = UUID.randomUUID();
      AscriptionEntity entity = stubEntity(ascId, AscriptionStatusType.ACTIVE);
      when(entityManager.find(AscriptionEntity.class, ascId)).thenReturn(entity);
      stubRecordTransition();
      when(structureService.getRefereeReferences(any())).thenReturn(List.of());

      service.transition(ascId, "DEPRECATED");

      verify(structureService, never()).onDeactivation(entity);
    }
  }

  // ========================================================================
  // Transition — governance convergence (APPROVED)
  // ========================================================================

  @Nested
  class GovernanceConvergence {

    @Test
    void approval_abandonsDraftSiblings() {
      UUID ascId = UUID.randomUUID();
      UUID siblingId = UUID.randomUUID();
      UUID defId = UUID.randomUUID();

      AscriptionEntity entity = stubEntity(ascId, AscriptionStatusType.PROPOSED);
      AscriptionEntity sibling = stubEntity(siblingId, AscriptionStatusType.DRAFT);
      // Same definition
      DefinitionEntity sharedDef = entity.getDefinition();
      UUID entityDefId = sharedDef.getId();
      doReturn(sharedDef).when(sibling).getDefinition();

      when(entityManager.find(AscriptionEntity.class, ascId)).thenReturn(entity);
      stubRecordTransition();
      when(structureService.getRefereeReferences(any())).thenReturn(List.of());
      doReturn(List.of(entity, sibling)).when(structureService).findAllByDefinitionId(entityDefId);

      service.transition(ascId, "APPROVED");

      // Main transition + sibling termination = 2 saves
      verify(transitionRepo, times(2)).save(any());
    }

    @Test
    void approval_rejectsProposedSiblings() {
      UUID ascId = UUID.randomUUID();
      UUID siblingId = UUID.randomUUID();

      AscriptionEntity entity = stubEntity(ascId, AscriptionStatusType.PROPOSED);
      AscriptionEntity sibling = stubEntity(siblingId, AscriptionStatusType.PROPOSED);
      DefinitionEntity sharedDef = entity.getDefinition();
      UUID entityDefId = sharedDef.getId();
      doReturn(sharedDef).when(sibling).getDefinition();

      when(entityManager.find(AscriptionEntity.class, ascId)).thenReturn(entity);
      stubRecordTransition();
      when(structureService.getRefereeReferences(any())).thenReturn(List.of());
      doReturn(List.of(entity, sibling)).when(structureService).findAllByDefinitionId(entityDefId);

      service.transition(ascId, "APPROVED");

      verify(transitionRepo, times(2)).save(any());
    }

    @Test
    void approval_skipsActiveSiblings() {
      UUID ascId = UUID.randomUUID();
      UUID siblingId = UUID.randomUUID();

      AscriptionEntity entity = stubEntity(ascId, AscriptionStatusType.PROPOSED);
      AscriptionEntity sibling = stubEntity(siblingId, AscriptionStatusType.ACTIVE);
      DefinitionEntity sharedDef = entity.getDefinition();
      UUID entityDefId = sharedDef.getId();
      doReturn(sharedDef).when(sibling).getDefinition();

      when(entityManager.find(AscriptionEntity.class, ascId)).thenReturn(entity);
      stubRecordTransition();
      when(structureService.getRefereeReferences(any())).thenReturn(List.of());
      doReturn(List.of(entity, sibling)).when(structureService).findAllByDefinitionId(entityDefId);

      service.transition(ascId, "APPROVED");

      // Only main transition, not the active sibling
      verify(transitionRepo, times(1)).save(any());
    }
  }

  // ========================================================================
  // Cascade execution
  // ========================================================================

  @Nested
  class Cascades {

    @Test
    void constitutiveCascade_propagatesToMatchingTargets() {
      // Mechanism ACTIVE → SUSPENDED should cascade to Effectors (constitutive)
      UUID mechAscId = UUID.randomUUID();
      UUID effAscId = UUID.randomUUID();
      AscriptionEntity mechEntity =
          stubEntity(mechAscId, AscriptionStatusType.ACTIVE, DefinitionSubjectType.MECHANISM);
      AscriptionEntity effEntity =
          stubEntity(effAscId, AscriptionStatusType.ACTIVE, DefinitionSubjectType.EFFECTOR);

      when(entityManager.find(AscriptionEntity.class, mechAscId)).thenReturn(mechEntity);
      stubRecordTransition();
      when(mechanismService.getRefereeReferences(any())).thenReturn(List.of());
      doReturn(List.of(effEntity))
          .when(effectorService)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechAscId);
      when(effectorService.getRefereeReferences(any())).thenReturn(List.of());
      // No further cascades from effector
      doReturn(List.of()).when(effectorService).findCascadeTargetsFrom(any(), eq(effAscId));

      service.transition(mechAscId, "SUSPENDED");

      // Main + cascade = 2 transitions
      verify(transitionRepo, times(2)).save(any());
    }

    @Test
    void constitutiveCascade_failsOnStatusMismatch() {
      UUID mechAscId = UUID.randomUUID();
      UUID effAscId = UUID.randomUUID();
      AscriptionEntity mechEntity =
          stubEntity(mechAscId, AscriptionStatusType.ACTIVE, DefinitionSubjectType.MECHANISM);
      // Effector is in DRAFT, not ACTIVE — constitutive should fail
      AscriptionEntity effEntity =
          stubEntity(effAscId, AscriptionStatusType.DRAFT, DefinitionSubjectType.EFFECTOR);

      when(entityManager.find(AscriptionEntity.class, mechAscId)).thenReturn(mechEntity);
      stubRecordTransition();
      when(mechanismService.getRefereeReferences(any())).thenReturn(List.of());
      doReturn(List.of(effEntity))
          .when(effectorService)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechAscId);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.transition(mechAscId, "SUSPENDED"));
      assertEquals(
          AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_CONSTITUENTS,
          ex.getRuleType());
    }

    @Test
    void constitutiveCascade_failsOnRefereePrecondition() {
      UUID mechAscId = UUID.randomUUID();
      UUID effAscId = UUID.randomUUID();
      AscriptionEntity mechEntity =
          stubEntity(mechAscId, AscriptionStatusType.ACTIVE, DefinitionSubjectType.MECHANISM);
      AscriptionEntity effEntity =
          stubEntity(effAscId, AscriptionStatusType.ACTIVE, DefinitionSubjectType.EFFECTOR);

      when(entityManager.find(AscriptionEntity.class, mechAscId)).thenReturn(mechEntity);
      stubRecordTransition();
      when(mechanismService.getRefereeReferences(any())).thenReturn(List.of());
      doReturn(List.of(effEntity))
          .when(effectorService)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechAscId);
      // Effector has a referee that blocks the cascade
      AscriptionEntity badRef = stubEntityWithStatus(AscriptionStatusType.RETIRED);
      when(effectorService.getRefereeReferences(effEntity))
          .thenReturn(List.of(new RefereeReference(badRef, "data-archetype")));

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.transition(mechAscId, "SUSPENDED"));
      assertEquals(
          AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_CONSTITUENTS,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Constitutive cascade blocked"));
    }

    @Test
    void dependentCascade_onlyAppliesInScope() {
      // Set up effectorService as DEPENDENT cascade from mechanism
      lenient()
          .when(effectorService.getCascadeTargetRoles())
          .thenReturn(
              Map.of(
                  DefinitionSubjectType.MECHANISM,
                  AscriptionStatusTransitionCascadeType.DEPENDENT));
      // Rebuild cascade graph
      service.afterSingletonsInstantiated();

      UUID mechAscId = UUID.randomUUID();
      AscriptionEntity mechEntity =
          stubEntity(mechAscId, AscriptionStatusType.DRAFT, DefinitionSubjectType.MECHANISM);
      when(entityManager.find(AscriptionEntity.class, mechAscId)).thenReturn(mechEntity);
      stubRecordTransition();
      when(mechanismService.getRefereeReferences(any())).thenReturn(List.of());

      // DRAFT → PROPOSED is NOT in DEPENDENT_CASCADE_SCOPE, so no cascade
      service.transition(mechAscId, "PROPOSED");
      verify(effectorService, never()).findCascadeTargetsFrom(any(), any());
    }

    @Test
    void dependentCascade_skipsStatusMismatch() {
      // DEPENDENT cascade: status mismatch → skip (logged), not throw
      lenient()
          .when(effectorService.getCascadeTargetRoles())
          .thenReturn(
              Map.of(
                  DefinitionSubjectType.MECHANISM,
                  AscriptionStatusTransitionCascadeType.DEPENDENT));
      service.afterSingletonsInstantiated();

      UUID mechAscId = UUID.randomUUID();
      UUID effAscId = UUID.randomUUID();
      AscriptionEntity mechEntity =
          stubEntity(mechAscId, AscriptionStatusType.ACTIVE, DefinitionSubjectType.MECHANISM);
      // Effector is DRAFT, not ACTIVE — dependent skip
      AscriptionEntity effEntity =
          stubEntity(effAscId, AscriptionStatusType.DRAFT, DefinitionSubjectType.EFFECTOR);

      when(entityManager.find(AscriptionEntity.class, mechAscId)).thenReturn(mechEntity);
      stubRecordTransition();
      when(mechanismService.getRefereeReferences(any())).thenReturn(List.of());
      doReturn(List.of(effEntity))
          .when(effectorService)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechAscId);

      // Should NOT throw for dependent cascade status mismatch
      assertDoesNotThrow(() -> service.transition(mechAscId, "SUSPENDED"));
    }

    @Test
    void dependentCascade_skipsRefereePreconditionFailure() {
      lenient()
          .when(effectorService.getCascadeTargetRoles())
          .thenReturn(
              Map.of(
                  DefinitionSubjectType.MECHANISM,
                  AscriptionStatusTransitionCascadeType.DEPENDENT));
      service.afterSingletonsInstantiated();

      UUID mechAscId = UUID.randomUUID();
      UUID effAscId = UUID.randomUUID();
      AscriptionEntity mechEntity =
          stubEntity(mechAscId, AscriptionStatusType.ACTIVE, DefinitionSubjectType.MECHANISM);
      AscriptionEntity effEntity =
          stubEntity(effAscId, AscriptionStatusType.ACTIVE, DefinitionSubjectType.EFFECTOR);

      when(entityManager.find(AscriptionEntity.class, mechAscId)).thenReturn(mechEntity);
      stubRecordTransition();
      when(mechanismService.getRefereeReferences(any())).thenReturn(List.of());
      doReturn(List.of(effEntity))
          .when(effectorService)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechAscId);
      // Effector has a referee that blocks → skip for dependent
      AscriptionEntity badRef = stubEntityWithStatus(AscriptionStatusType.RETIRED);
      when(effectorService.getRefereeReferences(effEntity))
          .thenReturn(List.of(new RefereeReference(badRef, "data-archetype")));

      // Dependent cascade skips on referee failure (no throw)
      assertDoesNotThrow(() -> service.transition(mechAscId, "SUSPENDED"));
    }

    @Test
    void governingCascade_skipsStatusMismatch() {
      // Set up structureService as having governing cascade targets from mechanism
      lenient()
          .when(structureService.getCascadeTargetRoles())
          .thenReturn(
              Map.of(
                  DefinitionSubjectType.MECHANISM,
                  AscriptionStatusTransitionCascadeType.GOVERNING));
      service.afterSingletonsInstantiated();

      UUID mechAscId = UUID.randomUUID();
      UUID structAscId = UUID.randomUUID();
      AscriptionEntity mechEntity =
          stubEntity(mechAscId, AscriptionStatusType.ACTIVE, DefinitionSubjectType.MECHANISM);
      // Structure in DRAFT, not ACTIVE — governing skip
      AscriptionEntity structEntity =
          stubEntity(structAscId, AscriptionStatusType.DRAFT, DefinitionSubjectType.STRUCTURE);

      when(entityManager.find(AscriptionEntity.class, mechAscId)).thenReturn(mechEntity);
      stubRecordTransition();
      when(mechanismService.getRefereeReferences(any())).thenReturn(List.of());
      doReturn(List.of(structEntity))
          .when(structureService)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechAscId);

      // Should NOT throw for governing cascade status mismatch
      assertDoesNotThrow(() -> service.transition(mechAscId, "SUSPENDED"));
    }

    @Test
    void governingCascade_skipsRefereePreconditionFailure() {
      lenient()
          .when(structureService.getCascadeTargetRoles())
          .thenReturn(
              Map.of(
                  DefinitionSubjectType.MECHANISM,
                  AscriptionStatusTransitionCascadeType.GOVERNING));
      service.afterSingletonsInstantiated();

      UUID mechAscId = UUID.randomUUID();
      UUID structAscId = UUID.randomUUID();
      AscriptionEntity mechEntity =
          stubEntity(mechAscId, AscriptionStatusType.ACTIVE, DefinitionSubjectType.MECHANISM);
      AscriptionEntity structEntity =
          stubEntity(structAscId, AscriptionStatusType.ACTIVE, DefinitionSubjectType.STRUCTURE);

      when(entityManager.find(AscriptionEntity.class, mechAscId)).thenReturn(mechEntity);
      stubRecordTransition();
      when(mechanismService.getRefereeReferences(any())).thenReturn(List.of());
      doReturn(List.of(structEntity))
          .when(structureService)
          .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, mechAscId);
      AscriptionEntity badRef = stubEntityWithStatus(AscriptionStatusType.RETIRED);
      when(structureService.getRefereeReferences(structEntity))
          .thenReturn(List.of(new RefereeReference(badRef, "owner")));

      // Governing cascade skips on referee failure (no throw)
      assertDoesNotThrow(() -> service.transition(mechAscId, "SUSPENDED"));
    }

    @Test
    void noCascadeTargets_noError() {
      UUID ascId = UUID.randomUUID();
      AscriptionEntity entity = stubEntity(ascId, AscriptionStatusType.DRAFT);
      when(entityManager.find(AscriptionEntity.class, ascId)).thenReturn(entity);
      stubRecordTransition();
      when(structureService.getRefereeReferences(any())).thenReturn(List.of());

      assertDoesNotThrow(() -> service.transition(ascId, "PROPOSED"));
    }
  }

  // ========================================================================
  // Transition — referee preconditions at transition time
  // ========================================================================

  @Nested
  class TransitionRefereePreconditions {

    @Test
    void transitionRefereePreconditions_delegatesToSubtype() {
      UUID ascId = UUID.randomUUID();
      AscriptionEntity entity = stubEntity(ascId, AscriptionStatusType.DRAFT);
      AscriptionEntity referee = stubEntityWithStatus(AscriptionStatusType.ACTIVE);
      when(entityManager.find(AscriptionEntity.class, ascId)).thenReturn(entity);
      stubRecordTransition();
      when(structureService.getRefereeReferences(entity))
          .thenReturn(List.of(new RefereeReference(referee, "ref")));

      assertDoesNotThrow(() -> service.transition(ascId, "PROPOSED"));
    }

    @Test
    void transitionRefereePreconditions_blocksOnInvalidStatus() {
      UUID ascId = UUID.randomUUID();
      AscriptionEntity entity = stubEntity(ascId, AscriptionStatusType.APPROVED);
      // Referee is DRAFT — blocks APPROVED→ACTIVE (requires ACTIVE)
      AscriptionEntity referee = stubEntityWithStatus(AscriptionStatusType.DRAFT);
      when(entityManager.find(AscriptionEntity.class, ascId)).thenReturn(entity);
      when(structureService.getRefereeReferences(entity))
          .thenReturn(List.of(new RefereeReference(referee, "ref")));

      assertThrows(RuleViolationException.class, () -> service.transition(ascId, "ACTIVE"));
    }
  }

  // ========================================================================
  // AfterSingletonsInstantiated (startup wiring)
  // ========================================================================

  @Nested
  class StartupWiring {

    @Test
    void afterSingletonsInstantiated_buildsSubtypeAndCascadeMap() {
      // The setUp already calls afterSingletonsInstantiated; verify it works
      // by exercising a transition on a known subtype
      UUID ascId = UUID.randomUUID();
      AscriptionEntity entity = stubEntity(ascId, AscriptionStatusType.DRAFT);
      when(entityManager.find(AscriptionEntity.class, ascId)).thenReturn(entity);
      stubRecordTransition();
      when(structureService.getRefereeReferences(any())).thenReturn(List.of());

      assertNotNull(service.transition(ascId, "PROPOSED"));
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private AscriptionEntity stubEntity(UUID id, AscriptionStatusType status) {
    return stubEntity(id, status, DefinitionSubjectType.STRUCTURE);
  }

  private AscriptionEntity stubEntity(
      UUID id, AscriptionStatusType status, DefinitionSubjectType type) {
    AscriptionEntity entity = mock(AscriptionEntity.class);
    when(entity.getId()).thenReturn(id);
    when(entity.getStatus()).thenReturn(status);
    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(UUID.randomUUID());
    when(def.getSubjectType()).thenReturn(type);
    when(entity.getDefinition()).thenReturn(def);
    return entity;
  }

  private AscriptionEntity stubEntityWithStatus(AscriptionStatusType status) {
    AscriptionEntity entity = mock(AscriptionEntity.class);
    when(entity.getId()).thenReturn(UUID.randomUUID());
    when(entity.getStatus()).thenReturn(status);
    return entity;
  }

  private void stubRecordTransition() {
    UUID transId = UUID.randomUUID();
    AscriptionStatusTransitionEntity saved = mock(AscriptionStatusTransitionEntity.class);
    when(saved.getId()).thenReturn(transId);
    when(transitionRepo.save(any())).thenReturn(saved);
    when(transitionRepo.findById(any())).thenReturn(Optional.of(saved));
  }
}
