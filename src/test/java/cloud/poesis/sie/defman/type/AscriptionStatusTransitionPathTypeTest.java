package cloud.poesis.sie.defman.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AscriptionStatusTransitionPathTypeTest {

  @ParameterizedTest
  @EnumSource(AscriptionStatusTransitionPathType.class)
  void getTo_neverNull(AscriptionStatusTransitionPathType path) {
    assertNotNull(path.getTo());
  }

  @Nested
  class CreateTransition {

    @Test
    void createHasNullFrom() {
      assertNull(AscriptionStatusTransitionPathType.CREATE.getFrom());
      assertEquals(AscriptionStatusType.DRAFT, AscriptionStatusTransitionPathType.CREATE.getTo());
    }

    @Test
    void isValid_createTransition() {
      assertTrue(AscriptionStatusTransitionPathType.isValid(null, AscriptionStatusType.DRAFT));
    }
  }

  @Nested
  class ProgressPath {

    @Test
    void draftToProposed() {
      assertTrue(
          AscriptionStatusTransitionPathType.isValid(
              AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED));
    }

    @Test
    void proposedToApproved() {
      assertTrue(
          AscriptionStatusTransitionPathType.isValid(
              AscriptionStatusType.PROPOSED, AscriptionStatusType.APPROVED));
    }

    @Test
    void approvedToActive() {
      assertTrue(
          AscriptionStatusTransitionPathType.isValid(
              AscriptionStatusType.APPROVED, AscriptionStatusType.ACTIVE));
    }
  }

  @Nested
  class DegradationPath {

    @Test
    void activeToSuspended() {
      assertTrue(
          AscriptionStatusTransitionPathType.isValid(
              AscriptionStatusType.ACTIVE, AscriptionStatusType.SUSPENDED));
    }

    @Test
    void suspendedToActive() {
      assertTrue(
          AscriptionStatusTransitionPathType.isValid(
              AscriptionStatusType.SUSPENDED, AscriptionStatusType.ACTIVE));
    }

    @Test
    void activeToDeprecated() {
      assertTrue(
          AscriptionStatusTransitionPathType.isValid(
              AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED));
    }

    @Test
    void suspendedToDeprecated() {
      assertTrue(
          AscriptionStatusTransitionPathType.isValid(
              AscriptionStatusType.SUSPENDED, AscriptionStatusType.DEPRECATED));
    }

    @Test
    void deprecatedToSuspended() {
      assertTrue(
          AscriptionStatusTransitionPathType.isValid(
              AscriptionStatusType.DEPRECATED, AscriptionStatusType.SUSPENDED));
    }

    @Test
    void deprecatedToRetired() {
      assertTrue(
          AscriptionStatusTransitionPathType.isValid(
              AscriptionStatusType.DEPRECATED, AscriptionStatusType.RETIRED));
    }
  }

  @Nested
  class TerminalPaths {

    @Test
    void draftToAbandoned() {
      assertTrue(
          AscriptionStatusTransitionPathType.isValid(
              AscriptionStatusType.DRAFT, AscriptionStatusType.ABANDONED));
    }

    @Test
    void proposedToRejected() {
      assertTrue(
          AscriptionStatusTransitionPathType.isValid(
              AscriptionStatusType.PROPOSED, AscriptionStatusType.REJECTED));
    }
  }

  @Nested
  class InvalidTransitions {

    @Test
    void draftToActive_invalid() {
      assertFalse(
          AscriptionStatusTransitionPathType.isValid(
              AscriptionStatusType.DRAFT, AscriptionStatusType.ACTIVE));
    }

    @Test
    void activeToApproved_invalid() {
      assertFalse(
          AscriptionStatusTransitionPathType.isValid(
              AscriptionStatusType.ACTIVE, AscriptionStatusType.APPROVED));
    }

    @Test
    void retiredToAnything_invalid() {
      assertFalse(
          AscriptionStatusTransitionPathType.isValid(
              AscriptionStatusType.RETIRED, AscriptionStatusType.ACTIVE));
    }
  }

  @Nested
  class ValidTargets {

    @Test
    void draftHasProposedAndAbandoned() {
      Set<AscriptionStatusType> targets =
          AscriptionStatusTransitionPathType.validTargets(AscriptionStatusType.DRAFT);
      assertTrue(targets.contains(AscriptionStatusType.PROPOSED));
      assertTrue(targets.contains(AscriptionStatusType.ABANDONED));
      assertEquals(2, targets.size());
    }

    @Test
    void activeHasSuspendedAndDeprecated() {
      Set<AscriptionStatusType> targets =
          AscriptionStatusTransitionPathType.validTargets(AscriptionStatusType.ACTIVE);
      assertTrue(targets.contains(AscriptionStatusType.SUSPENDED));
      assertTrue(targets.contains(AscriptionStatusType.DEPRECATED));
    }

    @Test
    void terminalStatusReturnsEmpty() {
      assertTrue(
          AscriptionStatusTransitionPathType.validTargets(AscriptionStatusType.RETIRED).isEmpty());
      assertTrue(
          AscriptionStatusTransitionPathType.validTargets(AscriptionStatusType.ABANDONED)
              .isEmpty());
      assertTrue(
          AscriptionStatusTransitionPathType.validTargets(AscriptionStatusType.REJECTED).isEmpty());
    }
  }

  @Nested
  class IsTerminal {

    @Test
    void retiredIsTerminal() {
      assertTrue(AscriptionStatusTransitionPathType.isTerminal(AscriptionStatusType.RETIRED));
    }

    @Test
    void abandonedIsTerminal() {
      assertTrue(AscriptionStatusTransitionPathType.isTerminal(AscriptionStatusType.ABANDONED));
    }

    @Test
    void rejectedIsTerminal() {
      assertTrue(AscriptionStatusTransitionPathType.isTerminal(AscriptionStatusType.REJECTED));
    }

    @Test
    void activeIsNotTerminal() {
      assertFalse(AscriptionStatusTransitionPathType.isTerminal(AscriptionStatusType.ACTIVE));
    }

    @Test
    void draftIsNotTerminal() {
      assertFalse(AscriptionStatusTransitionPathType.isTerminal(AscriptionStatusType.DRAFT));
    }
  }
}
