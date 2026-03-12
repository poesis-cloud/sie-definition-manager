package com.sif.sie.definitionmanager.validator;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CelProfileValidatorTest {

    private CelProfileValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CelProfileValidator();
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
            assertDoesNotThrow(() -> validator.validateGuard(guard));
        }

        @Test
        void singleAxisEquality_accepted() {
            assertDoesNotThrow(() ->
                    validator.validateGuard("DeploymentProperties.environment == \"production\""));
        }

        @Test
        void singleAxisInequality_accepted() {
            assertDoesNotThrow(() ->
                    validator.validateGuard("PerformanceProperties.criticality >= 3"));
        }

        @Test
        void singleAxisSetMembership_accepted() {
            assertDoesNotThrow(() ->
                    validator.validateGuard("DeploymentProperties.tier in [\"production\", \"staging\"]"));
        }

        @Test
        void singleAxisNegatedSetMembership_accepted() {
            assertDoesNotThrow(() ->
                    validator.validateGuard("!(DeploymentProperties.region in [\"cn-north-1\", \"cn-northwest-1\"])"));
        }

        @Test
        void singleAxisRegexMatch_accepted() {
            assertDoesNotThrow(() ->
                    validator.validateGuard("ServiceProperties.name.matches(\"^payment-.*\")"));
        }

        @Test
        void multiAxisConjunction_accepted() {
            assertDoesNotThrow(() ->
                    validator.validateGuard(
                            "DeploymentProperties.environment == \"production\" "
                                    + "&& ServiceProperties.classification == \"PII\""));
        }

        @Test
        void threeAxisConjunction_accepted() {
            assertDoesNotThrow(() ->
                    validator.validateGuard(
                            "DeploymentProperties.environment == \"production\" "
                                    + "&& DataProperties.classification == \"confidential\" "
                                    + "&& ServiceProperties.owner != \"deprecated-team\""));
        }

        @Test
        void disjunction_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    validator.validateGuard(
                            "DeploymentProperties.env == \"prod\" "
                                    + "|| DeploymentProperties.env == \"staging\""));
            assertTrue(ex.getMessage().contains("||"));
            assertTrue(ex.getMessage().contains("forbidden"));
        }

        @Test
        void ternary_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    validator.validateGuard(
                            "DeploymentProperties.env == \"prod\" ? true : false"));
            assertTrue(ex.getMessage().contains("ternary"));
        }

        @Test
        void arithmetic_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    validator.validateGuard("PerformanceProperties.score + 1 > 5"));
            assertTrue(ex.getMessage().contains("arithmetic"));
        }

        @Test
        void crossPropertyComparison_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    validator.validateGuard(
                            "PerformanceProperties.actual > PerformanceProperties.budget"));
            assertTrue(ex.getMessage().contains("cross-property") || ex.getMessage().contains("duplicate axis"));
        }

        @Test
        void duplicateAxis_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    validator.validateGuard(
                            "DeploymentProperties.env == \"prod\" "
                                    + "&& DeploymentProperties.env == \"staging\""));
            assertTrue(ex.getMessage().contains("duplicate axis"));
        }

        @Test
        void forbiddenFunction_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    validator.validateGuard("DeploymentProperties.tags.size() > 0"));
            assertTrue(ex.getMessage().contains("matches()") || ex.getMessage().contains("forbidden"));
        }

        @Test
        void syntaxError_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    validator.validateGuard("DeploymentProperties.env ==== \"prod\""));
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
            assertThrows(IllegalArgumentException.class, () ->
                    validator.validatePredicate(null));
            assertThrows(IllegalArgumentException.class, () ->
                    validator.validatePredicate(""));
            assertThrows(IllegalArgumentException.class, () ->
                    validator.validatePredicate("   "));
        }

        @Test
        void simpleThreshold_accepted() {
            assertDoesNotThrow(() ->
                    validator.validatePredicate("self.encryptionLevel >= \"AES-128\""));
        }

        @Test
        void booleanAnd_accepted() {
            assertDoesNotThrow(() ->
                    validator.validatePredicate(
                            "self.tlsEnabled == true && self.tlsVersion >= \"1.2\""));
        }

        @Test
        void disjunction_accepted() {
            assertDoesNotThrow(() ->
                    validator.validatePredicate(
                            "self.authMethod == \"mTLS\" || self.authMethod == \"OAuth2\""));
        }

        @Test
        void negation_accepted() {
            assertDoesNotThrow(() ->
                    validator.validatePredicate("!self.allowsAnonymousAccess"));
        }

        @Test
        void crossPropertyComparison_accepted() {
            assertDoesNotThrow(() ->
                    validator.validatePredicate("self.p99Latency < self.budget"));
        }

        @Test
        void arithmeticThreshold_accepted() {
            assertDoesNotThrow(() ->
                    validator.validatePredicate("self.p99Latency < self.budget * 1.1"));
        }

        @Test
        void setMembership_accepted() {
            assertDoesNotThrow(() ->
                    validator.validatePredicate(
                            "self.cipherSuite in [\"TLS_AES_128_GCM_SHA256\", \"TLS_AES_256_GCM_SHA384\"]"));
        }

        @Test
        void stringFunctions_accepted() {
            assertDoesNotThrow(() ->
                    validator.validatePredicate(
                            "self.schema.matches(\"^https://.*\") && !self.owner.endsWith(\"-deprecated\")"));
        }

        @Test
        void collectionMacroAll_accepted() {
            assertDoesNotThrow(() ->
                    validator.validatePredicate("self.ports.all(p, p >= 1024)"));
        }

        @Test
        void collectionMacroExists_accepted() {
            assertDoesNotThrow(() ->
                    validator.validatePredicate(
                            "self.certifications.exists(c, c == \"SOC2\" || c == \"ISO27001\")"));
        }

        @Test
        void hasFieldPresence_accepted() {
            assertDoesNotThrow(() ->
                    validator.validatePredicate("has(self.retentionPolicy)"));
        }

        @Test
        void sizeCheck_accepted() {
            assertDoesNotThrow(() ->
                    validator.validatePredicate("self.tags.size() >= 1"));
        }

        @Test
        void ternary_accepted() {
            assertDoesNotThrow(() ->
                    validator.validatePredicate(
                            "(self.tier == \"critical\" ? self.p99Latency < 100 : self.p99Latency < 500)"));
        }

        @Test
        void typeConversion_accepted() {
            assertDoesNotThrow(() ->
                    validator.validatePredicate("double(self.monthlyBudget) > 0.0"));
        }

        @Test
        void nonDeterministicNow_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    validator.validatePredicate("self.lastReview > now()"));
            assertTrue(ex.getMessage().contains("non-deterministic"));
        }

        @Test
        void nonDeterministicUuid_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    validator.validatePredicate("self.id == uuid()"));
            assertTrue(ex.getMessage().contains("non-deterministic"));
        }

        @Test
        void explicitArchetypeName_rejected() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    validator.validatePredicate("SecurityProperties.tlsEnabled == true"));
            assertTrue(ex.getMessage().contains("self"));
        }

        @Test
        void syntaxError_rejected() {
            assertThrows(IllegalArgumentException.class, () ->
                    validator.validatePredicate("self.value >>>= 5"));
        }
    }
}
