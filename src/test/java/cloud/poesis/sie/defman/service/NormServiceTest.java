package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.NormEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.NormRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionPathType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.cel.compiler.CelCompilerFactory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NormServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private NormRepository normRepo;

  @Mock private StructureService structureService;

  @Mock private ArchetypeService archetypeService;

  @Mock private NormApplicabilityValidationService applicabilityValidation;

  @Mock private NormAssertionValidationService assertionValidation;

  private NormService service;

  @BeforeEach
  void setUp() {
    service =
        new NormService(
            normRepo,
            structureService,
            archetypeService,
            applicabilityValidation,
            assertionValidation);
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

  static Stream<Arguments> applicabilityRefereeStatusMatrix() {
    Set<AscriptionStatusType> refereeStatuses =
        Set.of(
            AscriptionStatusType.APPROVED,
            AscriptionStatusType.ACTIVE,
            AscriptionStatusType.SUSPENDED,
            AscriptionStatusType.DEPRECATED,
            AscriptionStatusType.RETIRED);
    return Stream.of(AscriptionStatusTransitionPathType.values())
        .flatMap(path -> refereeStatuses.stream().map(status -> Arguments.of(path, status)));
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
        assertTrue(refs.stream().anyMatch(r -> r.getValue().equals("structure")));
        assertTrue(refs.stream().anyMatch(r -> r.getValue().equals("qualifier")));
      }

      @Test
      void referencesDistinctApplicabilityArchetypes() {
        String deploymentId = "gsmarc://tenant/DeploymentProperties/v1";
        String serviceId = "gsmarc://tenant/ServiceProperties/v2";
        ObjectNode statement = MAPPER.createObjectNode();
        statement.put("applicability", "expression");
        NormEntity entity = stubNorm(UUID.randomUUID(), UUID.randomUUID(), statement);
        ArchetypeEntity deployment = mock(ArchetypeEntity.class);
        ArchetypeEntity serviceArchetype = mock(ArchetypeEntity.class);
        when(applicabilityValidation.extractApplicabilityReferences("expression"))
            .thenReturn(List.of(deploymentId, serviceId));
        when(archetypeService.resolveArchetypeUri(deploymentId, "applicability ref()"))
            .thenReturn(deployment);
        when(archetypeService.resolveArchetypeUri(serviceId, "applicability ref()"))
            .thenReturn(serviceArchetype);

        var references = service.getRefereeReferences(entity);

        assertEquals(4, references.size());
        assertTrue(
            references.stream()
                .anyMatch(
                    reference ->
                        reference.getKey() == deployment
                            && reference
                                .getValue()
                                .equals("applicability ref(" + deploymentId + ")")));
        assertTrue(
            references.stream()
                .anyMatch(
                    reference ->
                        reference.getKey() == serviceArchetype
                            && reference
                                .getValue()
                                .equals("applicability ref(" + serviceId + ")")));
      }

      @ParameterizedTest(name = "{0} with applicability referee {1}")
      @MethodSource(
          "cloud.poesis.sie.defman.service.NormServiceTest#applicabilityRefereeStatusMatrix")
      void applicabilityArchetypeUsesLifecycleRefereeMatrix(
          AscriptionStatusTransitionPathType path, AscriptionStatusType refereeStatus) {
        String archetypeId = "gsmarc://tenant/DeploymentProperties/v1";
        ObjectNode statement = MAPPER.createObjectNode();
        statement.put(
            "applicability", "ref(\"" + archetypeId + "\").environment == \"production\"");
        NormEntity entity = stubNorm(UUID.randomUUID(), UUID.randomUUID(), statement);
        ArchetypeEntity applicabilityArchetype = mock(ArchetypeEntity.class);
        when(applicabilityArchetype.getId()).thenReturn(UUID.randomUUID());
        when(applicabilityArchetype.getStatus()).thenReturn(refereeStatus);
        when(archetypeService.resolveArchetypeUri(archetypeId, "applicability ref()"))
            .thenReturn(applicabilityArchetype);
        var realApplicabilityValidation =
            new NormApplicabilityValidationService(
                archetypeService,
                new ArchetypeCompositionValidationService(),
                CelCompilerFactory.standardCelCompilerBuilder().build());
        var normService =
            new NormService(
                normRepo,
                structureService,
                archetypeService,
                realApplicabilityValidation,
                assertionValidation);

        var applicabilityReference =
            normService.getRefereeReferences(entity).stream()
                .filter(reference -> reference.getValue().startsWith("applicability ref("))
                .toList();
        var stateMachine = new AscriptionStateMachineService();

        if (path.getRefereeAllowedStatuses().contains(refereeStatus)) {
          assertDoesNotThrow(
              () ->
                  stateMachine.validateRefereePreconditions(
                      applicabilityReference, path.getFrom(), path.getTo()));
        } else {
          assertThrows(
              RuleViolationException.class,
              () ->
                  stateMachine.validateRefereePreconditions(
                      applicabilityReference, path.getFrom(), path.getTo()));
        }
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
  // Create
  // ========================================================================

  @Nested
  class Create {

    @Test
    void validStatement_returnsEntity() {
      UUID structId = UUID.randomUUID();
      String qualifierId = "gsmarc://tenant/TestQual/v1";

      StructureEntity structure = mock(StructureEntity.class);
      when(structureService.findEntityById(structId)).thenReturn(structure);

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      ObjectNode qualSchema = MAPPER.createObjectNode();
      qualSchema.put("title", "TestQual");
      qualSchema
          .putObject("properties")
          .set("status", MAPPER.createObjectNode().put("type", "string"));
      when(qualifier.getStatement()).thenReturn(qualSchema);
      when(archetypeService.resolveArchetypeUri(qualifierId, "qualifier")).thenReturn(qualifier);

      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);

      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("structure", structId.toString());
      stmt.put("qualifier", qualifierId);
      stmt.put("assertion", "status == \"OK\"");
      stmt.put("toleranceMode", "INSTANTANEOUS");

      NormEntity result = service.create(def, archetype, stmt);
      assertEquals(def, result.getDefinition());
      assertEquals(structure, result.getStructure());
      assertEquals(qualifier, result.getQualifier());
    }

    @Test
    void withApplicability_validatesGuardReferencesAndPredicate() {
      UUID structId = UUID.randomUUID();
      String qualifierId = "gsmarc://tenant/TestQual/v1";

      StructureEntity structure = mock(StructureEntity.class);
      when(structureService.findEntityById(structId)).thenReturn(structure);

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      ObjectNode qualSchema = MAPPER.createObjectNode();
      qualSchema.put("title", "TestQual");
      qualSchema
          .putObject("properties")
          .set("status", MAPPER.createObjectNode().put("type", "string"));
      when(qualifier.getStatement()).thenReturn(qualSchema);
      when(archetypeService.resolveArchetypeUri(qualifierId, "qualifier")).thenReturn(qualifier);

      // Applicability references DeploymentProps — stub the archetype lookup
      ArchetypeEntity deployArch = mock(ArchetypeEntity.class);
      ObjectNode deploySchema = MAPPER.createObjectNode();
      deploySchema.put("title", "DeploymentProperties");
      deploySchema
          .putObject("properties")
          .set("environment", MAPPER.createObjectNode().put("type", "string"));
      when(deployArch.getStatement()).thenReturn(deploySchema);
      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("structure", structId.toString());
      stmt.put("qualifier", qualifierId);
      stmt.put("applicability", "DeploymentProperties.environment == \"production\"");
      stmt.put("assertion", "status == \"OK\"");
      stmt.put("toleranceMode", "INSTANTANEOUS");

      NormEntity result = service.create(def, archetype, stmt);
      assertEquals(def, result.getDefinition());
    }

    @Test
    void withApplicability_trueDefault_skipsGuardReferences() {
      UUID structId = UUID.randomUUID();
      String qualifierId = "gsmarc://tenant/TestQual/v1";

      StructureEntity structure = mock(StructureEntity.class);
      when(structureService.findEntityById(structId)).thenReturn(structure);

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      ObjectNode qualSchema = MAPPER.createObjectNode();
      qualSchema.put("title", "TestQual");
      qualSchema
          .putObject("properties")
          .set("status", MAPPER.createObjectNode().put("type", "string"));
      when(qualifier.getStatement()).thenReturn(qualSchema);
      when(archetypeService.resolveArchetypeUri(qualifierId, "qualifier")).thenReturn(qualifier);

      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("structure", structId.toString());
      stmt.put("qualifier", qualifierId);
      stmt.put("applicability", "true");
      stmt.put("assertion", "status == \"OK\"");
      stmt.put("toleranceMode", "INSTANTANEOUS");

      NormEntity result = service.create(def, archetype, stmt);
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
    void applicabilityArchetypeSource_hasNoCascadeTargetFinder() {
      var result =
          service.findCascadeTargetsFrom(DefinitionSubjectType.ARCHETYPE, UUID.randomUUID());
      assertTrue(result.isEmpty());
      verifyNoInteractions(normRepo);
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
}
