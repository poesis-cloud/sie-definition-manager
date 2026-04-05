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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.EnumSet;
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

  @Mock private ArchetypeRepository archetypeRepo;

  @Mock private ArchetypePropertyIndexationService indexProvisioning;

  @Mock private ArchetypeAnnotationValidationService annotationValidation;

  @Mock private ArchetypeCompositionValidationService compositionValidation;

  private ArchetypeService service;

  @BeforeEach
  void setUp() {
    service =
        new ArchetypeService(
            archetypeRepo, indexProvisioning, annotationValidation, compositionValidation);
    // Default: findInEffectByTitle returns empty for any title not explicitly
    // mocked.
    when(archetypeRepo.findInEffectByTitle(anyString())).thenReturn(Optional.empty());
  }

  // ========================================================================
  // Activation (title uniqueness, identity-bound)
  // ========================================================================

  @Nested
  class Activation {

    @Nested
    class ActivationUniqueness {

      @Test
      void uniqueTitle_valid() {
        UUID thisDefId = UUID.randomUUID();
        ArchetypeEntity entity = stubArchetype("SecurityProperties", thisDefId);

        when(archetypeRepo.findAllByStatusIn(
                EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
            .thenReturn(List.of());

        assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
      }

      @Test
      void duplicateTitle_differentDefinition_rejected() {
        UUID thisDefId = UUID.randomUUID();
        UUID otherDefId = UUID.randomUUID();

        ArchetypeEntity entity = stubArchetype("SecurityProperties", thisDefId);
        ArchetypeEntity existing = stubArchetype("SecurityProperties", otherDefId);

        when(archetypeRepo.findAllByStatusIn(
                EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
            .thenReturn(List.of(existing));

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class, () -> service.validateActivationUniqueness(entity));
        assertTrue(ex.getMessage().contains("SecurityProperties"));
        assertTrue(ex.getMessage().contains("already in"));
        assertEquals(
            AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
            ex.getRuleType());
      }

      @Test
      void sameTitle_sameDefinition_valid() {
        UUID defId = UUID.randomUUID();

        ArchetypeEntity entity = stubArchetype("SecurityProperties", defId);
        ArchetypeEntity existing = stubArchetype("SecurityProperties", defId);

        when(archetypeRepo.findAllByStatusIn(
                EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
            .thenReturn(List.of(existing));

        assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
      }

      @Test
      void differentTitle_valid() {
        UUID thisDefId = UUID.randomUUID();
        UUID otherDefId = UUID.randomUUID();

        ArchetypeEntity entity = stubArchetype("SecurityProperties", thisDefId);
        ArchetypeEntity existing = stubArchetype("PerformanceProperties", otherDefId);

        when(archetypeRepo.findAllByStatusIn(
                EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
            .thenReturn(List.of(existing));

        assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
      }
    }

    @Nested
    class IdentityBound {

      @Test
      void titleExtracted() {
        ArchetypeEntity entity = stubArchetype("SecurityProperties", UUID.randomUUID());
        var values = service.getIdentityBoundValues(entity);

        assertTrue(values.containsKey("title"));
        assertTrue(values.get("title").equals("SecurityProperties"));
      }

      @Test
      void noSchema_emptyMap() {
        ArchetypeEntity entity = stubArchetypeNoSchema(UUID.randomUUID());
        var values = service.getIdentityBoundValues(entity);

        assertTrue(values.isEmpty());
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
          ArchetypeParsingService.extractTitleFromRef("gsm://archetypes/SecurityProperties/v1"));
      assertEquals(
          "MyType", ArchetypeParsingService.extractTitleFromRef("gsm://archetypes/MyType/v42"));
    }

    @Test
    void invalidUri() {
      assertNull(ArchetypeParsingService.extractTitleFromRef("https://example.com/schema"));
      assertNull(ArchetypeParsingService.extractTitleFromRef("not-a-uri"));
      assertNull(ArchetypeParsingService.extractTitleFromRef("gsm://archetypes/NoVersion"));
    }
  }

  // ========================================================================
  // ResolveForCreation (subject type resolution via $ref chain)
  // ========================================================================

  @Nested
  class ResolveForCreation {

    @Test
    void baseArchetype_resolvesDirectly() {
      UUID id = UUID.randomUUID();
      ArchetypeEntity base = mockArchetype(schemaNode("StructureArchetype", false));
      when(base.getId()).thenReturn(id);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(base));

      var resolution = service.resolveForCreation(id);
      assertEquals(DefinitionSubjectType.STRUCTURE, resolution.subjectType());
    }

    @Test
    void resolveForCreation_tenantStructuralArchetype_walksRefChain() {
      UUID tenantId = UUID.randomUUID();
      ObjectNode tenantSchema = MAPPER.createObjectNode().put("title", "MyServiceArchetype");
      tenantSchema.put("$ref", "gsm://archetypes/StructureArchetype/v1");
      ArchetypeEntity tenant = mockArchetype(tenantSchema);
      when(tenant.getId()).thenReturn(tenantId);
      when(archetypeRepo.findById(tenantId)).thenReturn(Optional.of(tenant));
      when(compositionValidation.resolveGsmBases(
              eq("gsm://archetypes/StructureArchetype/v1"), eq("MyServiceArchetype"), any()))
          .thenReturn(Set.of("StructureArchetype"));

      var resolution = service.resolveForCreation(tenantId);
      assertEquals(DefinitionSubjectType.STRUCTURE, resolution.subjectType());
    }

    @Test
    void resolveForCreation_tenantViaIntermediary_walksRefChain() {
      UUID tenantId = UUID.randomUUID();
      ObjectNode tenantSchema = MAPPER.createObjectNode().put("title", "SpecificMechanism");
      tenantSchema.put("$ref", "gsm://archetypes/BaseMechanismTemplate/v1");
      ArchetypeEntity tenant = mockArchetype(tenantSchema);
      when(tenant.getId()).thenReturn(tenantId);
      when(archetypeRepo.findById(tenantId)).thenReturn(Optional.of(tenant));
      when(compositionValidation.resolveGsmBases(
              eq("gsm://archetypes/BaseMechanismTemplate/v1"), eq("SpecificMechanism"), any()))
          .thenReturn(Set.of("MechanismArchetype"));

      var resolution = service.resolveForCreation(tenantId);
      assertEquals(DefinitionSubjectType.MECHANISM, resolution.subjectType());
    }

    @Test
    void resolveForCreation_rootlessNoRef_rejected() {
      UUID id = UUID.randomUUID();
      ObjectNode rootless = MAPPER.createObjectNode().put("title", "SecurityProperties");
      ArchetypeEntity entity = mockArchetype(rootless);
      when(entity.getId()).thenReturn(id);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.resolveForCreation(id));
      assertTrue(ex.getMessage().contains("Rootless"));
      assertTrue(ex.getMessage().contains("archetype_id"));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          ex.getRuleType());
    }

    @Test
    void resolveForCreation_rootlessWithAllOfOnly_rejected() {
      ObjectNode baseFacet = schemaNode("BaseFacet", false);
      ArchetypeEntity baseFacetEntity = mockArchetype(baseFacet);
      when(archetypeRepo.findInEffectByTitle("BaseFacet")).thenReturn(Optional.of(baseFacetEntity));

      UUID id = UUID.randomUUID();
      ObjectNode rootless = MAPPER.createObjectNode().put("title", "DetailedFacet");
      rootless.putArray("allOf").addObject().put("$ref", "gsm://archetypes/BaseFacet/v1");
      ArchetypeEntity entity = mockArchetype(rootless);
      when(entity.getId()).thenReturn(id);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.resolveForCreation(id));
      assertTrue(ex.getMessage().contains("Rootless"));
      assertTrue(ex.getMessage().contains("archetype_id"));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          ex.getRuleType());
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

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.create(def, archetypeRef, null));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ex.getRuleType());
    }

    @Test
    void nonObjectStatement_rejected() {
      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.create(def, archetypeRef, MAPPER.createArrayNode()));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ex.getRuleType());
    }

    @Test
    void delegatesToAnnotationValidation_validateRefUriPolicy() {
      ObjectNode stmt = MAPPER.createObjectNode().put("title", "Archetype");
      DefinitionEntity def = mock(DefinitionEntity.class);
      UUID defId = UUID.randomUUID();
      when(def.getId()).thenReturn(defId);
      ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);
      when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

      service.create(def, archetypeRef, stmt);

      verify(annotationValidation).validateRefUriPolicy(stmt);
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
  // FindInEffectByTitle
  // ========================================================================

  @Nested
  class FindInEffectByTitleTests {

    @Test
    void found_returnsOptional() {
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(archetypeRepo.findInEffectByTitle("Test")).thenReturn(Optional.of(entity));

      assertTrue(service.findInEffectByTitle("Test").isPresent());
    }

    @Test
    void notFound_returnsEmpty() {
      when(archetypeRepo.findInEffectByTitle("X")).thenReturn(Optional.empty());

      assertTrue(service.findInEffectByTitle("X").isEmpty());
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
      UUID id = UUID.randomUUID();
      ArchetypeEntity entity = mockArchetype(MAPPER.createObjectNode());
      when(entity.getId()).thenReturn(id);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.resolveForCreation(id));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("no title"));
    }

    @Test
    void nullStatement_rejected() {
      UUID id = UUID.randomUUID();
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getId()).thenReturn(id);
      when(entity.getStatement()).thenReturn(null);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.resolveForCreation(id));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE,
          ex.getRuleType());
    }

    @Test
    void allBaseArchetypes_resolveCorrectly() {
      Map<String, DefinitionSubjectType> expected =
          Map.of(
              "Archetype", DefinitionSubjectType.ARCHETYPE,
              "StructureArchetype", DefinitionSubjectType.STRUCTURE,
              "MechanismArchetype", DefinitionSubjectType.MECHANISM,
              "EffectorArchetype", DefinitionSubjectType.EFFECTOR,
              "ReceptorArchetype", DefinitionSubjectType.RECEPTOR,
              "InteractionArchetype", DefinitionSubjectType.INTERACTION,
              "DirectiveArchetype", DefinitionSubjectType.DIRECTIVE,
              "NormArchetype", DefinitionSubjectType.NORM);

      for (var entry : expected.entrySet()) {
        UUID id = UUID.randomUUID();
        ArchetypeEntity entity =
            mockArchetype(MAPPER.createObjectNode().put("title", entry.getKey()));
        when(entity.getId()).thenReturn(id);
        when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

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
      ArchetypeEntity entity = archetypeWithBaseTitle("StructureArchetype");

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
      ArchetypeEntity entity = archetypeWithBaseTitle("StructureArchetype");

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

    private ArchetypeEntity archetypeWithBaseTitle(String title) {
      UUID defId = UUID.randomUUID();
      DefinitionEntity def = mock(DefinitionEntity.class);
      when(def.getId()).thenReturn(defId);

      ObjectNode stmt = MAPPER.createObjectNode().put("title", title);

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

    ObjectNode stmt = MAPPER.createObjectNode();
    stmt.put("title", title);

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
    ObjectNode schema = MAPPER.createObjectNode().put("title", title);
    if (sealed) {
      schema.put("$gsm:sealed", true);
    }
    return schema;
  }

  private static ArchetypeEntity mockArchetype(JsonNode schema) {
    ArchetypeEntity entity = mock(ArchetypeEntity.class);
    when(entity.getStatement()).thenReturn(schema);
    when(entity.getId()).thenReturn(UUID.randomUUID());
    return entity;
  }

  // ========================================================================
  // Descendant resolution API (getAncestorTitles / isDescendantOf)
  // ========================================================================

  @Nested
  class DescendantResolution {

    @Test
    void getAncestorTitles_rootlessArchetype_returnsOwnTitleOnly() {
      UUID id = UUID.randomUUID();
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "SecurityProperties");
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(schema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      Set<String> ancestors = service.getAncestorTitles(id);

      assertEquals(Set.of("SecurityProperties"), ancestors);
    }

    @Test
    void getAncestorTitles_singleRefToBase_returnsOwnPlusBase() {
      UUID id = UUID.randomUUID();
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "SecurityProperties");
      schema.put("$ref", "gsm://archetypes/StructureArchetype/v1");

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(schema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      Set<String> ancestors = service.getAncestorTitles(id);

      assertTrue(ancestors.contains("SecurityProperties"));
      assertTrue(ancestors.contains("StructureArchetype"));
      assertEquals(2, ancestors.size());
    }

    @Test
    void getAncestorTitles_chainThroughIntermediary_resolvesAll() {
      UUID id = UUID.randomUUID();
      // Child → ($ref) → SecurityProperties → ($ref) → StructureArchetype (base)
      ObjectNode childSchema = MAPPER.createObjectNode();
      childSchema.put("title", "DetailedSecurity");
      childSchema.put("$ref", "gsm://archetypes/SecurityProperties/v1");

      ArchetypeEntity childEntity = mock(ArchetypeEntity.class);
      when(childEntity.getStatement()).thenReturn(childSchema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(childEntity));

      // Intermediary schema in DB
      ObjectNode intermediarySchema = MAPPER.createObjectNode();
      intermediarySchema.put("title", "SecurityProperties");
      intermediarySchema.put("$ref", "gsm://archetypes/StructureArchetype/v1");

      ArchetypeEntity intermediaryEntity = mock(ArchetypeEntity.class);
      when(intermediaryEntity.getStatement()).thenReturn(intermediarySchema);
      when(intermediaryEntity.getStatus()).thenReturn(AscriptionStatusType.ACTIVE);

      when(archetypeRepo.findInEffectByTitle("SecurityProperties"))
          .thenReturn(Optional.of(intermediaryEntity));

      Set<String> ancestors = service.getAncestorTitles(id);

      assertTrue(ancestors.contains("DetailedSecurity"));
      assertTrue(ancestors.contains("SecurityProperties"));
      assertTrue(ancestors.contains("StructureArchetype"));
      assertEquals(3, ancestors.size());
    }

    @Test
    void isDescendantOf_exactMatch_returnsTrue() {
      UUID id = UUID.randomUUID();
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "SecurityProperties");
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(schema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      assertTrue(service.isDescendantOf(id, "SecurityProperties"));
    }

    @Test
    void isDescendantOf_viaRefChain_returnsTrue() {
      UUID id = UUID.randomUUID();
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "DetailedSecurity");
      schema.put("$ref", "gsm://archetypes/StructureArchetype/v1");

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(schema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      assertTrue(service.isDescendantOf(id, "StructureArchetype"));
    }

    @Test
    void getAncestorTitles_noTitle_returnsEmpty() {
      UUID id = UUID.randomUUID();
      ObjectNode schema = MAPPER.createObjectNode();
      // No title, no allOf
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(schema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      Set<String> ancestors = service.getAncestorTitles(id);
      assertTrue(ancestors.isEmpty());
    }

    @Test
    void getAncestorTitles_unresolvableIntermediary_skipsGracefully() {
      UUID id = UUID.randomUUID();
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "Child");
      schema.put("$ref", "gsm://archetypes/UnknownParent/v1");

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(schema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      // No in-effect archetypes in DB → intermediary not resolvable
      // Default findInEffectByTitle returns Optional.empty()

      Set<String> ancestors = service.getAncestorTitles(id);

      assertTrue(ancestors.contains("Child"));
      assertTrue(ancestors.contains("UnknownParent"));
      assertEquals(2, ancestors.size());
    }
  }

  // $ref URI policy validation is covered in
  // ArchetypeAnnotationValidationServiceTest.
}
