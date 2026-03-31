package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.DirectiveEntity;
import cloud.poesis.sie.defman.entity.NormEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.repository.NormRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.RuleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NormServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private NormRepository normRepo;

  @Mock private DirectiveService directiveService;

  @Mock private StructureService structureService;

  @Mock private ArchetypeService archetypeService;

  private NormService service;

  @BeforeEach
  void setUp() {
    service =
        new NormService(
            normRepo,
            directiveService,
            structureService,
            archetypeService,
            mock(ArchetypeRepository.class),
            mock(DefinitionService.class),
            mock(AscriptionStatusTransitionService.class),
            mock(AscriptionService.class),
            mock(EntityManager.class),
            mock(DataProtectionService.class));
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
    class ApplicabilityProfile {

      @ParameterizedTest
      @NullAndEmptySource
      @ValueSource(strings = {"true", " true ", "  "})
      void unconditionalApplicability_accepted(String applicability) {
        assertDoesNotThrow(() -> service.validateApplicability(applicability));
      }

      @Test
      void singleAxisEquality_accepted() {
        assertDoesNotThrow(
            () ->
                service.validateApplicability(
                    "DeploymentProperties.environment == \"production\""));
      }

      @Test
      void singleAxisInequality_accepted() {
        assertDoesNotThrow(
            () -> service.validateApplicability("PerformanceProperties.criticality >= 3"));
      }

      @Test
      void singleAxisSetMembership_accepted() {
        assertDoesNotThrow(
            () ->
                service.validateApplicability(
                    "DeploymentProperties.tier in [\"production\", \"staging\"]"));
      }

      @Test
      void singleAxisNegatedSetMembership_accepted() {
        assertDoesNotThrow(
            () ->
                service.validateApplicability(
                    "!(DeploymentProperties.region in [\"cn-north-1\", \"cn-northwest-1\"])"));
      }

      @Test
      void singleAxisRegexMatch_accepted() {
        assertDoesNotThrow(
            () -> service.validateApplicability("ServiceProperties.name.matches(\"^payment-.*\")"));
      }

      @Test
      void multiAxisConjunction_accepted() {
        assertDoesNotThrow(
            () ->
                service.validateApplicability(
                    "DeploymentProperties.environment == \"production\" "
                        + "&& ServiceProperties.classification == \"PII\""));
      }

      @Test
      void threeAxisConjunction_accepted() {
        assertDoesNotThrow(
            () ->
                service.validateApplicability(
                    "DeploymentProperties.environment == \"production\" "
                        + "&& DataProperties.classification == \"confidential\" "
                        + "&& ServiceProperties.owner != \"deprecated-team\""));
      }

      @Test
      void disjunction_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateApplicability(
                        "DeploymentProperties.env == \"prod\" "
                            + "|| DeploymentProperties.env == \"staging\""));
        assertEquals(RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
        assertTrue(ex.getMessage().contains("||"));
        assertTrue(ex.getMessage().contains("forbidden"));
      }

      @Test
      void ternary_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateApplicability(
                        "DeploymentProperties.env == \"prod\" ? true : false"));
        assertEquals(RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
        assertTrue(ex.getMessage().contains("ternary"));
      }

      @Test
      void arithmetic_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateApplicability("PerformanceProperties.score + 1 > 5"));
        assertEquals(RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
        assertTrue(ex.getMessage().contains("arithmetic"));
      }

      @Test
      void crossPropertyComparison_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateApplicability(
                        "PerformanceProperties.actual > PerformanceProperties.budget"));
        assertEquals(RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
        assertTrue(
            ex.getMessage().contains("cross-property")
                || ex.getMessage().contains("duplicate axis"));
      }

      @Test
      void duplicateAxis_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateApplicability(
                        "DeploymentProperties.env == \"prod\" "
                            + "&& DeploymentProperties.env == \"staging\""));
        assertEquals(RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
        assertTrue(ex.getMessage().contains("duplicate axis"));
      }

      @Test
      void forbiddenFunction_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateApplicability("DeploymentProperties.tags.size() > 0"));
        assertEquals(RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
        assertTrue(ex.getMessage().contains("matches()") || ex.getMessage().contains("forbidden"));
      }

      @Test
      void syntaxError_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateApplicability("DeploymentProperties.env ==== \"prod\""));
        assertEquals(RuleType.NORM_APPLICABILITY_CEL_PARSING, ex.getRuleType());
        assertTrue(ex.getMessage().contains("parse error"));
      }

      // NORM_APPLICABILITY_COMPARISON_CONSISTENCY

      @Test
      void inListSingleElement_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateApplicability("DeploymentProperties.env in [\"prod\"]"));
        assertEquals(RuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY, ex.getRuleType());
        assertTrue(ex.getMessage().contains(">= 2"));
      }

      @Test
      void inListMixedTypes_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateApplicability("DeploymentProperties.tier in [\"prod\", 1]"));
        assertEquals(RuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY, ex.getRuleType());
        assertTrue(ex.getMessage().contains("type-homogeneous"));
      }

      @Test
      void inListDuplicates_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateApplicability(
                        "DeploymentProperties.tier in [\"prod\", \"staging\", \"prod\"]"));
        assertEquals(RuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY, ex.getRuleType());
        assertTrue(ex.getMessage().contains("Duplicate"));
      }

      // NORM_APPLICABILITY_ARCHETYPE_REFERENCE_RESOLUTION

      @Test
      void applicabilityArchetypeNotFound_rejected() {
        when(archetypeService.findInEffectByTitle("NonExistent")).thenReturn(Optional.empty());

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateApplicabilityReferences(
                        "NonExistent.environment == \"production\""));
        assertEquals(RuleType.NORM_APPLICABILITY_ARCHETYPE_REFERENCE_RESOLUTION, ex.getRuleType());
        assertTrue(ex.getMessage().contains("NonExistent"));
      }

      // NORM_APPLICABILITY_PROPERTY_PATH_RESOLUTION

      @Test
      void applicabilityPropertyNotInSchema_rejected() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("title", "DeploymentProperties");
        schema
            .putObject("properties")
            .set("region", MAPPER.createObjectNode().put("type", "string"));

        ArchetypeEntity archetype = mock(ArchetypeEntity.class);
        when(archetype.getStatement()).thenReturn(schema);
        when(archetypeService.findInEffectByTitle("DeploymentProperties"))
            .thenReturn(Optional.of(archetype));

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () ->
                    service.validateApplicabilityReferences(
                        "DeploymentProperties.nonExistentProp == \"x\""));
        assertEquals(RuleType.NORM_APPLICABILITY_PROPERTY_PATH_RESOLUTION, ex.getRuleType());
        assertTrue(ex.getMessage().contains("nonExistentProp"));
      }
    }

    @Nested
    class AssertionProfile {

      @Test
      void emptyAssertion_rejected() {
        RuleViolationException ex1 =
            assertThrows(RuleViolationException.class, () -> service.validateAssertion(null));
        assertEquals(RuleType.NORM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex1.getRuleType());

        RuleViolationException ex2 =
            assertThrows(RuleViolationException.class, () -> service.validateAssertion(""));
        assertEquals(RuleType.NORM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex2.getRuleType());

        RuleViolationException ex3 =
            assertThrows(RuleViolationException.class, () -> service.validateAssertion("   "));
        assertEquals(RuleType.NORM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex3.getRuleType());
      }

      @Test
      void simpleThreshold_accepted() {
        assertDoesNotThrow(() -> service.validateAssertion("encryptionLevel >= \"AES-128\""));
      }

      @Test
      void booleanAnd_accepted() {
        assertDoesNotThrow(
            () -> service.validateAssertion("tlsEnabled == true && tlsVersion >= \"1.2\""));
      }

      @Test
      void disjunction_accepted() {
        assertDoesNotThrow(
            () -> service.validateAssertion("authMethod == \"mTLS\" || authMethod == \"OAuth2\""));
      }

      @Test
      void negation_accepted() {
        assertDoesNotThrow(() -> service.validateAssertion("!allowsAnonymousAccess"));
      }

      @Test
      void crossPropertyComparison_accepted() {
        assertDoesNotThrow(() -> service.validateAssertion("p99Latency < budget"));
      }

      @Test
      void arithmeticThreshold_accepted() {
        assertDoesNotThrow(() -> service.validateAssertion("p99Latency < budget * 1.1"));
      }

      @Test
      void setMembership_accepted() {
        assertDoesNotThrow(
            () ->
                service.validateAssertion(
                    "cipherSuite in [\"TLS_AES_128_GCM_SHA256\", \"TLS_AES_256_GCM_SHA384\"]"));
      }

      @Test
      void stringFunctions_accepted() {
        assertDoesNotThrow(
            () ->
                service.validateAssertion(
                    "schema.matches(\"^https://.*\") && !owner.endsWith(\"-deprecated\")"));
      }

      @Test
      void collectionMacroAll_accepted() {
        assertDoesNotThrow(() -> service.validateAssertion("ports.all(p, p >= 1024)"));
      }

      @Test
      void collectionMacroExists_accepted() {
        assertDoesNotThrow(
            () ->
                service.validateAssertion(
                    "certifications.exists(c, c == \"SOC2\" || c == \"ISO27001\")"));
      }

      @Test
      void hasFieldPresence_accepted() {
        assertDoesNotThrow(() -> service.validateAssertion("has(config.retentionPolicy)"));
      }

      @Test
      void sizeCheck_accepted() {
        assertDoesNotThrow(() -> service.validateAssertion("tags.size() >= 1"));
      }

      @Test
      void ternary_accepted() {
        assertDoesNotThrow(
            () ->
                service.validateAssertion(
                    "(tier == \"critical\" ? p99Latency < 100 : p99Latency < 500)"));
      }

      @Test
      void typeConversion_accepted() {
        assertDoesNotThrow(() -> service.validateAssertion("double(monthlyBudget) > 0.0"));
      }

      @Test
      void nonDeterministicNow_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateAssertion("lastReview > now()"));
        assertEquals(RuleType.NORM_ASSERTION_AS_DETERMINISTIC_EXPRESSION, ex.getRuleType());
        assertTrue(ex.getMessage().contains("non-deterministic"));
      }

      @Test
      void nonDeterministicUuid_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class, () -> service.validateAssertion("id == uuid()"));
        assertEquals(RuleType.NORM_ASSERTION_AS_DETERMINISTIC_EXPRESSION, ex.getRuleType());
        assertTrue(ex.getMessage().contains("non-deterministic"));
      }

      @Test
      void explicitArchetypeName_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateAssertion("SecurityProperties.tlsEnabled == true"));
        assertEquals(RuleType.NORM_ASSERTION_AS_ARCHETYPE_BOUND_EXPRESSION, ex.getRuleType());
        assertTrue(ex.getMessage().contains("bare qualifier property"));
      }

      @Test
      void selfPrefix_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateAssertion("self.tlsEnabled == true"));
        assertEquals(RuleType.NORM_ASSERTION_AS_ARCHETYPE_BOUND_EXPRESSION, ex.getRuleType());
        assertTrue(ex.getMessage().contains("bare qualifier property"));
      }

      @Test
      void syntaxError_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class, () -> service.validateAssertion("value >>>= 5"));
        assertEquals(RuleType.NORM_ASSERTION_CEL_PARSING, ex.getRuleType());
      }

      // NORM_ASSERTION_AS_BOOLEAN_RESULT

      @Test
      void arithmeticTopLevel_rejected() {
        RuleViolationException ex =
            assertThrows(RuleViolationException.class, () -> service.validateAssertion("a + b"));
        assertEquals(RuleType.NORM_ASSERTION_AS_BOOLEAN_RESULT, ex.getRuleType());
        assertTrue(ex.getMessage().contains("arithmetic"));
      }

      @Test
      void nonBooleanConstant_rejected() {
        RuleViolationException ex =
            assertThrows(RuleViolationException.class, () -> service.validateAssertion("42"));
        assertEquals(RuleType.NORM_ASSERTION_AS_BOOLEAN_RESULT, ex.getRuleType());
        assertTrue(ex.getMessage().contains("non-boolean constant"));
      }

      // NORM_ASSERTION_PROPERTY_PATH_RESOLUTION

      @Test
      void predicatePropertyNotInQualifier_rejected() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("title", "SecurityProperties");
        schema
            .putObject("properties")
            .set("tlsEnabled", MAPPER.createObjectNode().put("type", "boolean"));

        ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
        when(qualifier.getStatement()).thenReturn(schema);

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateAssertionPropertyPaths("nonExistentField > 0", qualifier));
        assertEquals(RuleType.NORM_ASSERTION_PROPERTY_PATH_RESOLUTION, ex.getRuleType());
        assertTrue(ex.getMessage().contains("nonExistentField"));
      }
    }
  }

  // ========================================================================
  // Tolerance Mode Consistency
  // ========================================================================

  @Nested
  class ToleranceMode {

    @Test
    void instantaneous_withTemporalWindow_rejected() {
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("toleranceMode", "INSTANTANEOUS");
      stmt.put("temporalWindow", "PT5M");

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> NormService.validateToleranceModeConsistency(stmt));
      assertEquals(RuleType.NORM_ASSERTION_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
      assertTrue(ex.getMessage().contains("INSTANTANEOUS"));
    }

    @Test
    void aggregated_missingWindow_rejected() {
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("toleranceMode", "AGGREGATED");
      stmt.put("temporalAggregation", "P99");

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> NormService.validateToleranceModeConsistency(stmt));
      assertEquals(RuleType.NORM_ASSERTION_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
      assertTrue(ex.getMessage().contains("AGGREGATED"));
    }

    @Test
    void aggregated_missingAggregation_rejected() {
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("toleranceMode", "AGGREGATED");
      stmt.put("temporalWindow", "PT5M");

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> NormService.validateToleranceModeConsistency(stmt));
      assertEquals(RuleType.NORM_ASSERTION_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
      assertTrue(ex.getMessage().contains("AGGREGATED"));
    }

    @Test
    void aggregated_withSustainedThreshold_rejected() {
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("toleranceMode", "AGGREGATED");
      stmt.put("temporalWindow", "PT5M");
      stmt.put("temporalAggregation", "P99");
      stmt.put("sustainedThreshold", 0.99);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> NormService.validateToleranceModeConsistency(stmt));
      assertEquals(RuleType.NORM_ASSERTION_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
      assertTrue(ex.getMessage().contains("forbids sustainedThreshold"));
    }

    @Test
    void sustained_missingFields_rejected() {
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("toleranceMode", "SUSTAINED");
      stmt.put("temporalWindow", "PT5M");

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> NormService.validateToleranceModeConsistency(stmt));
      assertEquals(RuleType.NORM_ASSERTION_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
      assertTrue(ex.getMessage().contains("SUSTAINED"));
    }

    @Test
    void sustained_thresholdOutOfRange_rejected() {
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("toleranceMode", "SUSTAINED");
      stmt.put("temporalWindow", "PT5M");
      stmt.put("temporalAggregation", "AVG");
      stmt.put("sustainedThreshold", 1.5);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> NormService.validateToleranceModeConsistency(stmt));
      assertEquals(RuleType.NORM_ASSERTION_TOLERANCE_MODE_CONSISTENCY, ex.getRuleType());
      assertTrue(ex.getMessage().contains("[0, 1]"));
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
      void structureQualifierAssertionExtracted() {
        UUID structDefId = UUID.randomUUID();
        UUID qualDefId = UUID.randomUUID();

        ObjectNode stmt = MAPPER.createObjectNode();
        stmt.put("assertion", "status == 'OK'");

        NormEntity entity = stubNorm(structDefId, qualDefId, stmt);

        var values = service.getIdentityBoundValues(entity);

        assertEquals(structDefId, values.get("structure"));
        assertEquals(qualDefId, values.get("qualifier"));
        assertEquals("status == 'OK'", values.get("assertion"));
      }

      @Test
      void noAssertion_structureAndQualifierOnly() {
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
        assertEquals(
            AscriptionStatusTransitionCascadeType.GOVERNING,
            roles.get(DefinitionSubjectType.STRUCTURE));
      }
    }
  }

  // ========================================================================
  // BuildEntity
  // ========================================================================

  @Nested
  class BuildEntity {

    @Test
    void validStatement_returnsEntity() {
      UUID structId = UUID.randomUUID();
      UUID qualId = UUID.randomUUID();

      StructureEntity structure = mock(StructureEntity.class);
      when(structureService.findEntityById(structId)).thenReturn(structure);

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      ObjectNode qualSchema = MAPPER.createObjectNode();
      qualSchema.put("title", "TestQual");
      qualSchema
          .putObject("properties")
          .set("status", MAPPER.createObjectNode().put("type", "string"));
      when(qualifier.getStatement()).thenReturn(qualSchema);
      when(archetypeService.findEntityById(qualId)).thenReturn(qualifier);

      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);

      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("structure", structId.toString());
      stmt.put("qualifier", qualId.toString());
      stmt.put("assertion", "status == \"OK\"");
      stmt.put("toleranceMode", "INSTANTANEOUS");

      NormEntity result = service.buildEntity(def, archetype, stmt);
      assertEquals(def, result.getDefinition());
      assertEquals(structure, result.getStructure());
      assertEquals(qualifier, result.getQualifier());
    }

    @Test
    void missingStructure_rejected() {
      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("qualifier", UUID.randomUUID().toString());
      stmt.put("assertion", "x > 1");
      stmt.put("toleranceMode", "INSTANTANEOUS");

      assertThrows(RuleViolationException.class, () -> service.buildEntity(def, archetype, stmt));
    }

    @Test
    void missingQualifier_rejected() {
      UUID structId = UUID.randomUUID();
      StructureEntity structure = mock(StructureEntity.class);
      when(structureService.findEntityById(structId)).thenReturn(structure);

      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("structure", structId.toString());
      stmt.put("assertion", "x > 1");
      stmt.put("toleranceMode", "INSTANTANEOUS");

      assertThrows(RuleViolationException.class, () -> service.buildEntity(def, archetype, stmt));
    }

    @Test
    void withApplicability_validatesGuardReferencesAndPredicate() {
      UUID structId = UUID.randomUUID();
      UUID qualId = UUID.randomUUID();

      StructureEntity structure = mock(StructureEntity.class);
      when(structureService.findEntityById(structId)).thenReturn(structure);

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      ObjectNode qualSchema = MAPPER.createObjectNode();
      qualSchema.put("title", "TestQual");
      qualSchema
          .putObject("properties")
          .set("status", MAPPER.createObjectNode().put("type", "string"));
      when(qualifier.getStatement()).thenReturn(qualSchema);
      when(archetypeService.findEntityById(qualId)).thenReturn(qualifier);

      // Applicability references DeploymentProps — stub the archetype lookup
      ArchetypeEntity deployArch = mock(ArchetypeEntity.class);
      ObjectNode deploySchema = MAPPER.createObjectNode();
      deploySchema.put("title", "DeploymentProperties");
      deploySchema
          .putObject("properties")
          .set("environment", MAPPER.createObjectNode().put("type", "string"));
      when(deployArch.getStatement()).thenReturn(deploySchema);
      when(archetypeService.findInEffectByTitle("DeploymentProperties"))
          .thenReturn(Optional.of(deployArch));

      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("structure", structId.toString());
      stmt.put("qualifier", qualId.toString());
      stmt.put("applicability", "DeploymentProperties.environment == \"production\"");
      stmt.put("assertion", "status == \"OK\"");
      stmt.put("toleranceMode", "INSTANTANEOUS");

      NormEntity result = service.buildEntity(def, archetype, stmt);
      assertEquals(def, result.getDefinition());
    }

    @Test
    void withApplicability_trueDefault_skipsGuardReferences() {
      UUID structId = UUID.randomUUID();
      UUID qualId = UUID.randomUUID();

      StructureEntity structure = mock(StructureEntity.class);
      when(structureService.findEntityById(structId)).thenReturn(structure);

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      ObjectNode qualSchema = MAPPER.createObjectNode();
      qualSchema.put("title", "TestQual");
      qualSchema
          .putObject("properties")
          .set("status", MAPPER.createObjectNode().put("type", "string"));
      when(qualifier.getStatement()).thenReturn(qualSchema);
      when(archetypeService.findEntityById(qualId)).thenReturn(qualifier);

      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("structure", structId.toString());
      stmt.put("qualifier", qualId.toString());
      stmt.put("applicability", "true");
      stmt.put("assertion", "status == \"OK\"");
      stmt.put("toleranceMode", "INSTANTANEOUS");

      NormEntity result = service.buildEntity(def, archetype, stmt);
      assertEquals(def, result.getDefinition());
    }
  }

  // ========================================================================
  // FindCascadeTargetsFrom
  // ========================================================================

  @Nested
  class FindCascadeTargetsFromTests {

    @Test
    void structureSource_delegatesToRepo() {
      UUID sourceId = UUID.randomUUID();
      NormEntity n1 = mock(NormEntity.class);
      when(normRepo.findAllByStructureId(sourceId)).thenReturn(List.of(n1));

      var result = service.findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, sourceId);
      assertEquals(1, result.size());
    }

    @Test
    void nonStructureSource_returnsEmpty() {
      var result = service.findCascadeTargetsFrom(DefinitionSubjectType.NORM, UUID.randomUUID());
      assertTrue(result.isEmpty());
    }
  }

  // ========================================================================
  // GetSubjectType / GetRepository
  // ========================================================================

  @Test
  void getSubjectType_returnsNorm() {
    assertEquals(DefinitionSubjectType.NORM, service.getSubjectType());
  }

  // ========================================================================
  // ToleranceModeEdgeCases
  // ========================================================================

  @Nested
  class ToleranceModeEdgeCases {

    @Test
    void noToleranceMode_accepted() {
      ObjectNode stmt = MAPPER.createObjectNode();
      assertDoesNotThrow(() -> NormService.validateToleranceModeConsistency(stmt));
    }

    @Test
    void unknownToleranceMode_accepted() {
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("toleranceMode", "UNKNOWN_MODE");
      // unknown mode falls through to default → no exception
      assertDoesNotThrow(() -> NormService.validateToleranceModeConsistency(stmt));
    }
  }

  // ========================================================================
  // ApplicabilityExprEdgeCases
  // ========================================================================

  @Nested
  class ApplicabilityExprEdgeCases {

    @Test
    void bareFunctionCall_rejected() {
      // A bare function call (non-targeted, non-comparison) → rejected
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateApplicability("timestamp(\"2024-01-01T00:00:00Z\")"));
      assertEquals(RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
    }

    @Test
    void guardOperandWithNonArithmeticFunction_rejected() {
      // Function call in comparison operand that is not arithmetic
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateApplicability("DeploymentProps.x == size(\"abc\")"));
      assertEquals(RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM, ex.getRuleType());
    }

    @Test
    void inListWithBooleans_accepted() {
      // in-list with boolean values exercises constantToString BOOLEAN_VALUE branch
      assertDoesNotThrow(
          () -> service.validateApplicability("DeploymentProps.active in [true, false]"));
    }

    @Test
    void inListWithDoubles_accepted() {
      // in-list with double values exercises constantToString DOUBLE_VALUE branch
      assertDoesNotThrow(
          () -> service.validateApplicability("DeploymentProps.score in [1.5, 2.5, 3.5]"));
    }
  }

  // ========================================================================
  // AssertionExprEdgeCases
  // ========================================================================

  @Nested
  class AssertionExprEdgeCases {

    @Test
    void listExprInAssertion_accepted() {
      // LIST branch in validateAssertionExpr
      assertDoesNotThrow(() -> service.validateAssertion("tags.exists(t, t in [\"a\", \"b\"])"));
    }

    @Test
    void unknownFunctionAtTopLevel_accepted() {
      // validateAssertionBooleanResult → unknown function → accept (DYN)
      assertDoesNotThrow(() -> service.validateAssertion("items.someCustomFunc()"));
    }
  }

  // ========================================================================
  // GovernanceChain
  // ========================================================================

  @Nested
  class GovernanceChain {

    @Test
    void noDirectives_rejects() {
      UUID structDefId = UUID.randomUUID();
      UUID qualDefId = UUID.randomUUID();
      UUID qualId = UUID.randomUUID();

      NormEntity norm = stubNormWithIds(structDefId, qualDefId, qualId, "encryptionLevel >= 1");

      when(directiveService.findAllByPurposeDefinitionIdAndStatusIn(
              eq(structDefId), anyCollection()))
          .thenReturn(List.of());

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateActivationUniqueness(norm));
      assertEquals(RuleType.NORM_GOVERNANCE_CHAIN, ex.getRuleType());
    }

    @Test
    void directiveWithMatchingPurposeAndQualifier_accepts() {
      UUID structDefId = UUID.randomUUID();
      UUID qualDefId = UUID.randomUUID();
      UUID qualId = UUID.randomUUID();
      UUID normDefId = UUID.randomUUID();

      NormEntity norm =
          stubNormWithIds(structDefId, qualDefId, qualId, normDefId, "encryptionLevel >= 1");

      UUID directiveQualId = UUID.randomUUID();
      DirectiveEntity directive = stubDirectiveWithQualifier(directiveQualId);

      when(directiveService.findAllByPurposeDefinitionIdAndStatusIn(
              eq(structDefId), anyCollection()))
          .thenReturn(List.of(directive));

      // Norm qualifier ancestors contain "SecurityProperties"
      when(archetypeService.getAncestorTitles(qualId))
          .thenReturn(new java.util.LinkedHashSet<>(List.of("SecurityProperties")));
      // Directive qualifier ancestors also contain "SecurityProperties"
      when(archetypeService.getAncestorTitles(directiveQualId))
          .thenReturn(new java.util.LinkedHashSet<>(List.of("SecurityProperties")));

      // No in-effect siblings for conflict detection
      when(normRepo.findAllByStructureDefinitionIdAndStatusIn(eq(structDefId), anyCollection()))
          .thenReturn(List.of());

      assertDoesNotThrow(() -> service.validateActivationUniqueness(norm));
    }

    @Test
    void directiveWithDisjointQualifier_rejects() {
      UUID structDefId = UUID.randomUUID();
      UUID qualDefId = UUID.randomUUID();
      UUID qualId = UUID.randomUUID();

      NormEntity norm = stubNormWithIds(structDefId, qualDefId, qualId, "encryptionLevel >= 1");

      UUID directiveQualId = UUID.randomUUID();
      DirectiveEntity directive = stubDirectiveWithQualifier(directiveQualId);

      when(directiveService.findAllByPurposeDefinitionIdAndStatusIn(
              eq(structDefId), anyCollection()))
          .thenReturn(List.of(directive));

      // Disjoint lineages
      when(archetypeService.getAncestorTitles(qualId))
          .thenReturn(new java.util.LinkedHashSet<>(List.of("SecurityProperties")));
      when(archetypeService.getAncestorTitles(directiveQualId))
          .thenReturn(new java.util.LinkedHashSet<>(List.of("PerformanceProperties")));

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateActivationUniqueness(norm));
      assertEquals(RuleType.NORM_GOVERNANCE_CHAIN, ex.getRuleType());
    }

    @Test
    void directiveWithAncestorQualifier_accepts() {
      // Norm qualifier is "DetailedSecurity", Directive qualifier is
      // "SecurityProperties"
      // DetailedSecurity extends SecurityProperties → lineage overlap
      UUID structDefId = UUID.randomUUID();
      UUID qualDefId = UUID.randomUUID();
      UUID qualId = UUID.randomUUID();
      UUID normDefId = UUID.randomUUID();

      NormEntity norm =
          stubNormWithIds(structDefId, qualDefId, qualId, normDefId, "encryptionLevel >= 1");

      UUID directiveQualId = UUID.randomUUID();
      DirectiveEntity directive = stubDirectiveWithQualifier(directiveQualId);

      when(directiveService.findAllByPurposeDefinitionIdAndStatusIn(
              eq(structDefId), anyCollection()))
          .thenReturn(List.of(directive));

      // Norm qualifier lineage: DetailedSecurity -> SecurityProperties ->
      // StructureArchetype
      when(archetypeService.getAncestorTitles(qualId))
          .thenReturn(
              new java.util.LinkedHashSet<>(
                  List.of("DetailedSecurity", "SecurityProperties", "StructureArchetype")));
      // Directive qualifier lineage: SecurityProperties -> StructureArchetype
      when(archetypeService.getAncestorTitles(directiveQualId))
          .thenReturn(
              new java.util.LinkedHashSet<>(List.of("SecurityProperties", "StructureArchetype")));

      when(normRepo.findAllByStructureDefinitionIdAndStatusIn(eq(structDefId), anyCollection()))
          .thenReturn(List.of());

      assertDoesNotThrow(() -> service.validateActivationUniqueness(norm));
    }
  }

  // ========================================================================
  // NormConflict
  // ========================================================================

  @Nested
  class NormConflict {

    @Test
    void noSiblings_noConflict() {
      UUID structDefId = UUID.randomUUID();
      UUID qualDefId = UUID.randomUUID();
      UUID qualId = UUID.randomUUID();
      UUID normDefId = UUID.randomUUID();

      NormEntity norm =
          stubNormWithIds(structDefId, qualDefId, qualId, normDefId, "encryptionLevel >= 1");

      // Governance chain passes
      UUID directiveQualId = UUID.randomUUID();
      DirectiveEntity directive = stubDirectiveWithQualifier(directiveQualId);
      when(directiveService.findAllByPurposeDefinitionIdAndStatusIn(
              eq(structDefId), anyCollection()))
          .thenReturn(List.of(directive));
      when(archetypeService.getAncestorTitles(qualId))
          .thenReturn(new java.util.LinkedHashSet<>(List.of("SecurityProperties")));
      when(archetypeService.getAncestorTitles(directiveQualId))
          .thenReturn(new java.util.LinkedHashSet<>(List.of("SecurityProperties")));

      when(normRepo.findAllByStructureDefinitionIdAndStatusIn(eq(structDefId), anyCollection()))
          .thenReturn(List.of());

      assertDoesNotThrow(() -> service.validateActivationUniqueness(norm));
    }

    @Test
    void siblingWithDisjointQualifier_noConflict() {
      UUID structDefId = UUID.randomUUID();
      UUID qualDefId = UUID.randomUUID();
      UUID qualId = UUID.randomUUID();
      UUID normDefId = UUID.randomUUID();

      NormEntity norm =
          stubNormWithIds(structDefId, qualDefId, qualId, normDefId, "encryptionLevel >= 1");

      // Governance chain passes
      UUID directiveQualId = UUID.randomUUID();
      DirectiveEntity directive = stubDirectiveWithQualifier(directiveQualId);
      when(directiveService.findAllByPurposeDefinitionIdAndStatusIn(
              eq(structDefId), anyCollection()))
          .thenReturn(List.of(directive));
      when(archetypeService.getAncestorTitles(qualId))
          .thenReturn(new java.util.LinkedHashSet<>(List.of("SecurityProperties")));
      when(archetypeService.getAncestorTitles(directiveQualId))
          .thenReturn(new java.util.LinkedHashSet<>(List.of("SecurityProperties")));

      // Sibling targets different qualifier
      UUID siblingQualId = UUID.randomUUID();
      UUID siblingDefId = UUID.randomUUID();
      NormEntity sibling =
          stubNormWithIds(structDefId, siblingDefId, siblingQualId, "latency < 100");
      when(normRepo.findAllByStructureDefinitionIdAndStatusIn(eq(structDefId), anyCollection()))
          .thenReturn(List.of(sibling));

      when(archetypeService.getAncestorTitles(siblingQualId))
          .thenReturn(new java.util.LinkedHashSet<>(List.of("PerformanceProperties")));

      // No conflict: disjoint qualifiers
      assertDoesNotThrow(() -> service.validateActivationUniqueness(norm));
    }

    @Test
    void siblingWithOverlappingQualifierAndCommonProperties_warns() {
      UUID structDefId = UUID.randomUUID();
      UUID qualDefId = UUID.randomUUID();
      UUID qualId = UUID.randomUUID();
      UUID normDefId = UUID.randomUUID();

      NormEntity norm =
          stubNormWithIds(structDefId, qualDefId, qualId, normDefId, "encryptionLevel >= 1");

      // Governance chain passes
      UUID directiveQualId = UUID.randomUUID();
      DirectiveEntity directive = stubDirectiveWithQualifier(directiveQualId);
      when(directiveService.findAllByPurposeDefinitionIdAndStatusIn(
              eq(structDefId), anyCollection()))
          .thenReturn(List.of(directive));
      when(archetypeService.getAncestorTitles(qualId))
          .thenReturn(new java.util.LinkedHashSet<>(List.of("SecurityProperties")));
      when(archetypeService.getAncestorTitles(directiveQualId))
          .thenReturn(new java.util.LinkedHashSet<>(List.of("SecurityProperties")));

      // Sibling targets same qualifier with different assertion on same property
      UUID siblingQualId = UUID.randomUUID();
      UUID siblingDefId = UUID.randomUUID();
      NormEntity sibling =
          stubNormWithIds(structDefId, siblingDefId, siblingQualId, "encryptionLevel >= 2");
      when(normRepo.findAllByStructureDefinitionIdAndStatusIn(eq(structDefId), anyCollection()))
          .thenReturn(List.of(sibling));

      when(archetypeService.getAncestorTitles(siblingQualId))
          .thenReturn(new java.util.LinkedHashSet<>(List.of("SecurityProperties")));

      // Warns but doesn't throw (conflict detection is advisory)
      assertDoesNotThrow(() -> service.validateActivationUniqueness(norm));
    }
  }

  // ========================================================================
  // Additional test helpers
  // ========================================================================

  private NormEntity stubNormWithIds(
      UUID structDefId, UUID qualDefId, UUID qualId, String assertion) {
    return stubNormWithIds(structDefId, qualDefId, qualId, UUID.randomUUID(), assertion);
  }

  private NormEntity stubNormWithIds(
      UUID structDefId, UUID qualDefId, UUID qualId, UUID normDefId, String assertion) {
    ObjectNode statement = MAPPER.createObjectNode();
    statement.put("assertion", assertion);

    StructureEntity structure = mock(StructureEntity.class);
    DefinitionEntity structDef = mock(DefinitionEntity.class);
    when(structDef.getId()).thenReturn(structDefId);
    when(structure.getDefinition()).thenReturn(structDef);

    ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
    when(qualifier.getId()).thenReturn(qualId);
    DefinitionEntity qualDef = mock(DefinitionEntity.class);
    when(qualDef.getId()).thenReturn(qualDefId);
    when(qualifier.getDefinition()).thenReturn(qualDef);

    DefinitionEntity normDef = mock(DefinitionEntity.class);
    when(normDef.getId()).thenReturn(normDefId);

    NormEntity entity = mock(NormEntity.class);
    when(entity.getStructure()).thenReturn(structure);
    when(entity.getQualifier()).thenReturn(qualifier);
    when(entity.getStatement()).thenReturn(statement);
    when(entity.getDefinition()).thenReturn(normDef);
    when(entity.getId()).thenReturn(UUID.randomUUID());

    return entity;
  }

  private DirectiveEntity stubDirectiveWithQualifier(UUID qualId) {
    DirectiveEntity directive = mock(DirectiveEntity.class);
    ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
    when(qualifier.getId()).thenReturn(qualId);
    when(directive.getQualifier()).thenReturn(qualifier);
    return directive;
  }
}
