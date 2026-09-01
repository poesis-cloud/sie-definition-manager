package cloud.poesis.sie.defman.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.poesis.sie.defman.type.AscriptionLifecycleSpec.Edge;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exhaustive conformance of {@link AscriptionStatusTransitionPathType} to {@link
 * AscriptionLifecycleSpec}: every one of the 9 x 9 status pairs plus the 9 creation pairs is
 * asserted, so an edge cannot be added, removed, or re-scoped without a matching change to the
 * hand-written spec table.
 */
class AscriptionStatusTransitionPathTypeMatrixTest {

  static Stream<Arguments> statusPairs() {
    return AscriptionLifecycleSpec.allStatusPairs().stream()
        .map(pair -> Arguments.of(pair[0], pair[1]));
  }

  // ========================================================================
  // Edge table
  // ========================================================================

  @Nested
  class EdgeTable {

    @Test
    void enumDeclaresExactlyTheSpecifiedEdges() {
      Set<String> declared = new LinkedHashSet<>();
      for (AscriptionStatusTransitionPathType path : AscriptionStatusTransitionPathType.values()) {
        declared.add(path.getFrom() + "->" + path.getTo());
      }
      Set<String> specified = new LinkedHashSet<>();
      for (Edge edge : AscriptionLifecycleSpec.EDGES) {
        specified.add(edge.from() + "->" + edge.to());
      }
      assertEquals(specified, declared);
      assertEquals(
          AscriptionLifecycleSpec.EDGES.size(),
          AscriptionStatusTransitionPathType.values().length,
          "Each specified edge must be declared exactly once");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource(
        "cloud.poesis.sie.defman.type.AscriptionStatusTransitionPathTypeMatrixTest#statusPairs")
    void isValidMatchesSpecification(AscriptionStatusType from, AscriptionStatusType to) {
      assertEquals(
          AscriptionLifecycleSpec.isLegal(from, to),
          AscriptionStatusTransitionPathType.isValid(from, to));
    }

    @ParameterizedTest(name = "creation -> {0}")
    @EnumSource(AscriptionStatusType.class)
    void creationIsValidOnlyIntoDraft(AscriptionStatusType to) {
      assertEquals(
          AscriptionLifecycleSpec.isLegal(null, to),
          AscriptionStatusTransitionPathType.isValid(null, to));
      assertEquals(to == AscriptionStatusType.DRAFT, AscriptionLifecycleSpec.isLegal(null, to));
    }

    @ParameterizedTest
    @EnumSource(AscriptionStatusType.class)
    void validTargetsMatchesSpecification(AscriptionStatusType from) {
      assertEquals(
          AscriptionLifecycleSpec.targetsOf(from),
          AscriptionStatusTransitionPathType.validTargets(from));
    }

    @ParameterizedTest
    @EnumSource(AscriptionStatusType.class)
    void isTerminalMatchesSpecification(AscriptionStatusType status) {
      boolean terminal = AscriptionLifecycleSpec.TERMINAL_STATUSES.contains(status);
      assertEquals(terminal, AscriptionStatusTransitionPathType.isTerminal(status));
      assertEquals(
          terminal,
          AscriptionStatusTransitionPathType.validTargets(status).isEmpty(),
          "A terminal status is exactly a status with no outgoing edge");
    }
  }

  // ========================================================================
  // Referee windows
  // ========================================================================

  @Nested
  class RefereeWindows {

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource(
        "cloud.poesis.sie.defman.type.AscriptionStatusTransitionPathTypeMatrixTest#statusPairs")
    void refereeWindowMatchesSpecification(AscriptionStatusType from, AscriptionStatusType to) {
      Set<AscriptionStatusType> actual =
          AscriptionStatusTransitionPathType.refereeAllowedStatuses(from, to);
      AscriptionLifecycleSpec.edge(from, to)
          .ifPresentOrElse(
              edge -> {
                assertNotNull(actual, "A declared edge must expose a referee window");
                assertEquals(edge.refereeWindow(), actual);
              },
              () -> assertNull(actual, "A non-edge must expose no referee window"));
    }

    @ParameterizedTest(name = "creation -> {0}")
    @EnumSource(AscriptionStatusType.class)
    void creationRefereeWindowMatchesSpecification(AscriptionStatusType to) {
      Set<AscriptionStatusType> actual =
          AscriptionStatusTransitionPathType.refereeAllowedStatuses(null, to);
      AscriptionLifecycleSpec.edge(null, to)
          .ifPresentOrElse(
              edge -> assertEquals(edge.refereeWindow(), actual), () -> assertNull(actual));
    }

    @Test
    void abandonAcceptsAnyRefereeStatusWhileRejectExcludesDraft() {
      Set<AscriptionStatusType> abandon =
          AscriptionStatusTransitionPathType.refereeAllowedStatuses(
              AscriptionStatusType.DRAFT, AscriptionStatusType.ABANDONED);
      Set<AscriptionStatusType> reject =
          AscriptionStatusTransitionPathType.refereeAllowedStatuses(
              AscriptionStatusType.PROPOSED, AscriptionStatusType.REJECTED);
      assertEquals(AscriptionStatusType.values().length, abandon.size());
      assertEquals(AscriptionStatusType.values().length - 1, reject.size());
      assertFalse(reject.contains(AscriptionStatusType.DRAFT));
    }
  }

  // ========================================================================
  // Dependent cascade applicability
  // ========================================================================

  @Nested
  class DependentCascade {

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource(
        "cloud.poesis.sie.defman.type.AscriptionStatusTransitionPathTypeMatrixTest#statusPairs")
    void cascadeFlagMatchesSpecification(AscriptionStatusType from, AscriptionStatusType to) {
      boolean expected =
          AscriptionLifecycleSpec.edge(from, to).map(Edge::dependentCascade).orElse(false);
      assertEquals(
          expected, AscriptionStatusTransitionPathType.isDependentCascadeApplicable(from, to));
    }

    @Test
    void progressEdgesNeverCascade() {
      assertFalse(
          AscriptionStatusTransitionPathType.isDependentCascadeApplicable(
              AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED));
      assertFalse(
          AscriptionStatusTransitionPathType.isDependentCascadeApplicable(
              AscriptionStatusType.PROPOSED, AscriptionStatusType.APPROVED));
      assertFalse(
          AscriptionStatusTransitionPathType.isDependentCascadeApplicable(
              AscriptionStatusType.APPROVED, AscriptionStatusType.ACTIVE));
      assertTrue(
          AscriptionStatusTransitionPathType.isDependentCascadeApplicable(
              AscriptionStatusType.ACTIVE, AscriptionStatusType.SUSPENDED));
    }
  }
}
