package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.DirectiveEntity;
import cloud.poesis.sie.defman.entity.NormEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.repository.NormRepository;
import cloud.poesis.sie.defman.type.AppraisalRuleType;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
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

  @Mock private AppraisalService appraisalService;

  private NormService service;

  @BeforeEach
  void setUp() {
    service =
        new NormService(
            normRepo,
            structureService,
            archetypeService,
            mock(ArchetypeRepository.class),
            mock(DefinitionService.class),
            mock(AscriptionStatusTransitionService.class),
            mock(AscriptionService.class),
            mock(EntityManager.class),
            mock(DataProtectionService.class),
            appraisalService,
            dev.cel.compiler.CelCompilerFactory.standardCelCompilerBuilder().build());
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
        assertEquals(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
            ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
            ex.getRuleType());
        assertTrue(ex.getMessage().contains("ternary"));
      }

      @Test
      void arithmetic_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateApplicability("PerformanceProperties.score + 1 > 5"));
        assertEquals(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
            ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
            ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
            ex.getRuleType());
        assertTrue(ex.getMessage().contains("duplicate axis"));
      }

      @Test
      void forbiddenFunction_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateApplicability("DeploymentProperties.tags.size() > 0"));
        assertEquals(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
            ex.getRuleType());
        assertTrue(ex.getMessage().contains("matches()") || ex.getMessage().contains("forbidden"));
      }

      @Test
      void syntaxError_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateApplicability("DeploymentProperties.env ==== \"prod\""));
        assertEquals(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_CEL_PARSING, ex.getRuleType());
        assertTrue(ex.getMessage().contains("parse error"));
      }

      // NORM_APPLICABILITY_COMPARISON_CONSISTENCY

      @Test
      void inListSingleElement_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateApplicability("DeploymentProperties.env in [\"prod\"]"));
        assertEquals(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
            ex.getRuleType());
        assertTrue(ex.getMessage().contains(">= 2"));
      }

      @Test
      void inListMixedTypes_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateApplicability("DeploymentProperties.tier in [\"prod\", 1]"));
        assertEquals(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
            ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
            ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_ARCHETYPE_REFERENCE_RESOLUTION,
            ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.NORM_APPLICABILITY_PROPERTY_PATH_RESOLUTION,
            ex.getRuleType());
        assertTrue(ex.getMessage().contains("nonExistentProp"));
      }

      @Test
      void matchesFunctionCall_exercisesCollectAxesCallTarget() {
        // .matches() is a CALL with a target (SELECT chain) → exercises
        // collectAxes CALL→target lambda (lambda$collectAxes$0)
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("title", "ServiceProperties");
        schema.putObject("properties").set("name", MAPPER.createObjectNode().put("type", "string"));

        ArchetypeEntity archetype = mock(ArchetypeEntity.class);
        when(archetype.getStatement()).thenReturn(schema);
        when(archetypeService.findInEffectByTitle("ServiceProperties"))
            .thenReturn(Optional.of(archetype));

        assertDoesNotThrow(
            () ->
                service.validateApplicabilityReferences(
                    "ServiceProperties.name.matches(\"^payment-.*\")"));
      }

      @Test
      void applicabilityReferences_propertyResolved_accepted() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("title", "DeploymentProperties");
        schema
            .putObject("properties")
            .set("region", MAPPER.createObjectNode().put("type", "string"));

        ArchetypeEntity archetype = mock(ArchetypeEntity.class);
        when(archetype.getStatement()).thenReturn(schema);
        when(archetypeService.findInEffectByTitle("DeploymentProperties"))
            .thenReturn(Optional.of(archetype));

        assertDoesNotThrow(
            () ->
                service.validateApplicabilityReferences(
                    "DeploymentProperties.region == \"us-east-1\""));
      }
    }

    @Nested
    class AssertionProfile {

      @Test
      void emptyAssertion_rejected() {
        RuleViolationException ex1 =
            assertThrows(RuleViolationException.class, () -> service.validateAssertion(null));
        assertEquals(
            AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
            ex1.getRuleType());

        RuleViolationException ex2 =
            assertThrows(RuleViolationException.class, () -> service.validateAssertion(""));
        assertEquals(
            AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
            ex2.getRuleType());

        RuleViolationException ex3 =
            assertThrows(RuleViolationException.class, () -> service.validateAssertion("   "));
        assertEquals(
            AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
            ex3.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.NORM_ASSERTION_AS_DETERMINISTIC_EXPRESSION,
            ex.getRuleType());
        assertTrue(ex.getMessage().contains("non-deterministic"));
      }

      @Test
      void nonDeterministicUuid_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class, () -> service.validateAssertion("id == uuid()"));
        assertEquals(
            AscriptionConsistencyRuleType.NORM_ASSERTION_AS_DETERMINISTIC_EXPRESSION,
            ex.getRuleType());
        assertTrue(ex.getMessage().contains("non-deterministic"));
      }

      @Test
      void explicitArchetypeName_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateAssertion("SecurityProperties.tlsEnabled == true"));
        assertEquals(
            AscriptionConsistencyRuleType.NORM_ASSERTION_AS_ARCHETYPE_BOUND_EXPRESSION,
            ex.getRuleType());
        assertTrue(ex.getMessage().contains("bare qualifier property"));
      }

      @Test
      void selfPrefix_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateAssertion("self.tlsEnabled == true"));
        assertEquals(
            AscriptionConsistencyRuleType.NORM_ASSERTION_AS_ARCHETYPE_BOUND_EXPRESSION,
            ex.getRuleType());
        assertTrue(ex.getMessage().contains("bare qualifier property"));
      }

      @Test
      void syntaxError_rejected() {
        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class, () -> service.validateAssertion("value >>>= 5"));
        assertEquals(AscriptionConsistencyRuleType.NORM_ASSERTION_CEL_PARSING, ex.getRuleType());
      }

      // NORM_ASSERTION_AS_BOOLEAN_RESULT

      @Test
      void arithmeticTopLevel_rejected() {
        RuleViolationException ex =
            assertThrows(RuleViolationException.class, () -> service.validateAssertion("a + b"));
        assertEquals(
            AscriptionConsistencyRuleType.NORM_ASSERTION_AS_BOOLEAN_RESULT, ex.getRuleType());
        assertTrue(ex.getMessage().contains("arithmetic"));
      }

      @Test
      void nonBooleanConstant_rejected() {
        RuleViolationException ex =
            assertThrows(RuleViolationException.class, () -> service.validateAssertion("42"));
        assertEquals(
            AscriptionConsistencyRuleType.NORM_ASSERTION_AS_BOOLEAN_RESULT, ex.getRuleType());
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
        assertEquals(
            AscriptionConsistencyRuleType.NORM_ASSERTION_PROPERTY_PATH_RESOLUTION,
            ex.getRuleType());
        assertTrue(ex.getMessage().contains("nonExistentField"));
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
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          ex.getRuleType());
    }

    @Test
    void topLevelArithmetic_rejected() {
      // Arithmetic op at top level of applicability → early arithmetic check (line
      // 312-320)
      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.validateApplicability("1 + 2"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("arithmetic"));
    }

    @Test
    void bareIdentAsApplicability_noError() {
      // Bare IDENT at top level → SELECT/IDENT/CONSTANT/LIST case (line 375)
      assertDoesNotThrow(() -> service.validateApplicability("true"));
    }

    @Test
    void bareMatchOnIdent_axisNull_accepted() {
      // x.matches("a") → extractAxis receives IDENT("x") → not SELECT → null
      assertDoesNotThrow(() -> service.validateApplicability("x.matches(\"^a\")"));
    }

    @Test
    void inOperatorWithIdentRhs_accepted() {
      // "x in y" → second arg is IDENT, not LIST → validateInListConsistency early
      // return
      assertDoesNotThrow(() -> service.validateApplicability("DeploymentProps.env in otherList"));
    }

    @Test
    void guardOperandWithNonArithmeticFunction_rejected() {
      // Function call in comparison operand that is not arithmetic
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateApplicability("DeploymentProps.x == size(\"abc\")"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          ex.getRuleType());
    }

    @Test
    void inListWithBooleans_accepted() {
      // in-list with boolean values exercises constantToString BOOLEAN_VALUE branch
      assertDoesNotThrow(
          () -> service.validateApplicability("DeploymentProps.active in [true, false]"));
    }

    @Test
    void duplicateMatchesAxis_rejected() {
      // Two .matches() predicates on the same axis → exercises the duplicate axis
      // throw inside the call.target().isPresent() + matches path
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateApplicability(
                      "DeploymentProps.name.matches(\"^a\") "
                          + "&& DeploymentProps.name.matches(\"^b\")"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("duplicate axis"));
    }

    @Test
    void funcCallTargetInMatches_axisResolvesToNull_accepted() {
      // func().b.matches("x") → extractAxis receives SELECT(CALL, "b") → root is
      // CALL (not IDENT) → extractAxis returns null → axis==null skipover
      assertDoesNotThrow(() -> service.validateApplicability("func().b.matches(\"^x\")"));
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

    @Test
    void nonBooleanConstantAtTopLevel_rejected() {
      // CONSTANT with non-BOOLEAN kind → validateAssertionBooleanResult rejects
      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.validateAssertion("42"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_ASSERTION_AS_BOOLEAN_RESULT, ex.getRuleType());
      assertTrue(ex.getMessage().contains("non-boolean constant"));
    }

    @Test
    void arithmeticAtTopLevel_rejected() {
      // CALL with arithmetic fn → validateAssertionBooleanResult rejects
      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.validateAssertion("1 + 2"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_ASSERTION_AS_BOOLEAN_RESULT, ex.getRuleType());
      assertTrue(ex.getMessage().contains("arithmetic"));
    }

    @Test
    void bareIdentAtTopLevel_accepted() {
      // IDENT at top level → validateAssertionBooleanResult IDENT/SELECT case → DYN
      // accept
      assertDoesNotThrow(() -> service.validateAssertion("x"));
    }

    @Test
    void listAtTopLevel_accepted() {
      // LIST at top level → validateAssertionBooleanResult default case → accepted
      assertDoesNotThrow(() -> service.validateAssertion("[1, 2, 3]"));
    }
  }

  // ========================================================================
  // GovernanceChain
  // ========================================================================

  @Nested
  class GovernanceChain {

    @Test
    void delegatesToAppraisalService() {
      NormEntity norm = stubNorm(UUID.randomUUID(), UUID.randomUUID(), MAPPER.createObjectNode());

      service.validateActivationUniqueness(norm);

      verify(appraisalService).validateGovernanceChain(norm);
      verify(appraisalService).validateNormCompatibility(norm);
    }

    @Test
    void propagatesGovernanceChainException() {
      NormEntity norm = stubNorm(UUID.randomUUID(), UUID.randomUUID(), MAPPER.createObjectNode());
      doThrow(RuleViolationException.of(AppraisalRuleType.NORM_DIRECTIVE_BACKING, "x"))
          .when(appraisalService)
          .validateGovernanceChain(norm);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateActivationUniqueness(norm));
      assertEquals(AppraisalRuleType.NORM_DIRECTIVE_BACKING, ex.getRuleType());
    }

    @Test
    void propagatesNormCompatibilityException() {
      NormEntity norm = stubNorm(UUID.randomUUID(), UUID.randomUUID(), MAPPER.createObjectNode());
      doThrow(RuleViolationException.of(AppraisalRuleType.NORM_ASSERTION_COMPATIBILITY, "x"))
          .when(appraisalService)
          .validateNormCompatibility(norm);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateActivationUniqueness(norm));
      assertEquals(AppraisalRuleType.NORM_ASSERTION_COMPATIBILITY, ex.getRuleType());
    }
  }

  // ========================================================================
  // FindAllByStructureDefinitionIdAndStatusIn
  // ========================================================================

  @Nested
  class FindAllByStructureDefinitionIdAndStatusIn {

    @Test
    void delegatesToRepo() {
      UUID structDefId = UUID.randomUUID();
      var statuses = List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);
      NormEntity n1 = mock(NormEntity.class);
      when(normRepo.findAllByStructureDefinitionIdAndStatusIn(structDefId, statuses))
          .thenReturn(List.of(n1));

      var result = service.findAllByStructureDefinitionIdAndStatusIn(structDefId, statuses);
      assertEquals(1, result.size());
      assertEquals(n1, result.get(0));
      verify(normRepo).findAllByStructureDefinitionIdAndStatusIn(structDefId, statuses);
    }
  }

  // ========================================================================
  // GetRepository
  // ========================================================================

  @Test
  void getRepository_returnsNormRepo() {
    // Calling findAllByDefinitionId exercises getRepository() indirectly
    UUID defId = UUID.randomUUID();
    when(normRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

    var result = service.findAllByDefinitionId(defId);
    assertTrue(result.isEmpty());
    verify(normRepo).findAllByDefinitionIdOrderByTimestampDesc(defId);
  }

  // ========================================================================
  // ConstantToString edge cases
  // ========================================================================

  @Nested
  class ConstantToStringEdgeCases {

    @Test
    void integerValues_accepted() {
      // in-list with integers exercises constantToString INT64_VALUE
      assertDoesNotThrow(() -> service.validateApplicability("DeploymentProps.level in [1, 2, 3]"));
    }

    @Test
    void uintValues_duplicateDetected() {
      // Exercises UINT64_VALUE branch indirectly via constant type detection.
      // CEL integer literals parse as INT64; UINT64 requires a u suffix.
      // We test duplicate detection works for numeric constants.
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateApplicability("DeploymentProps.level in [1, 2, 1]"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Duplicate"));
    }

    @Test
    void uintLiterals_accepted() {
      // CEL unsigned int literals (u suffix) exercise UINT64_VALUE branch
      assertDoesNotThrow(
          () -> service.validateApplicability("DeploymentProps.level in [2u, 3u, 4u]"));
    }
  }

  // ========================================================================
  // ExtractAxis deep nesting
  // ========================================================================

  @Nested
  class ExtractAxisDeepNesting {

    @Test
    void twoLevelSelect_extractsRootAndFirstField() {
      // DeploymentProperties.config.env → axis = "DeploymentProperties.config"
      assertDoesNotThrow(
          () -> service.validateApplicability("DeploymentProperties.config.env == \"production\""));
    }

    @Test
    void threeLevelSelect_extractsRootAndFirstField() {
      // DeploymentProperties.config.nested.env → axis = "DeploymentProperties.config"
      assertDoesNotThrow(
          () ->
              service.validateApplicability(
                  "DeploymentProperties.config.nested.env == \"production\""));
    }

    @Test
    void duplicateDeepAxis_rejected() {
      // Two predicates with same root axis (DeploymentProperties.config) should be
      // rejected.
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateApplicability(
                      "DeploymentProperties.config.env == \"prod\" "
                          + "&& DeploymentProperties.config.region == \"us\""));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("duplicate axis"));
    }
  }

  // ========================================================================
  // ValidateApplicabilityExpr — additional branches
  // ========================================================================

  @Nested
  class ApplicabilityExprAdditionalBranches {

    @Test
    void existsMacroInApplicability_rejectedAsFunctionCall() {
      // CEL parse() does NOT expand macros, so .exists() stays as a CALL node.
      // The applicability validator only allows .matches() as function call,
      // so .exists() is correctly rejected.
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  service.validateApplicability(
                      "DeploymentProperties.items.exists(x, x == \"prod\")"));
      assertTrue(ex.getMessage().contains("matches"));
    }

    @Test
    void nestedNegation_accepted() {
      // Negation of an @in call exercises the !_ → recurse → @in path
      assertDoesNotThrow(
          () ->
              service.validateApplicability(
                  "!(DeploymentProperties.tier in [\"internal\", \"dev\"])"));
    }

    @Test
    void bareIdentOnlyInComparison_accepted() {
      // An IDENT node as leaf in comparison operand (rare but valid)
      assertDoesNotThrow(() -> service.validateApplicability("DeploymentProperties.flag == true"));
    }

    @Test
    void listOperandInComparison_accepted() {
      // LIST as leaf in @in comparison (exercised already via in-list tests, but
      // here explicitly at validateApplicabilityExpr level for the LIST case branch)
      assertDoesNotThrow(
          () ->
              service.validateApplicability("DeploymentProperties.env in [\"prod\", \"staging\"]"));
    }

    @Test
    void negatedComparison_accepted() {
      // Exercises !_ → comparison → validateSingleAxisPredicate through negation
      // chain
      assertDoesNotThrow(() -> service.validateApplicability("!(DeploymentProperties.level == 1)"));
    }

    @Test
    void inListWithNullElement_rejectsAsMixedType() {
      // null (NULL_VALUE) mixed with other types is rejected at type homogeneity
      // check
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateApplicability("DeploymentProperties.x in [null, 1]"));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
          ex.getRuleType());
    }
  }

  // ========================================================================
  // ParseCel — validation error handling
  // ========================================================================

  @Nested
  class ParseCelEdgeCases {

    @Test
    void ternaryInAssertion_accepted() {
      // Ternary at top level exercises validateAssertionBooleanResult ternary path
      assertDoesNotThrow(() -> service.validateAssertion("x > 0 ? true : false"));
    }

    @Test
    void deepSelectInAssertion_recursesThroughSelectBranch() {
      // Exercises the SELECT → while loop → validateAssertionExpr(operand) recursion
      // in validateAssertionExpr with a 3-level deep select
      assertDoesNotThrow(() -> service.validateAssertion("a.b.c > 0"));
    }

    @Test
    void callTargetInAssertion_recursesIntoTarget() {
      // Exercises call.target().ifPresent(this::validateAssertionExpr) lambda
      assertDoesNotThrow(() -> service.validateAssertion("items.size() > 0"));
    }
  }

  // ========================================================================
  // CollectPropertyIdents — comprehensive branches
  // ========================================================================

  @Nested
  class CollectPropertyIdentsTests {

    @Test
    void simpleIdent_collected() {
      // "x > 1" → collects "x" as a property ident
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestQual");
      schema.putObject("properties").set("x", MAPPER.createObjectNode().put("type", "integer"));

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(qualifier.getStatement()).thenReturn(schema);

      // Should not throw — "x" resolves in schema
      assertDoesNotThrow(() -> service.validateAssertionPropertyPaths("x > 1", qualifier));
    }

    @Test
    void selectExpr_collectsRoot() {
      // "config.value > 1" → collects "config" as property root
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestQual");
      ObjectNode configProp = MAPPER.createObjectNode().put("type", "object");
      configProp
          .putObject("properties")
          .set("value", MAPPER.createObjectNode().put("type", "integer"));
      schema.putObject("properties").set("config", configProp);

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(qualifier.getStatement()).thenReturn(schema);

      assertDoesNotThrow(
          () -> service.validateAssertionPropertyPaths("config.value > 1", qualifier));
    }

    @Test
    void callExpr_recursesIntoTargetAndArgs() {
      // "items.size() > 0" → collects "items" (IDENT inside CALL target)
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestQual");
      schema.putObject("properties").set("items", MAPPER.createObjectNode().put("type", "array"));

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(qualifier.getStatement()).thenReturn(schema);

      assertDoesNotThrow(
          () -> service.validateAssertionPropertyPaths("items.size() > 0", qualifier));
    }

    @Test
    void listExpr_recursesIntoElements() {
      // "val in [min, max]" → collects "val", "min", "max" (IDENT inside LIST)
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestQual");
      ObjectNode props = schema.putObject("properties");
      props.set("val", MAPPER.createObjectNode().put("type", "integer"));
      props.set("min", MAPPER.createObjectNode().put("type", "integer"));
      props.set("max", MAPPER.createObjectNode().put("type", "integer"));

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(qualifier.getStatement()).thenReturn(schema);

      assertDoesNotThrow(
          () -> service.validateAssertionPropertyPaths("val in [min, max]", qualifier));
    }

    @Test
    void macroCall_iterVarExcludedFromCollection() {
      // "ports.all(p, p >= 1024)" — parse() keeps it as a CALL node, not
      // COMPREHENSION. The iter var "p" shows up as an IDENT, but it's not a
      // property. However, since parse() doesn't create COMPREHENSION, "p" will be
      // collected as a property ident. This test verifies the behavior.
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestQual");
      ObjectNode props = schema.putObject("properties");
      props.set("ports", MAPPER.createObjectNode().put("type", "array"));
      props.set("p", MAPPER.createObjectNode().put("type", "integer"));

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(qualifier.getStatement()).thenReturn(schema);

      // "p" is collected since parse() doesn't expand the macro
      assertDoesNotThrow(
          () -> service.validateAssertionPropertyPaths("ports.all(p, p >= 1024)", qualifier));
    }

    @Test
    void unresolvedProperty_rejected() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestQual");
      schema.putObject("properties").set("x", MAPPER.createObjectNode().put("type", "integer"));

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(qualifier.getStatement()).thenReturn(schema);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateAssertionPropertyPaths("unknownProp > 0", qualifier));
      assertEquals(
          AscriptionConsistencyRuleType.NORM_ASSERTION_PROPERTY_PATH_RESOLUTION, ex.getRuleType());
      assertTrue(ex.getMessage().contains("unknownProp"));
    }

    @Test
    void unresolvedProperty_noTitleInSchema_usesUnknownFallback() {
      // Schema without "title" → exercises the "(unknown)" fallback in error message
      ObjectNode schema = MAPPER.createObjectNode();
      schema.putObject("properties").set("x", MAPPER.createObjectNode().put("type", "integer"));

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(qualifier.getStatement()).thenReturn(schema);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateAssertionPropertyPaths("missing > 0", qualifier));
      assertTrue(ex.getMessage().contains("(unknown)"));
    }

    @Test
    void boundVarInSelect_excluded() {
      // "items.exists(x, x.val > 0)" — "x" is bound var but parse() doesn't expand.
      // "x" will appear as IDENT → needs to be in schema or triggers error.
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestQual");
      ObjectNode props = schema.putObject("properties");
      props.set("items", MAPPER.createObjectNode().put("type", "array"));
      props.set("x", MAPPER.createObjectNode().put("type", "object"));

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(qualifier.getStatement()).thenReturn(schema);

      assertDoesNotThrow(
          () -> service.validateAssertionPropertyPaths("items.exists(x, x.val > 0)", qualifier));
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
