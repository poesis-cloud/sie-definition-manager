package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionLifecycleSpec;
import cloud.poesis.sie.defman.type.AscriptionLifecycleSpec.Edge;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exhaustive behavioural matrix for {@link AscriptionStateMachineService}.
 *
 * <p>Covers every one of the 9 x 9 status pairs for path validation, and every (edge, referee
 * status) combination for referee preconditions, against the hand-written {@link
 * AscriptionLifecycleSpec} table.
 */
class AscriptionStateMachineServiceMatrixTest {

  private AscriptionStateMachineService stateMachine;

  @BeforeEach
  void setUp() {
    stateMachine = new AscriptionStateMachineService();
  }

  static Stream<Arguments> statusPairs() {
    return AscriptionLifecycleSpec.allStatusPairs().stream()
        .map(pair -> Arguments.of(pair[0], pair[1]));
  }

  static Stream<Arguments> illegalStatusPairs() {
    return AscriptionLifecycleSpec.allStatusPairs().stream()
        .filter(pair -> !AscriptionLifecycleSpec.isLegal(pair[0], pair[1]))
        .map(pair -> Arguments.of(pair[0], pair[1]));
  }

  static Stream<Arguments> edges() {
    return AscriptionLifecycleSpec.EDGES.stream().map(Arguments::of);
  }

  static Stream<Arguments> edgeRefereeCombinations() {
    return AscriptionLifecycleSpec.EDGES.stream()
        .flatMap(
            edge ->
                Stream.of(AscriptionStatusType.values())
                    .map(refereeStatus -> Arguments.of(edge, refereeStatus)));
  }

  // ========================================================================
  // Path validation — all 81 pairs
  // ========================================================================

  @Nested
  class PathValidation {

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource(
        "cloud.poesis.sie.defman.service.AscriptionStateMachineServiceMatrixTest#statusPairs")
    void everyStatusPairIsResolvedAsSpecified(AscriptionStatusType from, AscriptionStatusType to) {
      UUID id = UUID.randomUUID();

      if (AscriptionLifecycleSpec.isLegal(from, to)) {
        assertDoesNotThrow(() -> stateMachine.validateTransition(id, from, to));
        return;
      }

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> stateMachine.validateTransition(id, from, to));
      AscriptionStatusTransitionRuleType expected =
          AscriptionLifecycleSpec.TERMINAL_STATUSES.contains(from)
              ? AscriptionStatusTransitionRuleType
                  .ASCRIPTION_STATUS_TRANSITION_TERMINAL_IMMUTABILITY
              : AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_PATH;
      assertEquals(expected, ex.getRuleType());
      assertTrue(ex.getMessage().contains(id.toString()), "Message must identify the ascription");
      assertTrue(ex.getMessage().contains(from.name()), "Message must report the source status");
      assertTrue(ex.getMessage().contains(to.name()), "Message must report the target status");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource(
        "cloud.poesis.sie.defman.service.AscriptionStateMachineServiceMatrixTest#illegalStatusPairs")
    void refereeValidationIsANoOpOnIllegalPairs(
        AscriptionStatusType from, AscriptionStatusType to) {
      for (AscriptionStatusType refereeStatus : AscriptionStatusType.values()) {
        List<Map.Entry<AscriptionEntity, String>> refs = refs(refereeStatus);
        assertDoesNotThrow(
            () -> stateMachine.validateRefereePreconditions(refs, from, to),
            () ->
                "Non-edges carry no referee window, so referee validation must not throw for "
                    + from
                    + " -> "
                    + to
                    + " with referee status "
                    + refereeStatus);
      }
    }
  }

  // ========================================================================
  // Referee preconditions — every edge against every referee status
  // ========================================================================

  @Nested
  class RefereePreconditions {

    @ParameterizedTest(name = "{0} with referee {1}")
    @MethodSource(
        "cloud.poesis.sie.defman.service.AscriptionStateMachineServiceMatrixTest#edgeRefereeCombinations")
    void everyEdgeEnforcesItsRefereeWindow(Edge edge, AscriptionStatusType refereeStatus) {
      List<Map.Entry<AscriptionEntity, String>> refs = refs(refereeStatus);

      if (edge.refereeWindow().contains(refereeStatus)) {
        assertDoesNotThrow(
            () -> stateMachine.validateRefereePreconditions(refs, edge.from(), edge.to()));
        return;
      }

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> stateMachine.validateRefereePreconditions(refs, edge.from(), edge.to()));
      assertEquals(
          AscriptionStatusTransitionRuleType
              .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains(refereeStatus.name()));
      assertTrue(
          ex.getMessage().contains(edge.from() == null ? "creation" : edge.from().name()),
          "Message must report the originating status or creation");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cloud.poesis.sie.defman.service.AscriptionStateMachineServiceMatrixTest#edges")
    void emptyRefereeListIsAlwaysAccepted(Edge edge) {
      assertDoesNotThrow(
          () -> stateMachine.validateRefereePreconditions(List.of(), edge.from(), edge.to()));
    }
  }

  // ========================================================================
  // Dependent cascade applicability — all 81 pairs
  // ========================================================================

  @Nested
  class DependentCascadeApplicability {

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource(
        "cloud.poesis.sie.defman.service.AscriptionStateMachineServiceMatrixTest#statusPairs")
    void cascadeApplicabilityMatchesSpecification(
        AscriptionStatusType from, AscriptionStatusType to) {
      boolean expected =
          AscriptionLifecycleSpec.edge(from, to).map(Edge::dependentCascade).orElse(false);
      assertEquals(expected, stateMachine.isDependentCascadeApplicable(from, to));
    }
  }

  private static List<Map.Entry<AscriptionEntity, String>> refs(AscriptionStatusType status) {
    AscriptionEntity referee = mock(AscriptionEntity.class);
    when(referee.getStatus()).thenReturn(status);
    when(referee.getId()).thenReturn(UUID.randomUUID());
    return List.of(Map.entry(referee, "referee"));
  }
}
