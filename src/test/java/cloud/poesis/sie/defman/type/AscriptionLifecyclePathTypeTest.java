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

class AscriptionLifecyclePathTypeTest {

    @ParameterizedTest
    @EnumSource(AscriptionLifecyclePathType.class)
    void getTo_neverNull(AscriptionLifecyclePathType path) {
        assertNotNull(path.getTo());
    }

    @Nested
    class CreateTransition {

        @Test
        void createHasNullFrom() {
            assertNull(AscriptionLifecyclePathType.CREATE.getFrom());
            assertEquals(AscriptionStatusType.DRAFT, AscriptionLifecyclePathType.CREATE.getTo());
        }

        @Test
        void isValid_createTransition() {
            assertTrue(AscriptionLifecyclePathType.isValid(null, AscriptionStatusType.DRAFT));
        }
    }

    @Nested
    class ProgressPath {

        @Test
        void draftToProposed() {
            assertTrue(
                    AscriptionLifecyclePathType.isValid(
                            AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED));
        }

        @Test
        void proposedToApproved() {
            assertTrue(
                    AscriptionLifecyclePathType.isValid(
                            AscriptionStatusType.PROPOSED, AscriptionStatusType.APPROVED));
        }

        @Test
        void approvedToActive() {
            assertTrue(
                    AscriptionLifecyclePathType.isValid(
                            AscriptionStatusType.APPROVED, AscriptionStatusType.ACTIVE));
        }
    }

    @Nested
    class DegradationPath {

        @Test
        void activeToSuspended() {
            assertTrue(
                    AscriptionLifecyclePathType.isValid(
                            AscriptionStatusType.ACTIVE, AscriptionStatusType.SUSPENDED));
        }

        @Test
        void suspendedToActive() {
            assertTrue(
                    AscriptionLifecyclePathType.isValid(
                            AscriptionStatusType.SUSPENDED, AscriptionStatusType.ACTIVE));
        }

        @Test
        void activeToDeprecated() {
            assertTrue(
                    AscriptionLifecyclePathType.isValid(
                            AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED));
        }

        @Test
        void suspendedToDeprecated() {
            assertTrue(
                    AscriptionLifecyclePathType.isValid(
                            AscriptionStatusType.SUSPENDED, AscriptionStatusType.DEPRECATED));
        }

        @Test
        void deprecatedToSuspended() {
            assertTrue(
                    AscriptionLifecyclePathType.isValid(
                            AscriptionStatusType.DEPRECATED, AscriptionStatusType.SUSPENDED));
        }

        @Test
        void deprecatedToRetired() {
            assertTrue(
                    AscriptionLifecyclePathType.isValid(
                            AscriptionStatusType.DEPRECATED, AscriptionStatusType.RETIRED));
        }
    }

    @Nested
    class TerminalPaths {

        @Test
        void draftToAbandoned() {
            assertTrue(
                    AscriptionLifecyclePathType.isValid(
                            AscriptionStatusType.DRAFT, AscriptionStatusType.ABANDONED));
        }

        @Test
        void proposedToRejected() {
            assertTrue(
                    AscriptionLifecyclePathType.isValid(
                            AscriptionStatusType.PROPOSED, AscriptionStatusType.REJECTED));
        }
    }

    @Nested
    class InvalidTransitions {

        @Test
        void draftToActive_invalid() {
            assertFalse(
                    AscriptionLifecyclePathType.isValid(
                            AscriptionStatusType.DRAFT, AscriptionStatusType.ACTIVE));
        }

        @Test
        void activeToApproved_invalid() {
            assertFalse(
                    AscriptionLifecyclePathType.isValid(
                            AscriptionStatusType.ACTIVE, AscriptionStatusType.APPROVED));
        }

        @Test
        void retiredToAnything_invalid() {
            assertFalse(
                    AscriptionLifecyclePathType.isValid(
                            AscriptionStatusType.RETIRED, AscriptionStatusType.ACTIVE));
        }
    }

    @Nested
    class ValidTargets {

        @Test
        void draftHasProposedAndAbandoned() {
            Set<AscriptionStatusType> targets = AscriptionLifecyclePathType.validTargets(AscriptionStatusType.DRAFT);
            assertTrue(targets.contains(AscriptionStatusType.PROPOSED));
            assertTrue(targets.contains(AscriptionStatusType.ABANDONED));
            assertEquals(2, targets.size());
        }

        @Test
        void activeHasSuspendedAndDeprecated() {
            Set<AscriptionStatusType> targets = AscriptionLifecyclePathType.validTargets(AscriptionStatusType.ACTIVE);
            assertTrue(targets.contains(AscriptionStatusType.SUSPENDED));
            assertTrue(targets.contains(AscriptionStatusType.DEPRECATED));
        }

        @Test
        void terminalStatusReturnsEmpty() {
            assertTrue(AscriptionLifecyclePathType.validTargets(AscriptionStatusType.RETIRED).isEmpty());
            assertTrue(
                    AscriptionLifecyclePathType.validTargets(AscriptionStatusType.ABANDONED).isEmpty());
            assertTrue(AscriptionLifecyclePathType.validTargets(AscriptionStatusType.REJECTED).isEmpty());
        }
    }

    @Nested
    class IsTerminal {

        @Test
        void retiredIsTerminal() {
            assertTrue(AscriptionLifecyclePathType.isTerminal(AscriptionStatusType.RETIRED));
        }

        @Test
        void abandonedIsTerminal() {
            assertTrue(AscriptionLifecyclePathType.isTerminal(AscriptionStatusType.ABANDONED));
        }

        @Test
        void rejectedIsTerminal() {
            assertTrue(AscriptionLifecyclePathType.isTerminal(AscriptionStatusType.REJECTED));
        }

        @Test
        void activeIsNotTerminal() {
            assertFalse(AscriptionLifecyclePathType.isTerminal(AscriptionStatusType.ACTIVE));
        }

        @Test
        void draftIsNotTerminal() {
            assertFalse(AscriptionLifecyclePathType.isTerminal(AscriptionStatusType.DRAFT));
        }
    }
}
