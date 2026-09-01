package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
class ArchetypeServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock
  private ArchetypeRepository archetypeRepo;

  @Mock
  private ArchetypePropertyIndexationService indexProvisioning;

  @Mock
  private ArchetypeIdentityValidationService identityValidation;

  @Mock
  private ArchetypeAnnotationValidationService annotationValidation;

  @Mock
  private ArchetypeCompositionValidationService compositionValidation;

  @Mock
  private ArchetypeSchemaResolverService schemaResolver;

  private ArchetypeService service;

  @BeforeEach
  void setUp() {
    service = new ArchetypeService(
        archetypeRepo,
        indexProvisioning,
        identityValidation,
        annotationValidation,
        compositionValidation,
        schemaResolver,
        new JsonSchemaPositionWalker());
    // Mirrors the production resolver's governed-store lookup so the repository stubs
    // in each test drive URI resolution as well.
    when(schemaResolver.resolveUri(anyString()))
        .thenAnswer(invocation -> archetypeRepo
            .findResolvableByUri(invocation.getArgument(0))
            .map(ArchetypeEntity::getStatement)
            .orElse(null));
  }

  // ========================================================================
  // Activation and identity-bound properties
  // ========================================================================

  @Nested
  class Activation {

    @Test
    void duplicateTitleAcrossDefinitions_isAcceptedWithoutLookup() {
      ArchetypeEntity entity = stubArchetype("SecurityProperties", UUID.randomUUID());

      assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));

      verifyNoInteractions(archetypeRepo);
    }

    @Nested
    class IdentityBound {

      @Test
      void titleExtracted() {
        ArchetypeEntity entity = stubArchetype("SecurityProperties", UUID.randomUUID());
        ((ObjectNode) entity.getStatement()).put("$id", "gsmarc://tenant/SecurityProperties/v1");
        var values = service.getIdentityBoundValues(entity);

        assertTrue(values.containsKey("title"));
        assertTrue(values.get("title").equals("SecurityProperties"));
      }

      @Test
      void identityStemExtractedWithoutVersion() {
        ArchetypeEntity entity = stubArchetype("SecurityProperties", UUID.randomUUID());
        ((ObjectNode) entity.getStatement())
            .put("$id", "gsmarc://tenant/security/SecurityProperties/v2");

        assertEquals(
            Map.of(
                "stem",
                "gsmarc://tenant/security/SecurityProperties",
                "title",
                "SecurityProperties"),
            service.getIdentityBoundValues(entity));
      }

      @Test
      void noSchema_emptyMap() {
        ArchetypeEntity entity = stubArchetypeNoSchema(UUID.randomUUID());
        var values = service.getIdentityBoundValues(entity);

        assertTrue(values.isEmpty());
      }
    }

    @Nested
    class CreationUniqueness {

      @Test
      void sameDefinitionOwner_passesAndFlushesPendingDefinition() {
        UUID definitionId = UUID.randomUUID();
        ArchetypeEntity entity = stubArchetype("SecurityProperties", definitionId);
        ((ObjectNode) entity.getStatement())
            .put("$id", "gsmarc://tenant/security/SecurityProperties/v1");
        when(archetypeRepo.acquireDefinitionIdByStem(
            "gsmarc://tenant/security/SecurityProperties", definitionId))
            .thenReturn(definitionId);

        assertDoesNotThrow(() -> service.validateCreationUniqueness(entity));

        verify(archetypeRepo).flush();
        verify(archetypeRepo)
            .acquireDefinitionIdByStem("gsmarc://tenant/security/SecurityProperties", definitionId);
      }

      @Test
      void otherDefinitionOwner_rejectedPermanently() {
        UUID definitionId = UUID.randomUUID();
        UUID ownerDefinitionId = UUID.randomUUID();
        ArchetypeEntity entity = stubArchetype("SecurityProperties", definitionId);
        ((ObjectNode) entity.getStatement())
            .put("$id", "gsmarc://tenant/security/SecurityProperties/v2");
        when(archetypeRepo.acquireDefinitionIdByStem(
            "gsmarc://tenant/security/SecurityProperties", definitionId))
            .thenReturn(ownerDefinitionId);

        RuleViolationException exception = assertThrows(
            RuleViolationException.class, () -> service.validateCreationUniqueness(entity));

        assertEquals(
            AscriptionConsistencyRuleType.ARCHETYPE_STEM_UNIQUENESS_ACROSS_DEFINITIONS,
            exception.getRuleType());
      }
    }

    @Nested
    class CandidateVersion {

      @Test
      void staleCandidateSuffix_rejected() {
        UUID definitionId = UUID.randomUUID();
        ArchetypeEntity candidate = stubArchetype("SecurityProperties", definitionId);
        ArchetypeEntity resolvable = stubArchetype("SecurityProperties", definitionId);
        when(resolvable.getVersion()).thenReturn(1);
        when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(definitionId))
            .thenReturn(List.of(candidate, resolvable));

        RuleViolationException exception = assertThrows(
            RuleViolationException.class,
            () -> service.validateStatusTransition(
                candidate, AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED));

        assertEquals(
            AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_ARCHETYPE_CANDIDATE_VERSION,
            exception.getRuleType());
      }

      @Test
      void nextCandidateSuffix_accepted() {
        UUID definitionId = UUID.randomUUID();
        ArchetypeEntity candidate = stubArchetype("SecurityProperties", definitionId);
        ((ObjectNode) candidate.getStatement()).put("$id", "gsmarc://gsm/SecurityProperties/v2");
        ArchetypeEntity resolvable = stubArchetype("SecurityProperties", definitionId);
        when(resolvable.getVersion()).thenReturn(1);
        when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(definitionId))
            .thenReturn(List.of(candidate, resolvable));

        assertDoesNotThrow(
            () -> service.validateStatusTransition(
                candidate, AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED));
      }

      @Test
      void materializedVersionMismatch_rejectedAfterApproval() {
        ArchetypeEntity approved = stubArchetype("SecurityProperties", UUID.randomUUID());
        ((ObjectNode) approved.getStatement()).put("$id", "gsmarc://gsm/SecurityProperties/v2");
        when(approved.getVersion()).thenReturn(1);

        RuleViolationException exception = assertThrows(
            RuleViolationException.class,
            () -> service.validatePersistedStatusTransition(
                approved, AscriptionStatusType.PROPOSED, AscriptionStatusType.APPROVED));

        assertEquals(
            AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_ARCHETYPE_VERSION_RECONCILIATION,
            exception.getRuleType());
      }

      @Test
      void materializedVersionMatchingSuffix_acceptedAfterApproval() {
        ArchetypeEntity approved = stubArchetype("SecurityProperties", UUID.randomUUID());
        ((ObjectNode) approved.getStatement()).put("$id", "gsmarc://gsm/SecurityProperties/v2");
        when(approved.getVersion()).thenReturn(2);

        assertDoesNotThrow(
            () -> service.validatePersistedStatusTransition(
                approved, AscriptionStatusType.PROPOSED, AscriptionStatusType.APPROVED));
      }
    }
  }

  // Schema composition validation is covered in
  // ArchetypeCompositionValidationServiceTest.

  // ========================================================================
  // ExtractTitleFromRef (static utility — now on ArchetypeParsingService)
  // ========================================================================

  @Nested
  class ExtractTitleFromRef {

    @Test
    void validUri() {
      assertEquals(
          "SecurityProperties",
          ArchetypeParsingService.extractTitleFromRef("gsmarc://gsm/SecurityProperties/v1"));
      assertEquals(
          "MyType", ArchetypeParsingService.extractTitleFromRef("gsmarc://gsm/MyType/v42"));
    }

    @Test
    void invalidUri() {
      assertNull(ArchetypeParsingService.extractTitleFromRef("https://example.com/schema"));
      assertNull(ArchetypeParsingService.extractTitleFromRef("not-a-uri"));
      assertNull(ArchetypeParsingService.extractTitleFromRef("gsmarc://gsm/NoVersion"));
    }
  }

  // ========================================================================
  // ResolveForCreation (subject type resolution via $ref chain)
  // ========================================================================

  @Nested
  class ResolveForCreation {

    @Test
    void resolvableUri_resolvesExactVersion() {
      String id = "gsmarc://gsm/Structure/v1";
      ArchetypeEntity base = mockArchetype(schemaNode("Structure", false));
      when(base.getStatus()).thenReturn(AscriptionStatusType.ACTIVE);
      when(archetypeRepo.findResolvableByUri(id)).thenReturn(Optional.of(base));

      var resolution = service.resolveForCreation(id);

      assertEquals(base, resolution.archetype());
      assertEquals(DefinitionSubjectType.STRUCTURE, resolution.subjectType());
    }

    @Test
    void deprecatedUri_remainsEligibleForTyping() {
      String id = "gsmarc://gsm/Structure/v1";
      ArchetypeEntity deprecated = mockArchetype(schemaNode("Structure", false));
      when(deprecated.getStatus()).thenReturn(AscriptionStatusType.DEPRECATED);
      when(archetypeRepo.findResolvableByUri(id)).thenReturn(Optional.of(deprecated));

      assertEquals(deprecated, service.resolveForCreation(id).archetype());
    }

    @Test
    void approvedUri_isNotYetEligibleForTyping() {
      String id = "gsmarc://tenant/StructuralType/v1";
      ArchetypeEntity approved = mockArchetype(schemaNode("Structure", false));
      when(approved.getStatus()).thenReturn(AscriptionStatusType.APPROVED);
      when(archetypeRepo.findResolvableByUri(id)).thenReturn(Optional.of(approved));

      RuleViolationException exception = assertThrows(RuleViolationException.class,
          () -> service.resolveForCreation(id));

      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_IN_EFFECT, exception.getRuleType());
    }

    @Test
    void candidateOrMissingId_isNotResolvableForCreation() {
      String id = "gsmarc://tenant/CandidateStructure/v1";
      when(archetypeRepo.findResolvableByUri(id)).thenReturn(Optional.empty());

      RuleViolationException exception = assertThrows(RuleViolationException.class,
          () -> service.resolveForCreation(id));

      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY, exception.getRuleType());
    }

    @Test
    void malformedArchetypeUri_reportsNormViolation() {
      RuleViolationException exception = assertThrows(
          RuleViolationException.class,
          () -> service.resolveArchetypeUri("not-an-archetype-id", "qualifier"));

      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_REF_NORM, exception.getRuleType());
      assertTrue(exception.getMessage().contains("qualifier"));
    }

    @Test
    void archetypeUri_defersLifecycleEligibilityToRefereeValidation() {
      String id = "gsmarc://tenant/SecurityProperties/v2";
      ArchetypeEntity suspended = mock(ArchetypeEntity.class);
      when(suspended.getStatus()).thenReturn(AscriptionStatusType.SUSPENDED);
      when(archetypeRepo.findResolvableByUri(id)).thenReturn(Optional.of(suspended));

      assertEquals(suspended, service.resolveArchetypeUri(id, "qualifier"));
    }

    @Test
    void refereeEligibility_acceptsApprovedAndActive() {
      ArchetypeEntity approved = mock(ArchetypeEntity.class);
      when(approved.getStatus()).thenReturn(AscriptionStatusType.APPROVED);
      ArchetypeEntity active = mock(ArchetypeEntity.class);
      when(active.getStatus()).thenReturn(AscriptionStatusType.ACTIVE);

      assertDoesNotThrow(
          () -> service.validateRefereeEligibility(approved, "mechanism rule reference"));
      assertDoesNotThrow(
          () -> service.validateRefereeEligibility(active, "mechanism rule reference"));
    }

    @Test
    void refereeEligibility_rejectsDeprecated() {
      ArchetypeEntity deprecated = mock(ArchetypeEntity.class);
      when(deprecated.getId()).thenReturn(UUID.randomUUID());
      when(deprecated.getStatus()).thenReturn(AscriptionStatusType.DEPRECATED);

      assertThrows(
          RuleViolationException.class,
          () -> service.validateRefereeEligibility(deprecated, "mechanism rule reference"));
    }
  }

  // ========================================================================
  // ResolveForQuery (typing-filter resolution — resolvable, not in-effect)
  // ========================================================================

  @Nested
  class ResolveForQuery {

    @Test
    void activeUri_resolvesExactVersion() {
      String id = "gsmarc://gsm/Structure/v1";
      ArchetypeEntity base = mockArchetype(schemaNode("Structure", false));
      when(base.getStatus()).thenReturn(AscriptionStatusType.ACTIVE);
      when(archetypeRepo.findResolvableByUri(id)).thenReturn(Optional.of(base));

      var resolution = service.resolveForQuery(id);

      assertEquals(base, resolution.archetype());
      assertEquals(DefinitionSubjectType.STRUCTURE, resolution.subjectType());
    }

    @Test
    void suspendedOrRetiredUri_remainsQueryable() {
      String id = "gsmarc://tenant/StructuralType/v1";
      ArchetypeEntity retired = mockArchetype(schemaNode("Structure", false));
      when(retired.getStatus()).thenReturn(AscriptionStatusType.RETIRED);
      when(archetypeRepo.findResolvableByUri(id)).thenReturn(Optional.of(retired));

      assertEquals(retired, service.resolveForQuery(id).archetype());
    }

    @Test
    void candidateOrMissingId_isNotResolvableForQuery() {
      String id = "gsmarc://tenant/CandidateStructure/v1";
      when(archetypeRepo.findResolvableByUri(id)).thenReturn(Optional.empty());

      RuleViolationException exception = assertThrows(RuleViolationException.class, () -> service.resolveForQuery(id));

      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY, exception.getRuleType());
    }
  }

  // Annotation validation is covered in
  // ArchetypeAnnotationValidationServiceTest.
  // Create delegation tests below verify that ArchetypeService calls
  // annotationValidation/compositionValidation.

  // ========================================================================
  // Create
  // ========================================================================

  @Nested
  class Create {

    @Test
    void validStatement_returnsEntity() {
      ObjectNode stmt = MAPPER.createObjectNode().put("title", "Archetype");
      DefinitionEntity def = mock(DefinitionEntity.class);
      when(def.getId()).thenReturn(UUID.randomUUID());
      ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);
      when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(def.getId()))
          .thenReturn(List.of());

      ArchetypeEntity result = service.create(def, archetypeRef, stmt);
      assertNotNull(result);
      assertEquals(def, result.getDefinition());
    }

    @Test
    void nullStatement_rejected() {
      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);

      RuleViolationException ex = assertThrows(RuleViolationException.class,
          () -> service.create(def, archetypeRef, null));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ex.getRuleType());
    }

    @Test
    void nonObjectStatement_rejected() {
      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);

      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> service.create(def, archetypeRef, MAPPER.createArrayNode()));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ex.getRuleType());
    }

    @Test
    void validatesIdentityAndRefPolicyBeforeComposition() {
      ObjectNode stmt = MAPPER.createObjectNode().put("title", "Archetype");
      DefinitionEntity def = mock(DefinitionEntity.class);
      UUID defId = UUID.randomUUID();
      when(def.getId()).thenReturn(defId);
      ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);
      when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

      service.create(def, archetypeRef, stmt);

      var validationOrder = inOrder(identityValidation, annotationValidation, compositionValidation);
      validationOrder.verify(identityValidation).validate(stmt);
      validationOrder.verify(annotationValidation).validateRefUriPolicy(stmt);
      validationOrder.verify(compositionValidation).validateSchemaComposition(eq(stmt), any());
    }

    @Test
    void identityFailure_skipsCompositionAndPersistence() {
      ObjectNode stmt = MAPPER.createObjectNode().put("title", "Archetype");
      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);
      RuleViolationException identityFailure = RuleViolationException.of(
          AscriptionConsistencyRuleType.ARCHETYPE_ID_GRAMMAR,
          "Missing identity",
          "field",
          "$id");
      doThrow(identityFailure).when(identityValidation).validate(stmt);

      RuleViolationException actual = assertThrows(RuleViolationException.class,
          () -> service.create(def, archetypeRef, stmt));

      assertEquals(identityFailure, actual);
      verify(compositionValidation, never()).validateSchemaComposition(eq(stmt), any());
      verify(annotationValidation, never()).validateRefUriPolicy(stmt);
      verify(archetypeRepo, never()).save(any());
    }

    @Test
    void delegatesToAnnotationValidation_validateArchetypeAnnotations() {
      ObjectNode stmt = MAPPER.createObjectNode().put("title", "Archetype");
      DefinitionEntity def = mock(DefinitionEntity.class);
      UUID defId = UUID.randomUUID();
      when(def.getId()).thenReturn(defId);
      ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);
      List<ArchetypeEntity> existing = List.of(mock(ArchetypeEntity.class));
      when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(existing);

      service.create(def, archetypeRef, stmt);

      verify(annotationValidation).validateArchetypeAnnotations(stmt, existing);
    }
  }

  // ========================================================================
  // FindEntityById
  // ========================================================================

  @Nested
  class FindEntityByIdTests {

    @Test
    void found_returnsEntity() {
      UUID id = UUID.randomUUID();
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      assertEquals(entity, service.findEntityById(id));
    }

    @Test
    void notFound_throwsResourceNotFound() {
      UUID id = UUID.randomUUID();
      when(archetypeRepo.findById(id)).thenReturn(Optional.empty());

      assertThrows(ResourceNotFoundException.class, () -> service.findEntityById(id));
    }
  }

  // ========================================================================
  // GetRepository / GetSubjectType
  // ========================================================================

  @Test
  void getSubjectType_returnsArchetype() {
    assertEquals(DefinitionSubjectType.ARCHETYPE, service.getSubjectType());
  }

  // ========================================================================
  // ResolveSubjectType extra branches
  // ========================================================================

  @Nested
  class ResolveSubjectTypeEdgeCases {

    @Test
    void noTitleInStatement_rejected() {
      String id = "gsmarc://tenant/MissingTitle/v1";
      ArchetypeEntity entity = mockArchetype(MAPPER.createObjectNode().put("$id", id));
      when(archetypeRepo.findResolvableByUri(id)).thenReturn(Optional.of(entity));

      RuleViolationException ex = assertThrows(RuleViolationException.class, () -> service.resolveForCreation(id));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("no title"));
    }

    @Test
    void nullStatement_rejected() {
      String id = "gsmarc://tenant/NullStatement/v1";
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(null);
      when(entity.getVersion()).thenReturn(1);
      when(entity.getStatus()).thenReturn(AscriptionStatusType.ACTIVE);
      when(archetypeRepo.findResolvableByUri(id)).thenReturn(Optional.of(entity));

      RuleViolationException ex = assertThrows(RuleViolationException.class, () -> service.resolveForCreation(id));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          ex.getRuleType());
    }

    @Test
    void allBaseArchetypes_resolveCorrectly() {
      Map<String, DefinitionSubjectType> expected = Map.of(
          "Archetype", DefinitionSubjectType.ARCHETYPE,
          "Structure", DefinitionSubjectType.STRUCTURE,
          "Mechanism", DefinitionSubjectType.MECHANISM,
          "Effector", DefinitionSubjectType.EFFECTOR,
          "Receptor", DefinitionSubjectType.RECEPTOR,
          "Interaction", DefinitionSubjectType.INTERACTION,
          "Directive", DefinitionSubjectType.DIRECTIVE,
          "Norm", DefinitionSubjectType.NORM);

      for (var entry : expected.entrySet()) {
        String id = "gsmarc://gsm/" + entry.getKey() + "/v1";
        ArchetypeEntity entity = mockArchetype(MAPPER.createObjectNode().put("$id", id).put("title", entry.getKey()));
        when(archetypeRepo.findResolvableByUri(id)).thenReturn(Optional.of(entity));

        var resolution = service.resolveForCreation(id);
        assertEquals(
            entry.getValue(), resolution.subjectType(), "Wrong subject type for " + entry.getKey());
      }
    }
  }

  // ========================================================================
  // Index provisioning delegation (onActivation / onDeactivation)
  // ========================================================================

  @Nested
  class IndexProvisioning {

    @Test
    @SuppressWarnings("unchecked")
    void onActivation_delegatesToIndexProvisioning() {
      ArchetypeEntity entity = archetypeWithBaseTitle("Structure");

      service.onActivation(entity);

      var captor = org.mockito.ArgumentCaptor.forClass(java.util.function.Supplier.class);
      verify(indexProvisioning).provisionIndexes(eq(entity), captor.capture());
      assertEquals("structure", captor.getValue().get());
    }

    @Test
    void onActivation_nonArchetypeEntity_noOp() {
      AscriptionEntity notArchetype = mock(AscriptionEntity.class);
      service.onActivation(notArchetype);
      verify(indexProvisioning, never()).provisionIndexes(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onDeactivation_delegatesToIndexProvisioning() {
      ArchetypeEntity entity = archetypeWithBaseTitle("Structure");

      service.onDeactivation(entity);

      var captor = org.mockito.ArgumentCaptor.forClass(java.util.function.Supplier.class);
      verify(indexProvisioning).deprovisionIndexes(eq(entity), captor.capture());
      assertEquals("structure", captor.getValue().get());
    }

    @Test
    void onDeactivation_nonArchetypeEntity_noOp() {
      AscriptionEntity notArchetype = mock(AscriptionEntity.class);
      service.onDeactivation(notArchetype);
      verify(indexProvisioning, never()).deprovisionIndexes(any(), any());
    }

    @Test
    void reconcileBaseArchetypeIndexes_provisionsEverySeededBase() {
      ArchetypeEntity structure = archetypeWithBaseTitle("Structure");
      when(archetypeRepo.findResolvableByUri(anyString())).thenReturn(Optional.empty());
      when(archetypeRepo.findResolvableByUri("gsmarc://gsm/Structure/v1"))
          .thenReturn(Optional.of(structure));

      assertEquals(1, service.reconcileBaseArchetypeIndexes());

      verify(indexProvisioning).provisionIndexes(eq(structure), any());
    }

    @Test
    void reconcileBaseArchetypeIndexes_ignoresBasesAbsentFromTheStore() {
      when(archetypeRepo.findResolvableByUri(anyString())).thenReturn(Optional.empty());

      assertEquals(0, service.reconcileBaseArchetypeIndexes());

      verify(indexProvisioning, never()).provisionIndexes(any(), any());
    }

    private ArchetypeEntity archetypeWithBaseTitle(String title) {
      UUID defId = UUID.randomUUID();
      DefinitionEntity def = mock(DefinitionEntity.class);
      when(def.getId()).thenReturn(defId);

      ObjectNode stmt = MAPPER.createObjectNode().put("$id", "gsmarc://gsm/" + title + "/v1").put("title", title);

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(stmt);
      when(entity.getDefinition()).thenReturn(def);

      return entity;
    }
  }

  // Schema composition validation is covered in
  // ArchetypeCompositionValidationServiceTest.

  // ========================================================================
  // IdentityBound getIdentityBoundValues extra branches
  // ========================================================================

  @Nested
  class IdentityBoundExtras {

    @Test
    void nullStatement_emptyMap() {
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(null);

      assertTrue(service.getIdentityBoundValues(entity).isEmpty());
    }

    @Test
    void noTitle_emptyMap() {
      ArchetypeEntity entity = mockArchetype(MAPPER.createObjectNode());

      assertTrue(service.getIdentityBoundValues(entity).isEmpty());
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private ArchetypeEntity stubArchetype(String title, UUID defId) {
    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(defId);

    ObjectNode stmt = MAPPER.createObjectNode().put("$id", "gsmarc://gsm/" + title + "/v1").put("title", title);

    ArchetypeEntity entity = mock(ArchetypeEntity.class);
    when(entity.getId()).thenReturn(UUID.randomUUID());
    when(entity.getDefinition()).thenReturn(def);
    when(entity.getStatement()).thenReturn(stmt);

    return entity;
  }

  private ArchetypeEntity stubArchetypeNoSchema(UUID defId) {
    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(defId);

    ObjectNode stmt = MAPPER.createObjectNode();

    ArchetypeEntity entity = mock(ArchetypeEntity.class);
    when(entity.getId()).thenReturn(UUID.randomUUID());
    when(entity.getDefinition()).thenReturn(def);
    when(entity.getStatement()).thenReturn(stmt);

    return entity;
  }

  private static ObjectNode schemaNode(String title, boolean sealed) {
    ObjectNode schema = MAPPER.createObjectNode().put("$id", "gsmarc://gsm/" + title + "/v1").put("title", title);
    if (sealed) {
      schema.put("$gsm:sealed", true);
    }
    return schema;
  }

  private static ArchetypeEntity mockArchetype(JsonNode schema) {
    ArchetypeEntity entity = mock(ArchetypeEntity.class);
    when(entity.getStatement()).thenReturn(schema);
    when(entity.getId()).thenReturn(UUID.randomUUID());
    when(entity.getVersion()).thenReturn(1);
    when(entity.getStatus()).thenReturn(AscriptionStatusType.ACTIVE);
    return entity;
  }

  // ========================================================================
  // Descendant resolution API (getAncestorTitles / isDescendantOf)
  // ========================================================================

  @Nested
  class DescendantResolution {

    @Test
    void getAncestorStems_preservesSameTitleAcrossAuthorities() {
      UUID id = UUID.randomUUID();
      ObjectNode schema = MAPPER
          .createObjectNode()
          .put("$id", "gsmarc://tenant/Composite/v1")
          .put("title", "Composite");
      schema.putArray("allOf").addObject().put("$ref", "gsmarc://authority-a/SharedFacet/v1");
      schema.withArray("allOf").addObject().put("$ref", "gsmarc://authority-b/SharedFacet/v1");
      ArchetypeEntity root = mockArchetype(schema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(root));
      ArchetypeEntity resolvedFacet = mockArchetype(MAPPER.createObjectNode());
      when(archetypeRepo.findResolvableByUri(anyString())).thenReturn(Optional.of(resolvedFacet));

      Set<String> ancestors = service.getAncestorStems(id);

      assertEquals(
          Set.of(
              "gsmarc://tenant/Composite",
              "gsmarc://authority-a/SharedFacet",
              "gsmarc://authority-b/SharedFacet"),
          ancestors);
    }

    @Test
    void getAncestorStems_rootlessArchetype_returnsOwnStemOnly() {
      UUID id = UUID.randomUUID();
      ObjectNode schema = MAPPER
          .createObjectNode()
          .put("$id", "gsmarc://tenant/SecurityProperties/v2")
          .put("title", "SecurityProperties");
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(schema);
      when(entity.getVersion()).thenReturn(1);
      when(entity.getStatus()).thenReturn(AscriptionStatusType.ACTIVE);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      Set<String> ancestors = service.getAncestorStems(id);

      assertEquals(Set.of("gsmarc://tenant/SecurityProperties"), ancestors);
    }

    @Test
    void getAncestorStems_singleRefToBase_returnsOwnPlusBase() {
      UUID id = UUID.randomUUID();
      ObjectNode schema = MAPPER
          .createObjectNode()
          .put("$id", "gsmarc://tenant/SecurityProperties/v1")
          .put("title", "SecurityProperties");
      schema.put("$ref", "gsmarc://gsm/Structure/v1");

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(schema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      Set<String> ancestors = service.getAncestorStems(id);

      assertTrue(ancestors.contains("gsmarc://tenant/SecurityProperties"));
      assertTrue(ancestors.contains("gsmarc://gsm/Structure"));
      assertEquals(2, ancestors.size());
    }

    @Test
    void getAncestorStems_chainThroughIntermediary_resolvesAll() {
      UUID id = UUID.randomUUID();
      // Child → ($ref) → SecurityProperties → ($ref) → Structure (base)
      ObjectNode childSchema = MAPPER
          .createObjectNode()
          .put("$id", "gsmarc://tenant/DetailedSecurity/v1")
          .put("title", "DetailedSecurity");
      childSchema.put("$ref", "gsmarc://gsm/SecurityProperties/v1");

      ArchetypeEntity childEntity = mock(ArchetypeEntity.class);
      when(childEntity.getStatement()).thenReturn(childSchema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(childEntity));

      // Intermediary schema in DB
      ObjectNode intermediarySchema = MAPPER
          .createObjectNode()
          .put("$id", "gsmarc://gsm/SecurityProperties/v1")
          .put("title", "SecurityProperties");
      intermediarySchema.put("$ref", "gsmarc://gsm/Structure/v1");

      ArchetypeEntity intermediaryEntity = mock(ArchetypeEntity.class);
      when(intermediaryEntity.getStatement()).thenReturn(intermediarySchema);
      when(intermediaryEntity.getStatus()).thenReturn(AscriptionStatusType.ACTIVE);

      when(archetypeRepo.findResolvableByUri("gsmarc://gsm/SecurityProperties/v1"))
          .thenReturn(Optional.of(intermediaryEntity));

      Set<String> ancestors = service.getAncestorStems(id);

      assertTrue(ancestors.contains("gsmarc://tenant/DetailedSecurity"));
      assertTrue(ancestors.contains("gsmarc://gsm/SecurityProperties"));
      assertTrue(ancestors.contains("gsmarc://gsm/Structure"));
      assertEquals(3, ancestors.size());
    }

    @Test
    void isDescendantOf_exactMatch_returnsTrue() {
      UUID id = UUID.randomUUID();
      ObjectNode schema = MAPPER
          .createObjectNode()
          .put("$id", "gsmarc://tenant/SecurityProperties/v3")
          .put("title", "SecurityProperties");
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(schema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      assertTrue(service.isDescendantOf(id, "gsmarc://tenant/SecurityProperties"));
    }

    @Test
    void isDescendantOf_viaRefChain_returnsTrue() {
      UUID id = UUID.randomUUID();
      ObjectNode schema = MAPPER
          .createObjectNode()
          .put("$id", "gsmarc://tenant/DetailedSecurity/v1")
          .put("title", "DetailedSecurity");
      schema.put("$ref", "gsmarc://gsm/Structure/v1");

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(schema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      assertTrue(service.isDescendantOf(id, "gsmarc://gsm/Structure"));
    }

    @Test
    void getAncestorStems_noIdentity_returnsEmpty() {
      UUID id = UUID.randomUUID();
      ObjectNode schema = MAPPER.createObjectNode();
      // No title, no allOf
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(schema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      Set<String> ancestors = service.getAncestorStems(id);
      assertTrue(ancestors.isEmpty());
    }

    @Test
    void getAncestorStems_unresolvableIntermediary_isNotAnAncestor() {
      UUID id = UUID.randomUUID();
      ObjectNode schema = MAPPER.createObjectNode().put("$id", "gsmarc://tenant/Child/v1").put("title", "Child");
      schema.put("$ref", "gsmarc://gsm/UnknownParent/v1");

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(schema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      Set<String> ancestors = service.getAncestorStems(id);

      assertEquals(Set.of("gsmarc://tenant/Child"), ancestors);
    }
  }

  // ========================================================================
  // Subject type resolution defensive branches (resolveForCreation)
  // ========================================================================

  @Nested
  class SubjectTypeResolutionDefensiveBranches {

    private String mockTenantArchetype(String title, String ref) {
      String id = "gsmarc://tenant/" + title + "/v1";
      ObjectNode schema = MAPPER.createObjectNode().put("$id", id).put("title", title).put("$ref", ref);
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(schema);
      when(entity.getVersion()).thenReturn(1);
      when(entity.getStatus()).thenReturn(AscriptionStatusType.ACTIVE);
      when(archetypeRepo.findResolvableByUri(id)).thenReturn(Optional.of(entity));
      return id;
    }

    @Test
    void resolveForCreation_tenantIdentityWithBaseTitle_usesReferencedBase() {
      String id = mockTenantArchetype("Structure", "gsmarc://tenant/BaseMechanism/v1");
      when(compositionValidation.resolveGsmBases(
          eq("gsmarc://tenant/BaseMechanism/v1"), eq("gsmarc://tenant/Structure/v1"), any()))
          .thenReturn(Set.of("gsmarc://gsm/Mechanism/v1"));

      ArchetypeService.ArchetypeResolution resolution = service.resolveForCreation(id);

      assertEquals(DefinitionSubjectType.MECHANISM, resolution.subjectType());
    }

    @Test
    void resolveForCreation_refChainConvergesToNoBase_throws() {
      String id = mockTenantArchetype("Tenant", "gsmarc://t/x/Parent/v1");
      when(compositionValidation.resolveGsmBases(
          eq("gsmarc://t/x/Parent/v1"), eq("gsmarc://tenant/Tenant/v1"), any()))
          .thenReturn(Set.of());

      RuleViolationException ex = assertThrows(RuleViolationException.class, () -> service.resolveForCreation(id));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          ex.getRuleType());
    }

    @Test
    void resolveForCreation_refChainConvergesToMultipleBases_throws() {
      String id = mockTenantArchetype("Tenant", "gsmarc://t/x/Parent/v1");
      when(compositionValidation.resolveGsmBases(
          eq("gsmarc://t/x/Parent/v1"), eq("gsmarc://tenant/Tenant/v1"), any()))
          .thenReturn(Set.of("gsmarc://gsm/Structure/v1", "gsmarc://gsm/Mechanism/v1"));

      RuleViolationException ex = assertThrows(RuleViolationException.class, () -> service.resolveForCreation(id));
      assertEquals(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_EXCLUSIVE_BASE_CONVERGENCE,
          ex.getRuleType());
    }

    @Test
    void resolveForCreation_unmappableBaseName_throws() {
      String id = mockTenantArchetype("Tenant", "gsmarc://t/x/Parent/v1");
      when(compositionValidation.resolveGsmBases(
          eq("gsmarc://t/x/Parent/v1"), eq("gsmarc://tenant/Tenant/v1"), any()))
          .thenReturn(Set.of("gsmarc://tenant/NotABase/v1"));

      RuleViolationException ex = assertThrows(RuleViolationException.class, () -> service.resolveForCreation(id));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          ex.getRuleType());
    }

    @Test
    void resolveForCreation_singleBase_resolvesSubjectType() {
      String id = mockTenantArchetype("Tenant", "gsmarc://t/x/Parent/v1");
      when(compositionValidation.resolveGsmBases(
          eq("gsmarc://t/x/Parent/v1"), eq("gsmarc://tenant/Tenant/v1"), any()))
          .thenReturn(Set.of("gsmarc://gsm/Structure/v1"));

      ArchetypeService.ArchetypeResolution resolution = service.resolveForCreation(id);

      assertEquals(DefinitionSubjectType.STRUCTURE, resolution.subjectType());
    }

    @Test
    void resolveArchetypeSchema_usesExactUri() {
      String archetypeUri = "gsmarc://tenant/layers/Parent/v3";
      ArchetypeEntity parent = mock(ArchetypeEntity.class);
      ObjectNode parentSchema = MAPPER.createObjectNode().put("$id", archetypeUri);
      when(parent.getStatement()).thenReturn(parentSchema);
      when(archetypeRepo.findResolvableByUri(archetypeUri)).thenReturn(Optional.of(parent));

      JsonNode resolved = service.resolveArchetypeSchema(archetypeUri);

      assertEquals(parentSchema, resolved);
      verify(archetypeRepo).findResolvableByUri(archetypeUri);
    }
  }

  // ========================================================================
  // Lifecycle descriptors and repository accessor
  // ========================================================================

  @Nested
  class LifecycleDescriptors {

    @Test
    void getRepository_returnsArchetypeRepository() {
      assertEquals(archetypeRepo, service.getRepository());
    }

    @Test
    void getRefereeReferences_discoversExternalSchemaRefsOnly() {
      String rootRef = "gsmarc://tenant/Base/v2";
      String nestedRef = "gsmarc://tenant/facets/AuditFacet/v1";
      ArchetypeEntity rootTarget = mock(ArchetypeEntity.class);
      ArchetypeEntity nestedTarget = mock(ArchetypeEntity.class);
      when(archetypeRepo.findResolvableByUri(rootRef)).thenReturn(Optional.of(rootTarget));
      when(archetypeRepo.findResolvableByUri(nestedRef)).thenReturn(Optional.of(nestedTarget));

      ObjectNode schema = MAPPER
          .createObjectNode()
          .put("$id", "gsmarc://tenant/Composite/v1")
          .put("title", "Composite")
          .put("$ref", rootRef);
      schema.putObject("properties").putObject("local").put("$ref", "#/$defs/Local");
      schema.withObject("properties").putObject("audit").put("$ref", nestedRef);
      schema.putObject("default").put("$ref", "gsmarc://tenant/DataLookalike/v1");

      ArchetypeEntity entity = mockArchetype(schema);

      List<Map.Entry<AscriptionEntity, String>> refs = service.getRefereeReferences(entity);

      assertEquals(
          List.of(
              Map.entry(rootTarget, "/$ref"), Map.entry(nestedTarget, "/properties/audit/$ref")),
          refs);
      verify(archetypeRepo, never()).findResolvableByUri("gsmarc://tenant/DataLookalike/v1");
    }

    @Test
    void getRefereeReferences_unresolvedRef_failsClosed() {
      String ref = "gsmarc://tenant/facets/MissingFacet/v1";
      ObjectNode schema = MAPPER
          .createObjectNode()
          .put("$id", "gsmarc://tenant/Composite/v1")
          .put("title", "Composite");
      schema.putObject("properties").putObject("facet").put("$ref", ref);
      when(archetypeRepo.findResolvableByUri(ref)).thenReturn(Optional.empty());

      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> service.getRefereeReferences(mockArchetype(schema)));

      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY, ex.getRuleType());
      assertEquals(ref, ex.getSite().get("ref"));
      assertEquals("/properties/facet/$ref", ex.getSite().get("site"));
    }

    @Test
    void getRefereeReferences_invalidEntityOrStatement_rejectedExplicitly() {
      assertThrows(IllegalArgumentException.class, () -> service.getRefereeReferences(null));
      assertThrows(
          IllegalArgumentException.class,
          () -> service.getRefereeReferences(mock(AscriptionEntity.class)));

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(null);
      assertThrows(IllegalArgumentException.class, () -> service.getRefereeReferences(archetype));
    }

    @Test
    void getRefereeReferences_preservesDuplicateTargetSitesAcrossWalkerKeywords() {
      String ref = "gsmarc://tenant/SharedFacet/v1";
      ArchetypeEntity target = mock(ArchetypeEntity.class);
      when(archetypeRepo.findResolvableByUri(ref)).thenReturn(Optional.of(target));

      ObjectNode schema = MAPPER
          .createObjectNode()
          .put("$id", "gsmarc://tenant/Composite/v1")
          .put("title", "Composite");
      schema.putObject("$defs").putObject("shared").put("$ref", ref);
      schema.putArray("prefixItems").addObject().put("$ref", ref);

      List<Map.Entry<AscriptionEntity, String>> refs = service.getRefereeReferences(mockArchetype(schema));

      assertEquals(
          List.of(
              Map.entry(target, "/prefixItems/0/$ref"), Map.entry(target, "/$defs/shared/$ref")),
          refs);
    }

    @Test
    void getCascadeTargetRoles_isEmpty() {
      assertTrue(service.getCascadeTargetRoles().isEmpty());
    }

    @Test
    void findCascadeTargetsFrom_isEmpty() {
      assertTrue(
          service
              .findCascadeTargetsFrom(DefinitionSubjectType.MECHANISM, UUID.randomUUID())
              .isEmpty());
    }
  }

  // ========================================================================
  // Ancestor collection — allOf facet walk branches
  // ========================================================================

  @Nested
  class AncestorAllOfWalk {

    @Test
    void getAncestorStems_allOfWalk_coversSkipDedupBaseAndRecursionBranches() {
      UUID id = UUID.randomUUID();
      ObjectNode schema = MAPPER
          .createObjectNode()
          .put("$id", "gsmarc://tenant/Composite/v1")
          .put("title", "Composite");
      var allOf = schema.putArray("allOf");
      allOf.add(MAPPER.createObjectNode()); // no $ref → skipped
      allOf.add(MAPPER.createObjectNode().put("$ref", "#/local/ptr")); // non-gsmarc → null title
      allOf.add(MAPPER.createObjectNode().put("$ref", "gsmarc://gsm/Structure/v1")); // GSM base
      allOf.add(MAPPER.createObjectNode().put("$ref", "gsmarc://gsm/Structure/v1")); // duplicate
      allOf.add(MAPPER.createObjectNode().put("$ref", "gsmarc://t/x/Unknown/v1")); // unresolvable
      allOf.add(MAPPER.createObjectNode().put("$ref", "gsmarc://t/x/Facet/v1")); // resolvable

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(schema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      // Facet resolves to an intermediary whose own $ref reaches Mechanism.
      ObjectNode facetSchema = MAPPER
          .createObjectNode()
          .put("$id", "gsmarc://t/x/Facet/v1")
          .put("title", "Facet")
          .put("$ref", "gsmarc://gsm/Mechanism/v1");
      ArchetypeEntity facetEntity = mock(ArchetypeEntity.class);
      when(facetEntity.getStatement()).thenReturn(facetSchema);
      when(archetypeRepo.findResolvableByUri("gsmarc://t/x/Facet/v1"))
          .thenReturn(Optional.of(facetEntity));

      Set<String> ancestors = service.getAncestorStems(id);

      assertEquals(
          Set.of(
              "gsmarc://tenant/Composite",
              "gsmarc://gsm/Structure",
              "gsmarc://t/x/Facet",
              "gsmarc://gsm/Mechanism"),
          ancestors);
    }

    @Test
    void getAncestorStems_collapsesVersionsOfSameFamily() {
      UUID id = UUID.randomUUID();
      ObjectNode schema = MAPPER
          .createObjectNode()
          .put("$id", "gsmarc://tenant/Composite/v1")
          .put("title", "Composite");
      schema.putArray("allOf").addObject().put("$ref", "gsmarc://tenant/Facet/v1");
      schema.withArray("allOf").addObject().put("$ref", "gsmarc://tenant/Facet/v2");
      ArchetypeEntity root = mockArchetype(schema);
      ArchetypeEntity resolved = mockArchetype(MAPPER.createObjectNode());
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(root));
      when(archetypeRepo.findResolvableByUri(anyString())).thenReturn(Optional.of(resolved));

      assertEquals(
          Set.of("gsmarc://tenant/Composite", "gsmarc://tenant/Facet"),
          service.getAncestorStems(id));
    }
  }

  // $ref URI policy validation is covered in
  // ArchetypeAnnotationValidationServiceTest.
}
