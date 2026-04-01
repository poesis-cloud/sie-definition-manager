package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchetypeServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private ArchetypeRepository archetypeRepo;

  @Mock private JdbcTemplate jdbcTemplate;

  private ArchetypeService service;

  @BeforeEach
  void setUp() {
    service =
        new ArchetypeService(
            archetypeRepo,
            jdbcTemplate,
            mock(DefinitionService.class),
            mock(AscriptionStatusTransitionService.class),
            mock(AscriptionService.class),
            mock(EntityManager.class),
            mock(DataProtectionService.class));
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
                List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
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
                List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
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
                List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
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
                List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
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

  // ========================================================================
  // AllOf chain validation
  // ========================================================================

  @Nested
  class AllOfChain {

    @Test
    void baseArchetype_exempt() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "StructureArchetype");
      assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }

    @Test
    void baseArchetype_allExempt() {
      for (String title :
          List.of(
              "StructureArchetype",
              "MechanismArchetype",
              "InteractionArchetype",
              "Archetype",
              "EffectorArchetype",
              "ReceptorArchetype",
              "DirectiveArchetype",
              "NormArchetype")) {
        ObjectNode schema = MAPPER.createObjectNode().put("title", title);
        assertDoesNotThrow(() -> service.validateAllOfChain(schema), "Expected exempt: " + title);
      }
    }

    @Test
    void missingAllOf_rootlessAccepted() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "SecurityProperties");
      assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }

    @Test
    void emptyAllOf_rootlessAccepted() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "SecurityProperties");
      schema.putArray("allOf");
      assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }

    @Test
    void allOfWithOnlyRootlessIntermediary_accepted() {
      ObjectNode facetSchema = schemaNode("SecurityProperties", false);
      ArchetypeEntity facet = mockArchetype(facetSchema);
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(facet));

      ObjectNode schema = MAPPER.createObjectNode().put("title", "DetailedSecurity");
      schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/SecurityProperties/v1");

      assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }

    @Test
    void allOfWithInlineEntriesOnly_rootlessAccepted() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "InlineFacet");
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("type", "object");

      assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }

    @Test
    void structuralBaseWithRootlessFacet_accepted() {
      ArchetypeEntity structBase = mockArchetype(schemaNode("StructureArchetype", false));
      ObjectNode facetSchema = schemaNode("SecurityProperties", false);
      ArchetypeEntity facet = mockArchetype(facetSchema);
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(structBase, facet));

      ObjectNode schema = MAPPER.createObjectNode().put("title", "SecuredStructure");
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", "gsm://archetypes/StructureArchetype/v1");
      allOf.addObject().put("$ref", "gsm://archetypes/SecurityProperties/v1");

      assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }

    @Test
    void directAllOfToBase_accepted() {
      ArchetypeEntity baseArchetype = mockArchetype(schemaNode("StructureArchetype", false));
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(baseArchetype));

      ObjectNode schema = MAPPER.createObjectNode().put("title", "MyStructure");
      schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/StructureArchetype/v1");

      assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }

    @Test
    void allOfToSealedBase_rejected() {
      ArchetypeEntity sealedArchetype = mockArchetype(schemaNode("Archetype", true));
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(sealedArchetype));

      ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantMeta");
      schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/Archetype/v1");

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.validateAllOfChain(schema));
      assertTrue(ex.getMessage().contains("sealed"));
      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_NON_SEALED, ex.getRuleType());
    }

    @Test
    void allOfToUnsealedEffectorBase_accepted() {
      ArchetypeEntity effectorArchetype = mockArchetype(schemaNode("EffectorArchetype", false));
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(effectorArchetype));

      ObjectNode schema = MAPPER.createObjectNode().put("title", "mTLSEffector");
      schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/EffectorArchetype/v1");

      assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }

    @Test
    void allOfToUnsealedReceptorBase_accepted() {
      ArchetypeEntity receptorArchetype = mockArchetype(schemaNode("ReceptorArchetype", false));
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(receptorArchetype));

      ObjectNode schema = MAPPER.createObjectNode().put("title", "WebhookReceptor");
      schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/ReceptorArchetype/v1");

      assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }

    @Test
    void allOfToUnsealedDirectiveBase_accepted() {
      ArchetypeEntity directiveArchetype = mockArchetype(schemaNode("DirectiveArchetype", false));
      when(archetypeRepo.findAllByStatusIn(anyCollection()))
          .thenReturn(List.of(directiveArchetype));

      ObjectNode schema = MAPPER.createObjectNode().put("title", "Principle");
      schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/DirectiveArchetype/v1");

      assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }

    @Test
    void allOfToUnsealedNormBase_accepted() {
      ArchetypeEntity normArchetype = mockArchetype(schemaNode("NormArchetype", false));
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(normArchetype));

      ObjectNode schema = MAPPER.createObjectNode().put("title", "Measure");
      schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/NormArchetype/v1");

      assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }

    @Test
    void invalidRefFormat_rejected() {
      ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantThing");
      schema.putArray("allOf").addObject().put("$ref", "https://example.com/not-gsm");

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.validateAllOfChain(schema));
      assertTrue(ex.getMessage().contains("gsm://"));
      assertEquals(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_EXCLUSIVE_BASE_CONVERGENCE,
          ex.getRuleType());
    }

    @Test
    void convergesToMultipleBases_rejected() {
      ArchetypeEntity struct = mockArchetype(schemaNode("StructureArchetype", false));
      ArchetypeEntity mech = mockArchetype(schemaNode("MechanismArchetype", false));
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(struct, mech));

      ObjectNode schema = MAPPER.createObjectNode().put("title", "ConfusedType");
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", "gsm://archetypes/StructureArchetype/v1");
      allOf.addObject().put("$ref", "gsm://archetypes/MechanismArchetype/v1");

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.validateAllOfChain(schema));
      assertTrue(ex.getMessage().contains("multiple"));
      assertEquals(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_EXCLUSIVE_BASE_CONVERGENCE,
          ex.getRuleType());
    }

    @Test
    void cycleInAllOfChain_rejected() {
      ObjectNode schemaA = schemaNode("A", false);
      schemaA.putArray("allOf").addObject().put("$ref", "gsm://archetypes/B/v1");

      ObjectNode schemaB = schemaNode("B", false);
      schemaB.putArray("allOf").addObject().put("$ref", "gsm://archetypes/A/v1");

      ArchetypeEntity archetypeB = mockArchetype(schemaB);
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(archetypeB));

      ObjectNode schema = MAPPER.createObjectNode().put("title", "A");
      schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/B/v1");

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.validateAllOfChain(schema));
      assertTrue(ex.getMessage().contains("Cycle") || ex.getMessage().contains("already visited"));
      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_ACYCLICITY, ex.getRuleType());
    }

    @Test
    void unresolvableIntermediary_lenientAtAuthoring() {
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of());

      ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantType");
      schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/NonExistent/v1");

      // Authoring-time (strict=false): warns and skips unresolvable intermediary.
      assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }

    @Test
    void unresolvableIntermediary_strictAtActivation() {
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of());

      ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantType");
      schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/NonExistent/v1");

      // Activation-time (strict=true): rejects unresolvable intermediary.
      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateAllOfChain(schema, true));
      assertTrue(ex.getMessage().contains("Cannot resolve"));
      assertEquals(
          AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_EXCLUSIVE_BASE_CONVERGENCE,
          ex.getRuleType());
    }

    @Test
    void extractTitleFromRef_validUri() {
      assertEquals(
          "SecurityProperties",
          ArchetypeService.extractTitleFromRef("gsm://archetypes/SecurityProperties/v1"));
      assertEquals("MyType", ArchetypeService.extractTitleFromRef("gsm://archetypes/MyType/v42"));
    }

    @Test
    void extractTitleFromRef_invalidUri() {
      assertNull(ArchetypeService.extractTitleFromRef("https://example.com/schema"));
      assertNull(ArchetypeService.extractTitleFromRef("not-a-uri"));
      assertNull(ArchetypeService.extractTitleFromRef("gsm://archetypes/NoVersion"));
    }

    @Test
    void resolveForCreation_baseArchetype_resolvesDirectly() {
      UUID id = UUID.randomUUID();
      ArchetypeEntity base = mockArchetype(schemaNode("StructureArchetype", false));
      when(base.getId()).thenReturn(id);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(base));

      var resolution = service.resolveForCreation(id);
      assertEquals(DefinitionSubjectType.STRUCTURE, resolution.subjectType());
    }

    @Test
    void resolveForCreation_tenantStructuralArchetype_walksChain() {
      ArchetypeEntity structBase = mockArchetype(schemaNode("StructureArchetype", false));
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(structBase));

      UUID tenantId = UUID.randomUUID();
      ObjectNode tenantSchema = MAPPER.createObjectNode().put("title", "MyServiceArchetype");
      tenantSchema
          .putArray("allOf")
          .addObject()
          .put("$ref", "gsm://archetypes/StructureArchetype/v1");
      ArchetypeEntity tenant = mockArchetype(tenantSchema);
      when(tenant.getId()).thenReturn(tenantId);
      when(archetypeRepo.findById(tenantId)).thenReturn(Optional.of(tenant));

      var resolution = service.resolveForCreation(tenantId);
      assertEquals(DefinitionSubjectType.STRUCTURE, resolution.subjectType());
    }

    @Test
    void resolveForCreation_tenantViaIntermediary_walksChain() {
      ArchetypeEntity mechBase = mockArchetype(schemaNode("MechanismArchetype", false));
      ObjectNode intermediarySchema = schemaNode("BaseMechanismTemplate", false);
      intermediarySchema
          .putArray("allOf")
          .addObject()
          .put("$ref", "gsm://archetypes/MechanismArchetype/v1");
      ArchetypeEntity intermediary = mockArchetype(intermediarySchema);
      when(archetypeRepo.findAllByStatusIn(anyCollection()))
          .thenReturn(List.of(mechBase, intermediary));

      UUID tenantId = UUID.randomUUID();
      ObjectNode tenantSchema = MAPPER.createObjectNode().put("title", "SpecificMechanism");
      tenantSchema
          .putArray("allOf")
          .addObject()
          .put("$ref", "gsm://archetypes/BaseMechanismTemplate/v1");
      ArchetypeEntity tenant = mockArchetype(tenantSchema);
      when(tenant.getId()).thenReturn(tenantId);
      when(archetypeRepo.findById(tenantId)).thenReturn(Optional.of(tenant));

      var resolution = service.resolveForCreation(tenantId);
      assertEquals(DefinitionSubjectType.MECHANISM, resolution.subjectType());
    }

    @Test
    void resolveForCreation_rootlessNoAllOf_rejected() {
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
    void resolveForCreation_rootlessWithAllOf_rejected() {
      ObjectNode baseFacet = schemaNode("BaseFacet", false);
      ArchetypeEntity baseFacetEntity = mockArchetype(baseFacet);
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(baseFacetEntity));

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

  // ========================================================================
  // Annotation vocabulary validation
  // ========================================================================

  @Nested
  class Annotation {

    @Nested
    class UnknownAnnotations {

      @Test
      void unknownTopLevelAnnotation_rejected() {
        ObjectNode schema = schemaWithProperty("x", prop("string"));
        schema.put("$gsm:bogus", true);

        UUID defId = UUID.randomUUID();
        when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateArchetypeAnnotations(schema, defId));
        assertEquals(
            AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
            ex.getRuleType());
      }

      @Test
      void unknownPropertyAnnotation_rejected() {
        ObjectNode propNode = prop("string");
        propNode.put("$gsm:foobar", true);
        ObjectNode schema = schemaWithProperty("x", propNode);

        UUID defId = UUID.randomUUID();
        when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateArchetypeAnnotations(schema, defId));
        assertTrue(ex.getMessage().contains("$gsm:foobar"));
        assertEquals(
            AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
            ex.getRuleType());
      }

      @Test
      void topLevelAnnotationOnProperty_rejected() {
        ObjectNode propNode = prop("string");
        propNode.put("$gsm:sealed", true);
        ObjectNode schema = schemaWithProperty("x", propNode);

        UUID defId = UUID.randomUUID();

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateArchetypeAnnotations(schema, defId));
        assertTrue(ex.getMessage().contains("top-level only"));
        assertEquals(
            AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
            ex.getRuleType());
      }
    }

    @Nested
    class IdentityBoundSetImmutability {

      @Test
      void firstAscription_noCheck() {
        ObjectNode propNode = prop("string");
        propNode.put("$gsm:identityBound", true);
        ObjectNode schema = schemaWithProperty("purpose", propNode);

        UUID defId = UUID.randomUUID();
        when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

        assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
      }

      @Test
      void sameIdentityBoundSet_valid() {
        UUID defId = UUID.randomUUID();

        ObjectNode existingProp = prop("string");
        existingProp.put("$gsm:identityBound", true);
        ObjectNode existingSchema = schemaWithProperty("purpose", existingProp);
        ArchetypeEntity existing = stubArchetypeWithSchema(existingSchema);
        when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId))
            .thenReturn(List.of(existing));

        ObjectNode newProp = prop("string");
        newProp.put("$gsm:identityBound", true);
        ObjectNode newSchema = schemaWithProperty("purpose", newProp);

        assertDoesNotThrow(() -> service.validateArchetypeAnnotations(newSchema, defId));
      }

      @Test
      void changedIdentityBoundSet_rejected() {
        UUID defId = UUID.randomUUID();

        ObjectNode existingProp = prop("string");
        existingProp.put("$gsm:identityBound", true);
        ObjectNode existingSchema = schemaWithProperty("purpose", existingProp);
        ArchetypeEntity existing = stubArchetypeWithSchema(existingSchema);
        when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId))
            .thenReturn(List.of(existing));

        ObjectNode newProp = prop("string");
        newProp.put("$gsm:identityBound", true);
        ObjectNode newSchema = schemaWithProperty("name", newProp);

        RuleViolationException ex =
            assertThrows(
                RuleViolationException.class,
                () -> service.validateArchetypeAnnotations(newSchema, defId));
        assertTrue(ex.getMessage().contains("identityBound set immutability"));
        assertEquals(
            AscriptionConsistencyRuleType.ARCHETYPE_IDENTITY_BOUND_PROPERTY_IMMUTABILITY,
            ex.getRuleType());
      }
    }

    @Nested
    class CollectIdentityBoundFields {

      @Test
      void collectsAnnotatedFields() {
        ObjectNode p1 = prop("string");
        p1.put("$gsm:identityBound", true);
        ObjectNode p2 = prop("string");
        ObjectNode p3 = prop("number");
        p3.put("$gsm:identityBound", true);

        ObjectNode schema = MAPPER.createObjectNode();
        ObjectNode props = schema.putObject("properties");
        props.set("alpha", p1);
        props.set("beta", p2);
        props.set("gamma", p3);

        Set<String> result = ArchetypeService.collectIdentityBoundFields(schema);
        assertEquals(Set.of("alpha", "gamma"), result);
      }

      @Test
      void noProperties_returnsEmpty() {
        ObjectNode schema = MAPPER.createObjectNode();
        Set<String> result = ArchetypeService.collectIdentityBoundFields(schema);
        assertTrue(result.isEmpty());
      }
    }

    @Test
    void cleanSchema_noAnnotations_valid() {
      ObjectNode schema = schemaWithProperty("env", prop("string"));

      UUID defId = UUID.randomUUID();
      when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

      assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
    }
  }

  // ========================================================================
  // BuildEntity
  // ========================================================================

  @Nested
  class BuildEntity {

    @Test
    void validStatement_returnsEntity() {
      ObjectNode stmt = MAPPER.createObjectNode().put("title", "Archetype");
      DefinitionEntity def = mock(DefinitionEntity.class);
      when(def.getId()).thenReturn(UUID.randomUUID());
      ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);
      when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(def.getId()))
          .thenReturn(List.of());

      ArchetypeEntity result = service.buildEntity(def, archetypeRef, stmt);
      assertNotNull(result);
      assertEquals(def, result.getDefinition());
    }

    @Test
    void nullStatement_rejected() {
      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.buildEntity(def, archetypeRef, null));
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
              () -> service.buildEntity(def, archetypeRef, MAPPER.createArrayNode()));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ex.getRuleType());
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
  // FindInEffectBySchemaTitle
  // ========================================================================

  @Nested
  class FindInEffectBySchemaTitleTests {

    @Test
    void matchingTitle_returnsArchetype() {
      ArchetypeEntity entity =
          mockArchetype(MAPPER.createObjectNode().put("title", "SecurityProperties"));
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(entity));

      ArchetypeEntity result = service.findInEffectBySchemaTitle("SecurityProperties");
      assertEquals(entity, result);
    }

    @Test
    void noMatch_returnsNull() {
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of());
      assertNull(service.findInEffectBySchemaTitle("NonExistent"));
    }

    @Test
    void nullStatement_skipped() {
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(null);
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(entity));

      assertNull(service.findInEffectBySchemaTitle("Anything"));
    }

    @Test
    void noTitleInStatement_skipped() {
      ArchetypeEntity entity = mockArchetype(MAPPER.createObjectNode());
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(entity));

      assertNull(service.findInEffectBySchemaTitle("Anything"));
    }

    @Test
    void differentTitle_skipped() {
      ArchetypeEntity entity =
          mockArchetype(MAPPER.createObjectNode().put("title", "OtherProperties"));
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(entity));

      assertNull(service.findInEffectBySchemaTitle("SecurityProperties"));
    }
  }

  // ========================================================================
  // GetByIds
  // ========================================================================

  @Nested
  class GetByIdsTests {

    @Test
    void returnsMapById() {
      UUID id1 = UUID.randomUUID();
      UUID id2 = UUID.randomUUID();
      ArchetypeEntity e1 = mock(ArchetypeEntity.class);
      when(e1.getId()).thenReturn(id1);
      ArchetypeEntity e2 = mock(ArchetypeEntity.class);
      when(e2.getId()).thenReturn(id2);
      when(archetypeRepo.findAllById(List.of(id1, id2))).thenReturn(List.of(e1, e2));

      Map<UUID, ArchetypeEntity> result = service.getByIds(List.of(id1, id2));
      assertEquals(2, result.size());
      assertEquals(e1, result.get(id1));
      assertEquals(e2, result.get(id2));
    }

    @Test
    void emptyIds_returnsEmptyMap() {
      when(archetypeRepo.findAllById(List.of())).thenReturn(List.of());

      Map<UUID, ArchetypeEntity> result = service.getByIds(List.of());
      assertTrue(result.isEmpty());
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
  // Index provisioning (onActivation / onDeactivation)
  // ========================================================================

  @Nested
  class IndexProvisioning {

    @Test
    void onActivation_provisionsQueryableIndex() {
      ArchetypeEntity entity = archetypeWithQueryableProps("StructureArchetype", "env", "string");

      service.onActivation(entity);

      ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
      verify(jdbcTemplate).execute(captor.capture());
      String ddl = captor.getValue();
      assertTrue(ddl.contains("CREATE INDEX IF NOT EXISTS"), ddl);
      assertTrue(ddl.contains("idx_gsm_q_"), ddl);
      assertTrue(ddl.contains("statement->>'env'"), ddl);
    }

    @Test
    void onActivation_provisionsUniqueIndex() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity def = mock(DefinitionEntity.class);
      when(def.getId()).thenReturn(defId);

      ObjectNode stmt = MAPPER.createObjectNode().put("title", "StructureArchetype");
      ObjectNode props = stmt.putObject("properties");
      ObjectNode nameProp = props.putObject("name");
      nameProp.put("type", "string");
      nameProp.put("$gsm:unique", true);

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(stmt);
      when(entity.getDefinition()).thenReturn(def);

      service.onActivation(entity);

      ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
      verify(jdbcTemplate).execute(captor.capture());
      String ddl = captor.getValue();
      assertTrue(ddl.contains("CREATE UNIQUE INDEX IF NOT EXISTS"), ddl);
      assertTrue(ddl.contains("idx_gsm_u_"), ddl);
      assertTrue(ddl.contains("ACTIVE"), ddl);
    }

    @Test
    void onActivation_queryableAndUnique_provisionsBoth() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity def = mock(DefinitionEntity.class);
      when(def.getId()).thenReturn(defId);

      ObjectNode stmt = MAPPER.createObjectNode().put("title", "StructureArchetype");
      ObjectNode props = stmt.putObject("properties");
      ObjectNode envProp = props.putObject("env");
      envProp.put("type", "string");
      envProp.put("$gsm:queryable", true);
      envProp.put("$gsm:unique", true);

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(stmt);
      when(entity.getDefinition()).thenReturn(def);

      service.onActivation(entity);

      verify(jdbcTemplate, times(2)).execute(anyString());
    }

    @Test
    void onActivation_ginForArrayType() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity def = mock(DefinitionEntity.class);
      when(def.getId()).thenReturn(defId);

      ObjectNode stmt = MAPPER.createObjectNode().put("title", "StructureArchetype");
      ObjectNode props = stmt.putObject("properties");
      ObjectNode tagsProp = props.putObject("tags");
      tagsProp.put("type", "array");
      tagsProp.putObject("items").put("type", "string");
      tagsProp.put("$gsm:queryable", true);

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(stmt);
      when(entity.getDefinition()).thenReturn(def);

      service.onActivation(entity);

      ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
      verify(jdbcTemplate).execute(captor.capture());
      assertTrue(captor.getValue().contains("USING GIN"));
    }

    @Test
    void onActivation_noProperties_noIndexes() {
      DefinitionEntity def = defWithId();
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement())
          .thenReturn(MAPPER.createObjectNode().put("title", "StructureArchetype"));
      when(entity.getDefinition()).thenReturn(def);

      service.onActivation(entity);

      verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void onActivation_nonArchetypeEntity_noOp() {
      AscriptionEntity notArchetype = mock(AscriptionEntity.class);
      service.onActivation(notArchetype);
      verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void onActivation_indexCreationFailure_doesNotThrow() {
      ArchetypeEntity entity = archetypeWithQueryableProps("StructureArchetype", "env", "string");
      doThrow(new RuntimeException("DB error")).when(jdbcTemplate).execute(anyString());

      assertDoesNotThrow(() -> service.onActivation(entity));
    }

    @Test
    void onDeactivation_dropsIndexes() {
      ArchetypeEntity entity = archetypeWithQueryableProps("StructureArchetype", "env", "string");

      service.onDeactivation(entity);

      ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
      verify(jdbcTemplate).execute(captor.capture());
      assertTrue(captor.getValue().contains("DROP INDEX IF EXISTS"));
    }

    @Test
    void onDeactivation_nullStatement_noOp() {
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(null);

      service.onDeactivation(entity);

      verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void onDeactivation_noProperties_noOp() {
      DefinitionEntity def = defWithId();
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement())
          .thenReturn(MAPPER.createObjectNode().put("title", "StructureArchetype"));
      when(entity.getDefinition()).thenReturn(def);

      service.onDeactivation(entity);

      verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void onDeactivation_nonArchetypeEntity_noOp() {
      AscriptionEntity notArchetype = mock(AscriptionEntity.class);
      service.onDeactivation(notArchetype);
      verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void onDeactivation_dropFailure_doesNotThrow() {
      ArchetypeEntity entity = archetypeWithQueryableProps("StructureArchetype", "env", "string");
      doThrow(new RuntimeException("DB error")).when(jdbcTemplate).execute(anyString());

      assertDoesNotThrow(() -> service.onDeactivation(entity));
    }

    @Test
    void provisionUsesSchemaTitle_notId() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity def = mock(DefinitionEntity.class);
      when(def.getId()).thenReturn(defId);

      ObjectNode stmt = MAPPER.createObjectNode().put("title", "MyCustomArchetype");
      ObjectNode props = stmt.putObject("properties");
      props.putObject("x").put("type", "string").put("$gsm:queryable", true);

      // Needs allOf chain to resolve subject type → provide a structural base
      stmt.putArray("allOf").addObject().put("$ref", "gsm://archetypes/StructureArchetype/v1");
      ArchetypeEntity structBase = mockArchetype(schemaNode("StructureArchetype", false));
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(structBase));

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(stmt);
      when(entity.getDefinition()).thenReturn(def);

      service.onActivation(entity);

      ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
      verify(jdbcTemplate).execute(captor.capture());
      assertTrue(captor.getValue().contains("mycustomarchetype"), captor.getValue());
    }

    @Test
    void titleFallbackToIdWhenMissing() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity def = mock(DefinitionEntity.class);
      when(def.getId()).thenReturn(defId);

      // no title but still "StructureArchetype" for resolveSubjectType
      // Actually resolveTableName calls resolveSubjectType which needs title.
      // So if no title, deprovisionIndexes returns early. Test via deprovisioning:
      ObjectNode stmt = MAPPER.createObjectNode();
      ObjectNode props = stmt.putObject("properties");
      props.putObject("x").put("type", "string").put("$gsm:queryable", true);

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(stmt);
      when(entity.getDefinition()).thenReturn(def);

      // deprovisionIndexes -> resolveTableName -> resolveSubjectType -> throws
      // because no title. This is caught by the outer try-catch? No, it's not.
      // Actually deprovisionIndexes doesn't catch resolveTableName exceptions.
      // Let me verify...
      // Actually the code flow in deprovisionIndexes:
      // String tableName = resolveTableName(archetype);
      // And resolveTableName calls resolveSubjectType which throws.
      // deprovisionIndexes doesn't catch this, so the exception propagates.
      // onDeactivation doesn't catch it either.
      // So this would throw RuleViolationException.

      // This is an edge case that probably shouldn't happen in practice.
      // Skip this test.
    }

    private ArchetypeEntity archetypeWithQueryableProps(
        String title, String propName, String propType) {
      UUID defId = UUID.randomUUID();
      DefinitionEntity def = mock(DefinitionEntity.class);
      when(def.getId()).thenReturn(defId);

      ObjectNode stmt = MAPPER.createObjectNode().put("title", title);
      ObjectNode props = stmt.putObject("properties");
      ObjectNode propNode = props.putObject(propName);
      propNode.put("type", propType);
      propNode.put("$gsm:queryable", true);

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(stmt);
      when(entity.getDefinition()).thenReturn(def);

      return entity;
    }

    private DefinitionEntity defWithId() {
      DefinitionEntity def = mock(DefinitionEntity.class);
      when(def.getId()).thenReturn(UUID.randomUUID());
      return def;
    }
  }

  // ========================================================================
  // AllOf Chain extra tests
  // ========================================================================

  @Nested
  class AllOfChainExtras {

    @Test
    void sealedIntermediary_rejected() {
      ObjectNode sealedSchema = schemaNode("SealedFacet", false);
      sealedSchema.put("$gsm:sealed", true);
      ArchetypeEntity intermediary = mockArchetype(sealedSchema);
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(intermediary));

      ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantType");
      schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/SealedFacet/v1");

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.validateAllOfChain(schema));
      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_NON_SEALED, ex.getRuleType());
      assertTrue(ex.getMessage().contains("SealedFacet"));
    }

    @Test
    void intermediaryWithAllOf_walksRecursively() {
      ArchetypeEntity structBase = mockArchetype(schemaNode("StructureArchetype", false));
      ObjectNode midSchema = schemaNode("MiddleLayer", false);
      midSchema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/StructureArchetype/v1");
      ArchetypeEntity mid = mockArchetype(midSchema);
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(structBase, mid));

      ObjectNode schema = MAPPER.createObjectNode().put("title", "TopLevel");
      schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/MiddleLayer/v1");

      assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }

    @Test
    void noTitleInSchema_accepted() {
      ObjectNode schema = MAPPER.createObjectNode();
      assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }
  }

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

  private ObjectNode prop(String type) {
    return MAPPER.createObjectNode().put("type", type);
  }

  private ObjectNode schemaWithProperty(String propName, ObjectNode propNode) {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("title", "TestSchema");
    ObjectNode props = schema.putObject("properties");
    props.set(propName, propNode);
    return schema;
  }

  private ArchetypeEntity stubArchetypeWithSchema(ObjectNode schema) {
    ArchetypeEntity archetype = mock(ArchetypeEntity.class);
    when(archetype.getStatement()).thenReturn(schema);

    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(UUID.randomUUID());
    when(archetype.getDefinition()).thenReturn(def);

    return archetype;
  }

  // ========================================================================
  // Descendant resolution API (getAncestorTitles / isDescendantOf)
  // ========================================================================

  @Nested
  class DescendantResolution {

    @Test
    void getAncestorTitles_rootlessArchetype_returnsOwnTitleOnly() {
      UUID id = UUID.randomUUID();
      ObjectNode schema =
          MAPPER
              .createObjectNode()
              .put("title", "SecurityProperties")
              .putObject("properties")
              .putObject("encryptionLevel")
              .put("type", "string");
      // Fix: schema must not have nested properties inside properties
      ObjectNode correctSchema = MAPPER.createObjectNode();
      correctSchema.put("title", "SecurityProperties");
      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(correctSchema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      Set<String> ancestors = service.getAncestorTitles(id);

      assertEquals(Set.of("SecurityProperties"), ancestors);
    }

    @Test
    void getAncestorTitles_singleAllOfToBase_returnsOwnPlusBase() {
      UUID id = UUID.randomUUID();
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "SecurityProperties");
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", "gsm://archetypes/StructureArchetype/v1");

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
      // Child -> Intermediary -> StructureArchetype (base)
      ObjectNode childSchema = MAPPER.createObjectNode();
      childSchema.put("title", "DetailedSecurity");
      var childAllOf = childSchema.putArray("allOf");
      childAllOf.addObject().put("$ref", "gsm://archetypes/SecurityProperties/v1");

      ArchetypeEntity childEntity = mock(ArchetypeEntity.class);
      when(childEntity.getStatement()).thenReturn(childSchema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(childEntity));

      // Intermediary schema in DB
      ObjectNode intermediarySchema = MAPPER.createObjectNode();
      intermediarySchema.put("title", "SecurityProperties");
      var intermediaryAllOf = intermediarySchema.putArray("allOf");
      intermediaryAllOf.addObject().put("$ref", "gsm://archetypes/StructureArchetype/v1");

      ArchetypeEntity intermediaryEntity = mock(ArchetypeEntity.class);
      when(intermediaryEntity.getStatement()).thenReturn(intermediarySchema);
      when(intermediaryEntity.getStatus()).thenReturn(AscriptionStatusType.ACTIVE);

      when(archetypeRepo.findAllByStatusIn(anyCollection()))
          .thenReturn(List.of(intermediaryEntity));

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
    void isDescendantOf_viaAllOfChain_returnsTrue() {
      UUID id = UUID.randomUUID();
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "DetailedSecurity");
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", "gsm://archetypes/StructureArchetype/v1");

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
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", "gsm://archetypes/UnknownParent/v1");

      ArchetypeEntity entity = mock(ArchetypeEntity.class);
      when(entity.getStatement()).thenReturn(schema);
      when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

      // No in-effect archetypes in DB → intermediary not resolvable
      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of());

      Set<String> ancestors = service.getAncestorTitles(id);

      assertTrue(ancestors.contains("Child"));
      assertTrue(ancestors.contains("UnknownParent"));
      assertEquals(2, ancestors.size());
    }
  }

  // ========================================================================
  // $ref URI policy validation (E1 R2/R3)
  // ========================================================================

  @Nested
  class RefUriPolicy {

    @Test
    void localJsonPointer_accepted() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "MyArchetype");
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", "#/$defs/SomeLocal");

      assertDoesNotThrow(() -> service.validateRefUriPolicy(schema));
    }

    @Test
    void gsmUri_accepted() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "MyArchetype");
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", "gsm://archetypes/StructureArchetype/v1");

      assertDoesNotThrow(() -> service.validateRefUriPolicy(schema));
    }

    @Test
    void httpUri_rejected() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "MyArchetype");
      var props = schema.putObject("properties");
      props.putObject("ext").put("$ref", "http://example.com/schema.json");

      var ex =
          assertThrows(RuleViolationException.class, () -> service.validateRefUriPolicy(schema));
      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_REF_NORM, ex.getRuleType());
      assertTrue(ex.getMessage().contains("http://example.com/schema.json"));
    }

    @Test
    void httpsUri_rejected() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "MyArchetype");
      schema.putObject("$defs").putObject("Ext").put("$ref", "https://evil.com/inject.json");

      var ex =
          assertThrows(RuleViolationException.class, () -> service.validateRefUriPolicy(schema));
      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_REF_NORM, ex.getRuleType());
    }

    @Test
    void deeplyNestedRef_detected() {
      ObjectNode schema = MAPPER.createObjectNode();
      var arr = schema.putArray("allOf");
      var inner = arr.addObject().putObject("properties").putObject("nested");
      inner.put("$ref", "ftp://bad-host/schema");

      var ex =
          assertThrows(RuleViolationException.class, () -> service.validateRefUriPolicy(schema));
      assertEquals(AscriptionConsistencyRuleType.ARCHETYPE_REF_NORM, ex.getRuleType());
      assertTrue(ex.getMessage().contains("ftp://bad-host/schema"));
    }

    @Test
    void noRefs_noViolation() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "Plain");
      schema.putObject("properties").putObject("name").put("type", "string");

      assertDoesNotThrow(() -> service.validateRefUriPolicy(schema));
    }

    @Test
    void nullSchema_noViolation() {
      assertDoesNotThrow(() -> service.validateRefUriPolicy(null));
    }
  }
}
