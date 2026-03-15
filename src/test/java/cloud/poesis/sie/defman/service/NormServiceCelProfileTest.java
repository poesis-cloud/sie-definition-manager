package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import cloud.poesis.sie.defman.repository.NormRepository;

class NormServiceCelProfileTest {

    private NormService service;

    @BeforeEach
    void setUp() {
        service = new NormService(
                mock(NormRepository.class),
                mock(StructureService.class),
                mock(ArchetypeService.class));
    }

    // ========================================================================
    // Guard profile
    // ========================================================================

    @Nested
    class GuardProfile {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "true", " true ", "  " })
        void unconditionalGuard_accepted(String guard) {
            assertDoesNotThrow(() -> service.validateGuard(guard));
        }

        @Test
        void singleAxisEquality_accepted() {
            assertDoesNotThrow(() -> service
                    .validateGuard("DeploymentProperties.environment == \"production\""));
        }

        @Test
        void singleAxisInequality_accepted() {
            assertDoesNotThrow(() -> service.validateGuard("PerformanceProperties.criticality >= 3"));
        }

        @Test
        void singleAxisSetMembership_accepted() {
            assertDoesNotThrow(() -> service
                    .validateGuard("DeploymentProperties.tier in [\"production\", \"staging\"]"));
        }

        @Test
        void singleAxisNegatedSetMembership_accepted() {
            assertDoesNotThrow(() -> service.validateGuard(
                    "!(DeploymentProperties.region in [\"cn-north-1\", \"cn-northwest-1\"])"));
        }

        @Test
        void singleAxisRegexMatch_accepted() {
            assertDoesNotThrow(() -> service
                    .validateGuard("ServiceProperties.name.matches(\"^payment-.*\")"));
        }

        @Test
        void multiAxisConjunction_accepted() {
            assertDoesNotThrow(() -> service.validateGuard(
                    "DeploymentProperties.environment == \"production\" "
                            + "&& ServiceProperties.classification == \"PII\""));
        }

        @Test
        void threeAxisConjunction_accepted() {
            assertDoesNotThrow(() -> service.validateGuard(
                    "DeploymentProperties.environment == \"production\" "
                            + "&& DataProperties.classification == \"confidential\" "
                            + "&& ServiceProperties.owner != \"deprecated-team\""));
        }

        @Test
        void disjunction_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateGuard(
                            "DeploymentProperties.env == \"prod\" "
                                    + "|| DeploymentProperties.env == \"staging\""));
            assertTrue(ex.getMessage().contains("||"));
            assertTrue(ex.getMessage().contains("forbidden"));
        }

        @Test
        void ternary_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateGuard(
                            "DeploymentProperties.env == \"prod\" ? true : false"));
            assertTrue(ex.getMessage().contains("ternary"));
        }

        @Test
        void arithmetic_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateGuard("PerformanceProperties.score + 1 > 5"));
            assertTrue(ex.getMessage().contains("arithmetic"));
        }

        @Test
        void crossPropertyComparison_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateGuard(
                            "PerformanceProperties.actual > PerformanceProperties.budget"));
            assertTrue(ex.getMessage().contains("cross-property")
                    || ex.getMessage().contains("duplicate axis"));
        }

        @Test
        void duplicateAxis_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateGuard(
                            "DeploymentProperties.env == \"prod\" "
                                    + "&& DeploymentProperties.env == \"staging\""));
            assertTrue(ex.getMessage().contains("duplicate axis"));
        }

        @Test
        void forbiddenFunction_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateGuard("DeploymentProperties.tags.size() > 0"));
            assertTrue(ex.getMessage().contains("matches()") || ex.getMessage().contains("forbidden"));
        }

        @Test
        void syntaxError_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateGuard("DeploymentProperties.env ==== \"prod\""));
            assertTrue(ex.getMessage().contains("parse error"));
        }
    }

    // ========================================================================
    // Predicate profile
    // ========================================================================

    @Nested
    class PredicateProfile {

        @Test
        void emptyPredicate_rejected() {
            assertThrows(IllegalArgumentException.class, () -> service.validatePredicate(null));
            assertThrows(IllegalArgumentException.class, () -> service.validatePredicate(""));
            assertThrows(IllegalArgumentException.class, () -> service.validatePredicate("   "));
        }

        @Test
        void simpleThreshold_accepted() {
            assertDoesNotThrow(() -> service.validatePredicate("self.encryptionLevel >= \"AES-128\""));
        }

        @Test
        void booleanAnd_accepted() {
            assertDoesNotThrow(() -> service.validatePredicate(
                    "self.tlsEnabled == true && self.tlsVersion >= \"1.2\""));
        }

        @Test
        void disjunction_accepted() {
            assertDoesNotThrow(() -> service.validatePredicate(
                    "self.authMethod == \"mTLS\" || self.authMethod == \"OAuth2\""));
        }

        @Test
        void negation_accepted() {
            assertDoesNotThrow(() -> service.validatePredicate("!self.allowsAnonymousAccess"));
        }

        @Test
        void crossPropertyComparison_accepted() {
            assertDoesNotThrow(() -> service.validatePredicate("self.p99Latency < self.budget"));
        }

        @Test
        void arithmeticThreshold_accepted() {
            assertDoesNotThrow(() -> service.validatePredicate("self.p99Latency < self.budget * 1.1"));
        }

        @Test
        void setMembership_accepted() {
            assertDoesNotThrow(() -> service.validatePredicate(
                    "self.cipherSuite in [\"TLS_AES_128_GCM_SHA256\", \"TLS_AES_256_GCM_SHA384\"]"));
        }

        @Test
        void stringFunctions_accepted() {
            assertDoesNotThrow(() -> service.validatePredicate(
                    "self.schema.matches(\"^https://.*\") && !self.owner.endsWith(\"-deprecated\")"));
        }

        @Test
        void collectionMacroAll_accepted() {
            assertDoesNotThrow(() -> service.validatePredicate("self.ports.all(p, p >= 1024)"));
        }

        @Test
        void collectionMacroExists_accepted() {
            assertDoesNotThrow(() -> service.validatePredicate(
                    "self.certifications.exists(c, c == \"SOC2\" || c == \"ISO27001\")"));
        }

        @Test
        void hasFieldPresence_accepted() {
            assertDoesNotThrow(() -> service.validatePredicate("has(self.retentionPolicy)"));
        }

        @Test
        void sizeCheck_accepted() {
            assertDoesNotThrow(() -> service.validatePredicate("self.tags.size() >= 1"));
        }

        @Test
        void ternary_accepted() {
            assertDoesNotThrow(() -> service.validatePredicate(
                    "(self.tier == \"critical\" ? self.p99Latency < 100 : self.p99Latency < 500)"));
        }

        @Test
        void typeConversion_accepted() {
            assertDoesNotThrow(() -> service.validatePredicate("double(self.monthlyBudget) > 0.0"));
        }

        @Test
        void nonDeterministicNow_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validatePredicate("self.lastReview > now()"));
            assertTrue(ex.getMessage().contains("non-deterministic"));
        }

        @Test
        void nonDeterministicUuid_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validatePredicate("self.id == uuid()"));
            assertTrue(ex.getMessage().contains("non-deterministic"));
        }

        @Test
        void explicitArchetypeName_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validatePredicate("SecurityProperties.tlsEnabled == true"));
            assertTrue(ex.getMessage().contains("self"));
        }

        @Test
        void syntaxError_rejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.validatePredicate("self.value >>>= 5"));
        }
    }
}
