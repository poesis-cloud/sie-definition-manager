package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.exception.GsmRuleViolationException;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.NormEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.repository.NormRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NormServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NormService service;

    @BeforeEach
    void setUp() {
        service = new NormService(
                mock(NormRepository.class),
                mock(StructureService.class),
                mock(ArchetypeService.class));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private NormEntity stubNorm(UUID structDefId, UUID qualDefId, ObjectNode statement) {
        StructureEntity structure = mock(StructureEntity.class);
        DefinitionEntity structDef = mock(DefinitionEntity.class);
        when(structDef.getId()).thenReturn(structDefId);
        when(structure.getDefinition()).thenReturn(structDef);

        ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
        DefinitionEntity qualDef = mock(DefinitionEntity.class);
        when(qualDef.getId()).thenReturn(qualDefId);
        when(qualifier.getDefinition()).thenReturn(qualDef);

        NormEntity entity = mock(NormEntity.class);
        when(entity.getStructure()).thenReturn(structure);
        when(entity.getQualifier()).thenReturn(qualifier);
        when(entity.getStatement()).thenReturn(statement);

        return entity;
    }

    // ========================================================================
    // CelProfile
    // ========================================================================

    @Nested
    class CelProfile {

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
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuard(
                                "DeploymentProperties.env == \"prod\" "
                                        + "|| DeploymentProperties.env == \"staging\""));
                assertTrue(ex.getMessage().contains("||"));
                assertTrue(ex.getMessage().contains("forbidden"));
            }

            @Test
            void ternary_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuard(
                                "DeploymentProperties.env == \"prod\" ? true : false"));
                assertTrue(ex.getMessage().contains("ternary"));
            }

            @Test
            void arithmetic_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuard("PerformanceProperties.score + 1 > 5"));
                assertTrue(ex.getMessage().contains("arithmetic"));
            }

            @Test
            void crossPropertyComparison_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuard(
                                "PerformanceProperties.actual > PerformanceProperties.budget"));
                assertTrue(ex.getMessage().contains("cross-property")
                        || ex.getMessage().contains("duplicate axis"));
            }

            @Test
            void duplicateAxis_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuard(
                                "DeploymentProperties.env == \"prod\" "
                                        + "&& DeploymentProperties.env == \"staging\""));
                assertTrue(ex.getMessage().contains("duplicate axis"));
            }

            @Test
            void forbiddenFunction_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuard("DeploymentProperties.tags.size() > 0"));
                assertTrue(ex.getMessage().contains("matches()") || ex.getMessage().contains("forbidden"));
            }

            @Test
            void syntaxError_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validateGuard("DeploymentProperties.env ==== \"prod\""));
                assertTrue(ex.getMessage().contains("parse error"));
            }
        }

        @Nested
        class PredicateProfile {

            @Test
            void emptyPredicate_rejected() {
                assertThrows(GsmRuleViolationException.class, () -> service.validatePredicate(null));
                assertThrows(GsmRuleViolationException.class, () -> service.validatePredicate(""));
                assertThrows(GsmRuleViolationException.class, () -> service.validatePredicate("   "));
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
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validatePredicate("self.lastReview > now()"));
                assertTrue(ex.getMessage().contains("non-deterministic"));
            }

            @Test
            void nonDeterministicUuid_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validatePredicate("self.id == uuid()"));
                assertTrue(ex.getMessage().contains("non-deterministic"));
            }

            @Test
            void explicitArchetypeName_rejected() {
                GsmRuleViolationException ex = assertThrows(GsmRuleViolationException.class,
                        () -> service.validatePredicate("SecurityProperties.tlsEnabled == true"));
                assertTrue(ex.getMessage().contains("self"));
            }

            @Test
            void syntaxError_rejected() {
                assertThrows(GsmRuleViolationException.class,
                        () -> service.validatePredicate("self.value >>>= 5"));
            }
        }
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @Nested
    class Lifecycle {

        @Nested
        class IdentityBound {

            @Test
            void structureQualifierPredicateExtracted() {
                UUID structDefId = UUID.randomUUID();
                UUID qualDefId = UUID.randomUUID();

                ObjectNode stmt = MAPPER.createObjectNode();
                stmt.put("predicate", "self.status == 'OK'");

                NormEntity entity = stubNorm(structDefId, qualDefId, stmt);

                var values = service.getIdentityBoundValues(entity);

                assertEquals(structDefId, values.get("structure"));
                assertEquals(qualDefId, values.get("qualifier"));
                assertEquals("self.status == 'OK'", values.get("predicate"));
            }

            @Test
            void noPredicate_structureAndQualifierOnly() {
                UUID structDefId = UUID.randomUUID();
                UUID qualDefId = UUID.randomUUID();

                ObjectNode stmt = MAPPER.createObjectNode();

                NormEntity entity = stubNorm(structDefId, qualDefId, stmt);

                var values = service.getIdentityBoundValues(entity);

                assertEquals(structDefId, values.get("structure"));
                assertEquals(qualDefId, values.get("qualifier"));
                assertEquals(2, values.size());
            }
        }

        @Nested
        class RefereeReferences {

            @Test
            void referencesStructureAndQualifier() {
                UUID structDefId = UUID.randomUUID();
                UUID qualDefId = UUID.randomUUID();

                NormEntity entity = stubNorm(structDefId, qualDefId, MAPPER.createObjectNode());

                var refs = service.getRefereeReferences(entity);

                assertEquals(2, refs.size());
                assertTrue(refs.stream().anyMatch(r -> r.label().equals("structure")));
                assertTrue(refs.stream().anyMatch(r -> r.label().equals("qualifier")));
            }
        }

        @Nested
        class CascadeRoles {

            @Test
            void governingFromStructure() {
                var roles = service.getCascadeTargetRoles();

                assertEquals(1, roles.size());
                assertTrue(roles.containsKey(DefinitionSubjectType.STRUCTURE));
                assertEquals(AscriptionStatusTransitionCascadeType.GOVERNING,
                        roles.get(DefinitionSubjectType.STRUCTURE));
            }
        }
    }
}
