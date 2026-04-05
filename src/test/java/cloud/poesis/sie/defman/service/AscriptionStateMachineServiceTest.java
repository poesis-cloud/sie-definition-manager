package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AscriptionStateMachineTest {

  private AscriptionStateMachineService stateMachine;

  @BeforeEach
  void setUp() {
    stateMachine = new AscriptionStateMachineService();
  }

  // ========================================================================
  // Transition validation
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
      assertDoesNotThrow(
          () ->
              stateMachine.validateTransition(
                  id, AscriptionStatusType.valueOf(from), AscriptionStatusType.valueOf(to)));
    }
  }

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
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  stateMachine.validateTransition(
                      id, AscriptionStatusType.valueOf(from), AscriptionStatusType.valueOf(to)));
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
  // Referee preconditions (public API)
  // ========================================================================

  @Nested
  class PublicRefereePreconditions {

    @Test
    void creationWithAllowedStatus_succeeds() {
      AscriptionEntity ref =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE);
      List<Map.Entry<AscriptionEntity, String>> refs = List.of(Map.entry(ref, "structure"));

      assertDoesNotThrow(
          () -> stateMachine.validateRefereePreconditions(refs, null, AscriptionStatusType.DRAFT));
    }

    @Test
    void creationWithDisallowedStatus_throws() {
      AscriptionEntity ref =
          stubEntity(
              UUID.randomUUID(), DefinitionSubjectType.STRUCTURE, AscriptionStatusType.RETIRED);
      List<Map.Entry<AscriptionEntity, String>> refs = List.of(Map.entry(ref, "structure"));

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  stateMachine.validateRefereePreconditions(
                      refs, null, AscriptionStatusType.DRAFT));
      assertTrue(ex.getMessage().contains("Referee"));
      assertEquals(
          AscriptionStatusTransitionRuleType
              .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
    }

    @Test
    void emptyRefs_succeeds() {
      assertDoesNotThrow(
          () ->
              stateMachine.validateRefereePreconditions(
                  List.of(), AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED));
    }
  }

  // ========================================================================
  // Dependent cascade applicability
  // ========================================================================

  @Nested
  class DependentCascadeApplicability {

    @ParameterizedTest
    @CsvSource({
      "ACTIVE, SUSPENDED, true",
      "ACTIVE, DEPRECATED, true",
      "SUSPENDED, DEPRECATED, true",
      "DEPRECATED, SUSPENDED, true",
      "DEPRECATED, RETIRED, true",
      "DRAFT, ABANDONED, true",
      "PROPOSED, REJECTED, true",
      "DRAFT, PROPOSED, false",
      "PROPOSED, APPROVED, false",
      "APPROVED, ACTIVE, false"
    })
    void isDependentCascadeApplicable(String from, String to, boolean expected) {
      assertEquals(
          expected,
          stateMachine.isDependentCascadeApplicable(
              AscriptionStatusType.valueOf(from), AscriptionStatusType.valueOf(to)));
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private AscriptionEntity stubEntity(
      UUID id, DefinitionSubjectType subjectType, AscriptionStatusType status) {
    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(UUID.randomUUID());
    when(def.getSubjectType()).thenReturn(subjectType);

    AscriptionEntity entity = mock(AscriptionEntity.class);
    when(entity.getId()).thenReturn(id);
    when(entity.getDefinition()).thenReturn(def);
    when(entity.getStatus()).thenReturn(status);

    return entity;
  }
}
