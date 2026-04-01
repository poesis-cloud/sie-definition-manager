package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.DirectiveEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.repository.DirectiveRepository;
import cloud.poesis.sie.defman.type.AppraisalRuleType;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectiveServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private DirectiveRepository directiveRepo;

  @Mock private StructureService structureService;

  @Mock private ArchetypeService archetypeService;

  private DirectiveService service;

  @BeforeEach
  void setUp() {
    service =
        new DirectiveService(
            directiveRepo,
            structureService,
            archetypeService,
            mock(ArchetypeRepository.class),
            mock(DefinitionService.class),
            mock(AscriptionStatusTransitionService.class),
            mock(AscriptionService.class),
            mock(EntityManager.class),
            mock(DataProtectionService.class),
            null);
    AppraisalService appraisalService =
        new AppraisalService(service, mock(NormService.class), archetypeService);
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "appraisalService", appraisalService);
  }

  // ========================================================================
  // Consistency (verb/modal contradiction)
  // ========================================================================

  @Nested
  class Consistency {

    @Test
    void noSiblings_noConflict() {
      DirectiveEntity directive = stubDirective("ENSURE", "MUST");
      when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
              any(), any(), any()))
          .thenReturn(List.of());

      assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
    }

    @Test
    void differentNonContradictoryVerbs_noConflict() {
      DirectiveEntity directive = stubDirective("ENSURE", "MUST");
      DirectiveEntity sibling = stubDirective("MAXIMIZE", "SHOULD");

      when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
              any(), any(), any()))
          .thenReturn(List.of(sibling));

      assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
    }

    @Test
    void sameDefinition_skipped() {
      UUID sharedDefId = UUID.randomUUID();
      DirectiveEntity directive = stubDirectiveWithDefId("ENSURE", "MUST", sharedDefId);
      DirectiveEntity sibling = stubDirectiveWithDefId("PREVENT", "MUST", sharedDefId);

      when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
              any(), any(), any()))
          .thenReturn(List.of(sibling));

      assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
    }

    @Nested
    class VerbContradiction {

      @Test
      void ensureAndPrevent_contradiction() {
        DirectiveEntity directive = stubDirective("ENSURE", "MUST");
        DirectiveEntity sibling = stubDirective("PREVENT", "MUST");

        when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                any(), any(), any()))
            .thenReturn(List.of(sibling));

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateActivationUniqueness(directive));
        assertEquals(AppraisalRuleType.DIRECTIVE_COMPATIBILITY_ON_VERB, ex.getRuleType());
        assertTrue(ex.getMessage().contains("contradiction"));
        assertTrue(ex.getMessage().contains("ENSURE"));
        assertTrue(ex.getMessage().contains("PREVENT"));
      }

      @Test
      void preventAndEnsure_contradiction() {
        DirectiveEntity directive = stubDirective("PREVENT", "SHOULD");
        DirectiveEntity sibling = stubDirective("ENSURE", "MAY");

        when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                any(), any(), any()))
            .thenReturn(List.of(sibling));

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateActivationUniqueness(directive));
        assertEquals(AppraisalRuleType.DIRECTIVE_COMPATIBILITY_ON_VERB, ex.getRuleType());
        assertTrue(ex.getMessage().contains("contradiction"));
      }
    }

    @Nested
    class ModalContradiction {

      @Test
      void mustAndMustNot_contradiction() {
        DirectiveEntity directive = stubDirective("ENSURE", "MUST");
        DirectiveEntity sibling = stubDirective("ENSURE", "MUST_NOT");

        when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                any(), any(), any()))
            .thenReturn(List.of(sibling));

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateActivationUniqueness(directive));
        assertEquals(AppraisalRuleType.DIRECTIVE_COMPATIBILITY_ON_MODAL, ex.getRuleType());
        assertTrue(ex.getMessage().contains("modal contradiction"));
      }

      @Test
      void shouldAndShouldNot_contradiction() {
        DirectiveEntity directive = stubDirective("MAXIMIZE", "SHOULD");
        DirectiveEntity sibling = stubDirective("MAXIMIZE", "SHOULD_NOT");

        when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                any(), any(), any()))
            .thenReturn(List.of(sibling));

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateActivationUniqueness(directive));
        assertEquals(AppraisalRuleType.DIRECTIVE_COMPATIBILITY_ON_MODAL, ex.getRuleType());
        assertTrue(ex.getMessage().contains("modal contradiction"));
      }

      @Test
      void mustNotAndMust_contradiction() {
        DirectiveEntity directive = stubDirective("MAINTAIN", "MUST_NOT");
        DirectiveEntity sibling = stubDirective("MAINTAIN", "MUST");

        when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                any(), any(), any()))
            .thenReturn(List.of(sibling));

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateActivationUniqueness(directive));
        assertEquals(AppraisalRuleType.DIRECTIVE_COMPATIBILITY_ON_MODAL, ex.getRuleType());
      }

      @Test
      void shouldNotAndShould_contradiction() {
        DirectiveEntity directive = stubDirective("MAXIMIZE", "SHOULD_NOT");
        DirectiveEntity sibling = stubDirective("MAXIMIZE", "SHOULD");

        when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                any(), any(), any()))
            .thenReturn(List.of(sibling));

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateActivationUniqueness(directive));
        assertEquals(AppraisalRuleType.DIRECTIVE_COMPATIBILITY_ON_MODAL, ex.getRuleType());
        assertTrue(ex.getMessage().contains("modal contradiction"));
      }

      @Test
      void sameModalSameVerb_noConflict() {
        DirectiveEntity directive = stubDirective("ENSURE", "MUST");
        DirectiveEntity sibling = stubDirective("ENSURE", "MUST");

        when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                any(), any(), any()))
            .thenReturn(List.of(sibling));

        assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
      }
    }

    @Nested
    class ModalPrecedence {

      @Test
      void mustOverridesShould_noException() {
        DirectiveEntity directive = stubDirective("ENSURE", "MUST");
        DirectiveEntity sibling = stubDirective("ENSURE", "SHOULD");

        when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                any(), any(), any()))
            .thenReturn(List.of(sibling));

        assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
      }

      @Test
      void shouldOverridesMay_noException() {
        DirectiveEntity directive = stubDirective("MAXIMIZE", "SHOULD");
        DirectiveEntity sibling = stubDirective("MAXIMIZE", "MAY");

        when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                any(), any(), any()))
            .thenReturn(List.of(sibling));

        assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
      }

      @Test
      void mustNotOverridesShouldNot_noException() {
        DirectiveEntity directive = stubDirective("MAINTAIN", "MUST_NOT");
        DirectiveEntity sibling = stubDirective("MAINTAIN", "SHOULD_NOT");

        when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                any(), any(), any()))
            .thenReturn(List.of(sibling));

        assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
      }

      @Test
      void sameTierDifferentModal_noException() {
        DirectiveEntity directive = stubDirective("OPTIMIZE", "MAY");
        DirectiveEntity sibling = stubDirective("OPTIMIZE", "MAY");

        when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                any(), any(), any()))
            .thenReturn(List.of(sibling));

        assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
      }

      @Test
      void differentVerbsDifferentTiers_noConflict() {
        DirectiveEntity directive = stubDirective("ENSURE", "MUST");
        DirectiveEntity sibling = stubDirective("OPTIMIZE", "MAY");

        when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                any(), any(), any()))
            .thenReturn(List.of(sibling));

        assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
      }

      @Test
      void lowerPrecedenceOverriddenByHigher_noException() {
        DirectiveEntity directive = stubDirective("ENSURE", "MAY");
        DirectiveEntity sibling = stubDirective("ENSURE", "MUST");

        when(directiveRepo.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
                any(), any(), any()))
            .thenReturn(List.of(sibling));

        assertDoesNotThrow(() -> service.validateActivationUniqueness(directive));
      }
    }
  }

  // ========================================================================
  // Lifecycle (identity-bound, referee, cascade)
  // ========================================================================

  @Nested
  class Lifecycle {

    @Nested
    class IdentityBound {

      @Test
      void structureQualifierPurposeExtracted() {
        UUID structDefId = UUID.randomUUID();
        UUID qualDefId = UUID.randomUUID();
        UUID purposeDefId = UUID.randomUUID();

        DirectiveEntity entity = stubDirectiveLifecycle(structDefId, qualDefId, purposeDefId);

        var values = service.getIdentityBoundValues(entity);

        assertEquals(structDefId, values.get("structure"));
        assertEquals(qualDefId, values.get("qualifier"));
        assertEquals(purposeDefId, values.get("purpose"));
      }
    }

    @Nested
    class RefereeReferences {

      @Test
      void referencesStructureQualifierPurpose() {
        UUID structDefId = UUID.randomUUID();
        UUID qualDefId = UUID.randomUUID();
        UUID purposeDefId = UUID.randomUUID();

        DirectiveEntity entity = stubDirectiveLifecycle(structDefId, qualDefId, purposeDefId);

        var refs = service.getRefereeReferences(entity);

        assertEquals(3, refs.size());
        assertTrue(refs.stream().anyMatch(r -> r.label().equals("structure")));
        assertTrue(refs.stream().anyMatch(r -> r.label().equals("qualifier")));
        assertTrue(refs.stream().anyMatch(r -> r.label().equals("purpose")));
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
  // Statement Compliance
  // ========================================================================

  @Nested
  class StatementCompliance {

    @Test
    void missingRequiredField_rejected() {
      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode emptyStatement = MAPPER.createObjectNode();

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.buildEntity(def, archetype, emptyStatement));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("structure"));
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private DirectiveEntity stubDirective(String verb, String modal) {
    return stubDirectiveWithDefId(verb, modal, UUID.randomUUID());
  }

  private DirectiveEntity stubDirectiveWithDefId(String verb, String modal, UUID definitionId) {
    UUID qualifierDefId = UUID.randomUUID();
    UUID purposeDefId = UUID.randomUUID();

    DefinitionEntity defEntity = mock(DefinitionEntity.class);
    when(defEntity.getId()).thenReturn(definitionId);

    DefinitionEntity qualifierDef = mock(DefinitionEntity.class);
    when(qualifierDef.getId()).thenReturn(qualifierDefId);
    ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
    when(qualifier.getDefinition()).thenReturn(qualifierDef);

    DefinitionEntity purposeDef = mock(DefinitionEntity.class);
    when(purposeDef.getId()).thenReturn(purposeDefId);
    StructureEntity purpose = mock(StructureEntity.class);
    when(purpose.getDefinition()).thenReturn(purposeDef);

    ObjectNode stmt = MAPPER.createObjectNode().put("verb", verb).put("modal", modal);

    DirectiveEntity directive = mock(DirectiveEntity.class);
    when(directive.getDefinition()).thenReturn(defEntity);
    when(directive.getQualifier()).thenReturn(qualifier);
    when(directive.getPurpose()).thenReturn(purpose);
    when(directive.getStatement()).thenReturn(stmt);
    when(directive.getId()).thenReturn(UUID.randomUUID());

    return directive;
  }

  private DirectiveEntity stubDirectiveLifecycle(
      UUID structDefId, UUID qualDefId, UUID purposeDefId) {
    StructureEntity structure = mock(StructureEntity.class);
    DefinitionEntity structDef = mock(DefinitionEntity.class);
    when(structDef.getId()).thenReturn(structDefId);
    when(structure.getDefinition()).thenReturn(structDef);

    ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
    DefinitionEntity qualDef = mock(DefinitionEntity.class);
    when(qualDef.getId()).thenReturn(qualDefId);
    when(qualifier.getDefinition()).thenReturn(qualDef);

    StructureEntity purpose = mock(StructureEntity.class);
    DefinitionEntity purposeDef = mock(DefinitionEntity.class);
    when(purposeDef.getId()).thenReturn(purposeDefId);
    when(purpose.getDefinition()).thenReturn(purposeDef);

    DirectiveEntity entity = mock(DirectiveEntity.class);
    when(entity.getStructure()).thenReturn(structure);
    when(entity.getQualifier()).thenReturn(qualifier);
    when(entity.getPurpose()).thenReturn(purpose);

    return entity;
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
      UUID purposeId = UUID.randomUUID();

      StructureEntity structure = mock(StructureEntity.class);
      when(structureService.findEntityById(structId)).thenReturn(structure);

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(archetypeService.findEntityById(qualId)).thenReturn(qualifier);

      StructureEntity purpose = mock(StructureEntity.class);
      when(structureService.findEntityById(purposeId)).thenReturn(purpose);

      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);

      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("structure", structId.toString());
      stmt.put("qualifier", qualId.toString());
      stmt.put("purpose", purposeId.toString());
      stmt.put("modal", "MUST");
      stmt.put("verb", "ENSURE");

      DirectiveEntity result = service.buildEntity(def, archetype, stmt);
      assertEquals(def, result.getDefinition());
      assertEquals(structure, result.getStructure());
      assertEquals(qualifier, result.getQualifier());
      assertEquals(purpose, result.getPurpose());
    }

    @Test
    void missingStructure_rejected() {
      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("qualifier", UUID.randomUUID().toString());
      stmt.put("purpose", UUID.randomUUID().toString());

      assertThrows(RuleViolationException.class, () -> service.buildEntity(def, archetype, stmt));
    }

    @Test
    void structureNotFound_rejected() {
      UUID structId = UUID.randomUUID();
      when(structureService.findEntityById(structId))
          .thenThrow(new ResourceNotFoundException(PrimitiveType.STRUCTURE, structId));

      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("structure", structId.toString());
      stmt.put("qualifier", UUID.randomUUID().toString());
      stmt.put("purpose", UUID.randomUUID().toString());

      assertThrows(
          ResourceNotFoundException.class, () -> service.buildEntity(def, archetype, stmt));
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
      DirectiveEntity d1 = mock(DirectiveEntity.class);
      when(directiveRepo.findAllByStructureId(sourceId)).thenReturn(List.of(d1));

      var result = service.findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, sourceId);
      assertEquals(1, result.size());
    }

    @Test
    void nonStructureSource_returnsEmpty() {
      var result =
          service.findCascadeTargetsFrom(DefinitionSubjectType.DIRECTIVE, UUID.randomUUID());
      assertTrue(result.isEmpty());
    }
  }

  // ========================================================================
  // GetSubjectType
  // ========================================================================

  @Test
  void getSubjectType_returnsDirective() {
    assertEquals(DefinitionSubjectType.DIRECTIVE, service.getSubjectType());
  }
}
