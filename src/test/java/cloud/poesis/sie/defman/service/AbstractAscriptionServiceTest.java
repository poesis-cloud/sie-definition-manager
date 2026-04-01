package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.InternalException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.service.AbstractAscriptionService.RefereeReference;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import jakarta.persistence.EntityManager;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AbstractAscriptionServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock
  private DefinitionService definitionService;

  @Mock
  private AscriptionService ascriptionService;

  @Mock
  private AscriptionStatusTransitionService transitionService;

  @Mock
  private ArchetypeRepository archetypeRepo;

  @Mock
  private EntityManager entityManager;

  /** Minimal concrete subclass for testing package-private base methods. */
  private AbstractAscriptionService<AscriptionEntity> service;

  // Configurable responses for abstract methods
  private List<AscriptionEntity> existingAscriptions = List.of();
  private Map<String, Object> identityBoundValues = Map.of();
  private List<RefereeReference> refereeReferences = List.of();

  @BeforeEach
  void setUp() {
    service = new AbstractAscriptionService<>(
        definitionService,
        transitionService,
        ascriptionService,
        archetypeRepo,
        entityManager,
        new DataProtectionService()) {
      @Override
      public DefinitionSubjectType getSubjectType() {
        return DefinitionSubjectType.STRUCTURE;
      }

      @Override
      protected AbstractAscriptionRepository<AscriptionEntity> getRepository() {
        return null;
      }

      @Override
      public AscriptionEntity buildEntity(
          DefinitionEntity def, ArchetypeEntity arch, JsonNode stmt) {
        return null;
      }

      @Override
      public AscriptionEntity save(AscriptionEntity entity) {
        return null;
      }

      @Override
      public Page<AscriptionEntity> findAll(Pageable pageable) {
        return null;
      }

      @Override
      public Page<AscriptionEntity> findAllByStatus(AscriptionStatusType s, Pageable p) {
        return null;
      }

      @Override
      public List<AscriptionEntity> findAllByDefinitionId(UUID id) {
        return existingAscriptions;
      }

      @Override
      public List<AscriptionEntity> findAllByDefinitionIdAndStatus(
          UUID id, Collection<AscriptionStatusType> s) {
        return List.of();
      }

      @Override
      public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
        return identityBoundValues;
      }

      @Override
      public List<RefereeReference> getRefereeReferences(AscriptionEntity entity) {
        return refereeReferences;
      }
    };
  }

  // ========================================================================
  // $gsm:unique enforcement
  // ========================================================================

  @Nested
  class EnforceUnique {

    @Test
    void uniqueProperty_noDuplicates_valid() {
      UUID defId = UUID.randomUUID();
      ObjectNode propNode = prop("string");
      propNode.put("$gsm:unique", true);
      ObjectNode archetypeSchema = schemaWithProperty("code", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("code", "ALPHA");

      // No in-effect ascriptions with same archetype
      when(ascriptionService.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
          any(), any(), eq(defId)))
          .thenReturn(List.of());

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, defId));
    }

    @Test
    void uniqueProperty_duplicateInEffect_rejected() {
      UUID defId = UUID.randomUUID();
      ObjectNode propNode = prop("string");
      propNode.put("$gsm:unique", true);
      ObjectNode archetypeSchema = schemaWithProperty("code", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("code", "ALPHA");

      // Another definition has an in-effect ascription with same value
      AscriptionEntity existing = mock(AscriptionEntity.class);
      when(existing.getId()).thenReturn(UUID.randomUUID());
      DefinitionEntity existingDef = mock(DefinitionEntity.class);
      when(existingDef.getId()).thenReturn(UUID.randomUUID());
      when(existing.getDefinition()).thenReturn(existingDef);
      when(existing.getStatement()).thenReturn(MAPPER.createObjectNode().put("code", "ALPHA"));

      when(ascriptionService.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
          any(), any(), eq(defId)))
          .thenReturn(List.of(existing));

      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> service.enforceAnnotations(statement, archetype, defId));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("duplicates"));
      assertTrue(ex.getMessage().contains("code"));
    }

    @Test
    void uniqueProperty_differentValues_valid() {
      UUID defId = UUID.randomUUID();
      ObjectNode propNode = prop("string");
      propNode.put("$gsm:unique", true);
      ObjectNode archetypeSchema = schemaWithProperty("code", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("code", "ALPHA");

      // Another definition has an in-effect ascription but with different value
      AscriptionEntity existing = mock(AscriptionEntity.class);
      when(existing.getStatement()).thenReturn(MAPPER.createObjectNode().put("code", "BETA"));

      when(ascriptionService.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
          any(), any(), eq(defId)))
          .thenReturn(List.of(existing));

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, defId));
    }
  }

  // ========================================================================
  // $gsm:identityBound enforcement on statement properties (schema-driven)
  // ========================================================================

  @Nested
  class EnforceStatementIdentityBound {

    @Test
    void identityBoundProperty_firstAscription_passes() {
      UUID defId = UUID.randomUUID();
      ObjectNode propNode = prop("string");
      propNode.put("$gsm:identityBound", true);
      ObjectNode archetypeSchema = schemaWithProperty("region", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("region", "eu-west-1");

      existingAscriptions = List.of(); // no prior ascriptions

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, defId));
    }

    @Test
    void identityBoundProperty_sameValue_passes() {
      UUID defId = UUID.randomUUID();
      ObjectNode propNode = prop("string");
      propNode.put("$gsm:identityBound", true);
      ObjectNode archetypeSchema = schemaWithProperty("region", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("region", "eu-west-1");

      AscriptionEntity existing = mock(AscriptionEntity.class);
      DefinitionEntity existingDef = mock(DefinitionEntity.class);
      when(existingDef.getId()).thenReturn(defId);
      when(existing.getDefinition()).thenReturn(existingDef);
      when(existing.getStatement())
          .thenReturn(MAPPER.createObjectNode().put("region", "eu-west-1"));

      existingAscriptions = List.of(existing);

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, defId));
    }

    @Test
    void identityBoundProperty_differentValue_rejected() {
      UUID defId = UUID.randomUUID();
      ObjectNode propNode = prop("string");
      propNode.put("$gsm:identityBound", true);
      ObjectNode archetypeSchema = schemaWithProperty("region", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("region", "us-east-1");

      AscriptionEntity existing = mock(AscriptionEntity.class);
      DefinitionEntity existingDef = mock(DefinitionEntity.class);
      when(existingDef.getId()).thenReturn(defId);
      when(existing.getDefinition()).thenReturn(existingDef);
      when(existing.getStatement())
          .thenReturn(MAPPER.createObjectNode().put("region", "eu-west-1"));

      existingAscriptions = List.of(existing);

      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> service.enforceAnnotations(statement, archetype, defId));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Identity-bound field"));
      assertTrue(ex.getMessage().contains("region"));
    }

    @Test
    void identityBoundProperty_missingInFirstAscription_passes() {
      UUID defId = UUID.randomUUID();
      ObjectNode propNode = prop("string");
      propNode.put("$gsm:identityBound", true);
      ObjectNode archetypeSchema = schemaWithProperty("region", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("region", "eu-west-1");

      // First ascription doesn't have the field at all (schema evolved)
      AscriptionEntity existing = mock(AscriptionEntity.class);
      DefinitionEntity existingDef = mock(DefinitionEntity.class);
      when(existingDef.getId()).thenReturn(defId);
      when(existing.getDefinition()).thenReturn(existingDef);
      when(existing.getStatement()).thenReturn(MAPPER.createObjectNode());

      existingAscriptions = List.of(existing);

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, defId));
    }
  }

  // ========================================================================
  // Statement validation (Ascription-V1)
  // ========================================================================

  @Nested
  class ValidateStatement {

    @Test
    void validStatement_passes() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      schema.put("type", "object");
      ObjectNode props = schema.putObject("properties");
      props.set("name", MAPPER.createObjectNode().put("type", "string"));

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("name", "hello");

      assertDoesNotThrow(() -> service.validateStatement(statement, archetype));
    }

    @Test
    void invalidStatement_rejected() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      schema.put("type", "object");
      ObjectNode props = schema.putObject("properties");
      props.set("count", MAPPER.createObjectNode().put("type", "integer"));
      schema.putArray("required").add("count");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      // Missing required field
      ObjectNode statement = MAPPER.createObjectNode();

      RuleViolationException ex = assertThrows(
          RuleViolationException.class, () -> service.validateStatement(statement, archetype));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Statement validation failed"));
    }

    @Test
    void wrongType_rejected() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      schema.put("type", "object");
      ObjectNode props = schema.putObject("properties");
      props.set("count", MAPPER.createObjectNode().put("type", "integer"));

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      // Wrong type: string where integer expected
      ObjectNode statement = MAPPER.createObjectNode().put("count", "not-a-number");

      RuleViolationException ex = assertThrows(
          RuleViolationException.class, () -> service.validateStatement(statement, archetype));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Statement validation failed"));
    }

    @Test
    void tenantArchetypeRef_resolvedFromDb() {
      // Tenant archetype schema in the DB
      ObjectNode tenantSchema = MAPPER.createObjectNode();
      tenantSchema.put("title", "CustomTenantArchetype");
      tenantSchema.put("type", "object");
      tenantSchema.putObject("properties").putObject("label").put("type", "string");

      ArchetypeEntity tenantArchetype = mock(ArchetypeEntity.class);
      when(tenantArchetype.getStatement()).thenReturn(tenantSchema);
      when(tenantArchetype.getStatus()).thenReturn(AscriptionStatusType.ACTIVE);

      when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(tenantArchetype));

      // Archetype schema that references tenant archetype
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "CompositeTenant");
      schema.put("type", "object");
      var allOf = schema.putArray("allOf");
      allOf.addObject().put("$ref", "gsm://archetypes/CustomTenantArchetype/v1");
      var local = allOf.addObject();
      local.put("type", "object");
      local.putObject("properties").putObject("extra").put("type", "integer");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("label", "hello").put("extra", 42);

      assertDoesNotThrow(() -> service.validateStatement(statement, archetype));
    }

    @Test
    void classpathOnly_usesStaticFactory() {
      // Schema with only local refs → no DB call
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "PureSelf");
      schema.put("type", "object");
      schema.putObject("properties").putObject("val").put("type", "string");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("val", "ok");

      assertDoesNotThrow(() -> service.validateStatement(statement, archetype));
      // archetypeRepo.findAllByStatusIn should NOT be called for classpath-only
      // schemas
      verify(archetypeRepo, never()).findAllByStatusIn(anyCollection());
    }
  }

  // ========================================================================
  // Identity-bound validation (LI-1, LI-2)
  // ========================================================================

  @Nested
  class ValidateIdentityBound {

    @Test
    void firstAscription_noExisting_passes() {
      UUID defId = UUID.randomUUID();
      identityBoundValues = Map.of("purpose", "order-processing");
      existingAscriptions = List.of(); // no prior ascriptions

      AscriptionEntity entity = stubEntityWithDefinition(defId);

      assertDoesNotThrow(() -> service.validateIdentityBound(entity));
    }

    @Test
    void sameIdentityBoundValues_passes() {
      UUID defId = UUID.randomUUID();
      identityBoundValues = Map.of("purpose", "order-processing");

      // Existing ascription with same identity-bound values
      AscriptionEntity existing = stubEntityWithDefinition(defId);
      existingAscriptions = List.of(existing);

      AscriptionEntity newEntity = stubEntityWithDefinition(defId);

      assertDoesNotThrow(() -> service.validateIdentityBound(newEntity));
    }

    @Test
    void differentIdentityBoundValues_rejected() {
      UUID defId = UUID.randomUUID();

      // First call (for newEntity): returns new values
      // Second call (for existing): returns old values
      // Since getIdentityBoundValues returns the same map for both,
      // we simulate by having the existing return different values.
      // We need a more nuanced setup:
      AscriptionEntity existing = mock(AscriptionEntity.class);
      DefinitionEntity existingDef = mock(DefinitionEntity.class);
      when(existingDef.getId()).thenReturn(defId);
      when(existing.getDefinition()).thenReturn(existingDef);

      existingAscriptions = List.of(existing);

      AscriptionEntity newEntity = mock(AscriptionEntity.class);
      DefinitionEntity newDef = mock(DefinitionEntity.class);
      when(newDef.getId()).thenReturn(defId);
      when(newEntity.getDefinition()).thenReturn(newDef);

      // Override getIdentityBoundValues to return different values
      // depending on which entity is passed
      final Map<String, Object> oldValues = Map.of("purpose", "old-purpose");
      final Map<String, Object> newValues = Map.of("purpose", "new-purpose");

      AbstractAscriptionService<AscriptionEntity> spyService = new AbstractAscriptionService<>(
          definitionService,
          transitionService,
          ascriptionService,
          archetypeRepo,
          entityManager,
          new DataProtectionService()) {
        @Override
        public DefinitionSubjectType getSubjectType() {
          return DefinitionSubjectType.STRUCTURE;
        }

        @Override
        protected AbstractAscriptionRepository<AscriptionEntity> getRepository() {
          return null;
        }

        @Override
        public AscriptionEntity buildEntity(
            DefinitionEntity def, ArchetypeEntity arch, JsonNode stmt) {
          return null;
        }

        @Override
        public AscriptionEntity save(AscriptionEntity entity) {
          return null;
        }

        @Override
        public Page<AscriptionEntity> findAll(Pageable pageable) {
          return null;
        }

        @Override
        public Page<AscriptionEntity> findAllByStatus(AscriptionStatusType s, Pageable p) {
          return null;
        }

        @Override
        public List<AscriptionEntity> findAllByDefinitionId(UUID id) {
          return existingAscriptions;
        }

        @Override
        public List<AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID id, Collection<AscriptionStatusType> s) {
          return List.of();
        }

        @Override
        public Map<String, Object> getIdentityBoundValues(AscriptionEntity e) {
          if (e == existing) {
            return oldValues;
          }
          return newValues;
        }
      };

      RuleViolationException ex = assertThrows(
          RuleViolationException.class, () -> spyService.validateIdentityBound(newEntity));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Identity-bound field"));
      assertTrue(ex.getMessage().contains("purpose"));
    }

    @Test
    void noIdentityBoundFields_passes() {
      UUID defId = UUID.randomUUID();
      identityBoundValues = Map.of(); // no identity-bound fields

      AscriptionEntity entity = stubEntityWithDefinition(defId);
      existingAscriptions = List.of(entity);

      assertDoesNotThrow(() -> service.validateIdentityBound(entity));
    }
  }

  // ========================================================================
  // Creation referee preconditions (RP-1)
  // ========================================================================

  @Nested
  class ValidateCreationPreconditions {

    @BeforeEach
    void setUpRealTransitionService() {
      AscriptionStatusTransitionService realTransition = new AscriptionStatusTransitionService(
          mock(cloud.poesis.sie.defman.repository.AscriptionStatusTransitionRepository.class),
          entityManager,
          List.of());
      realTransition.initCascadeGraph();
      org.springframework.test.util.ReflectionTestUtils.setField(
          service, "transitionService", realTransition);
    }

    @Test
    void noReferences_passes() {
      refereeReferences = List.of();
      AscriptionEntity entity = mock(AscriptionEntity.class);

      assertDoesNotThrow(() -> service.validateCreationPreconditions(entity));
    }

    @Test
    void referenceInActive_passes() {
      AscriptionEntity ref = mock(AscriptionEntity.class);
      when(ref.getStatus()).thenReturn(AscriptionStatusType.ACTIVE);
      when(ref.getId()).thenReturn(UUID.randomUUID());
      refereeReferences = List.of(new RefereeReference(ref, "structure"));

      AscriptionEntity entity = mock(AscriptionEntity.class);

      assertDoesNotThrow(() -> service.validateCreationPreconditions(entity));
    }

    @Test
    void referenceInDraft_passes() {
      AscriptionEntity ref = mock(AscriptionEntity.class);
      when(ref.getStatus()).thenReturn(AscriptionStatusType.DRAFT);
      when(ref.getId()).thenReturn(UUID.randomUUID());
      refereeReferences = List.of(new RefereeReference(ref, "structure"));

      AscriptionEntity entity = mock(AscriptionEntity.class);

      assertDoesNotThrow(() -> service.validateCreationPreconditions(entity));
    }

    @Test
    void referenceInProposed_passes() {
      AscriptionEntity ref = mock(AscriptionEntity.class);
      when(ref.getStatus()).thenReturn(AscriptionStatusType.PROPOSED);
      when(ref.getId()).thenReturn(UUID.randomUUID());
      refereeReferences = List.of(new RefereeReference(ref, "structure"));

      AscriptionEntity entity = mock(AscriptionEntity.class);

      assertDoesNotThrow(() -> service.validateCreationPreconditions(entity));
    }

    @Test
    void referenceInApproved_passes() {
      AscriptionEntity ref = mock(AscriptionEntity.class);
      when(ref.getStatus()).thenReturn(AscriptionStatusType.APPROVED);
      when(ref.getId()).thenReturn(UUID.randomUUID());
      refereeReferences = List.of(new RefereeReference(ref, "structure"));

      AscriptionEntity entity = mock(AscriptionEntity.class);

      assertDoesNotThrow(() -> service.validateCreationPreconditions(entity));
    }

    @Test
    void referenceInRetired_rejected() {
      AscriptionEntity ref = mock(AscriptionEntity.class);
      when(ref.getStatus()).thenReturn(AscriptionStatusType.RETIRED);
      when(ref.getId()).thenReturn(UUID.randomUUID());
      refereeReferences = List.of(new RefereeReference(ref, "structure"));

      AscriptionEntity entity = mock(AscriptionEntity.class);

      RuleViolationException ex = assertThrows(
          RuleViolationException.class, () -> service.validateCreationPreconditions(entity));
      assertEquals(
          AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Referee"));
      assertTrue(ex.getMessage().contains("RETIRED"));
    }

    @Test
    void referenceInSuspended_rejected() {
      AscriptionEntity ref = mock(AscriptionEntity.class);
      when(ref.getStatus()).thenReturn(AscriptionStatusType.SUSPENDED);
      when(ref.getId()).thenReturn(UUID.randomUUID());
      refereeReferences = List.of(new RefereeReference(ref, "structure"));

      AscriptionEntity entity = mock(AscriptionEntity.class);

      RuleViolationException ex = assertThrows(
          RuleViolationException.class, () -> service.validateCreationPreconditions(entity));
      assertEquals(
          AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Referee"));
    }

    @Test
    void referenceInDeprecated_rejected() {
      AscriptionEntity ref = mock(AscriptionEntity.class);
      when(ref.getStatus()).thenReturn(AscriptionStatusType.DEPRECATED);
      when(ref.getId()).thenReturn(UUID.randomUUID());
      refereeReferences = List.of(new RefereeReference(ref, "structure"));

      AscriptionEntity entity = mock(AscriptionEntity.class);

      RuleViolationException ex = assertThrows(
          RuleViolationException.class, () -> service.validateCreationPreconditions(entity));
      assertEquals(
          AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Referee"));
    }

    @Test
    void referenceInAbandoned_rejected() {
      AscriptionEntity ref = mock(AscriptionEntity.class);
      when(ref.getStatus()).thenReturn(AscriptionStatusType.ABANDONED);
      when(ref.getId()).thenReturn(UUID.randomUUID());
      refereeReferences = List.of(new RefereeReference(ref, "structure"));

      AscriptionEntity entity = mock(AscriptionEntity.class);

      RuleViolationException ex = assertThrows(
          RuleViolationException.class, () -> service.validateCreationPreconditions(entity));
      assertEquals(
          AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Referee"));
    }
  }

  // ========================================================================
  // $gsm:dataProtection atRest enforcement
  // ========================================================================

  @Nested
  class EnforceDataProtectionAtRest {

    @Test
    void hashAtRest_replacesValueWithHash() {
      ObjectNode propNode = prop("string");
      ObjectNode dp = MAPPER.createObjectNode();
      ObjectNode atRest = dp.putObject("atRest");
      atRest.putObject("hash").put("algorithm", "SHA-256");
      propNode.set("$gsm:dataProtection", dp);
      ObjectNode archetypeSchema = schemaWithProperty("ssn", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("ssn", "123-45-6789");

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));

      // Value should be replaced with a hex hash (64 chars for SHA-256)
      String hashed = statement.get("ssn").asText();
      assertTrue(hashed.matches("[0-9a-f]{64}"), "Expected SHA-256 hex hash, got: " + hashed);
    }

    @Test
    void maskAtRest_masksValue() {
      ObjectNode propNode = prop("string");
      ObjectNode dp = MAPPER.createObjectNode();
      ObjectNode atRest = dp.putObject("atRest");
      ObjectNode mask = atRest.putObject("mask");
      mask.put("from", "RIGHT");
      ObjectNode with = mask.putObject("with");
      with.put("character", "*");
      with.put("occurrence", 4);
      propNode.set("$gsm:dataProtection", dp);
      ObjectNode archetypeSchema = schemaWithProperty("phone", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("phone", "555-1234");

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));

      // RIGHT direction = keep last 4 visible
      String masked = statement.get("phone").asText();
      assertTrue(masked.endsWith("1234"), "Expected last 4 chars visible, got: " + masked);
      assertTrue(masked.contains("*"), "Expected masking characters, got: " + masked);
    }

    @Test
    void maskAtRest_leftDirection_masksCorrectly() {
      ObjectNode propNode = prop("string");
      ObjectNode dp = MAPPER.createObjectNode();
      ObjectNode atRest = dp.putObject("atRest");
      ObjectNode mask = atRest.putObject("mask");
      mask.put("from", "LEFT");
      ObjectNode with = mask.putObject("with");
      with.put("character", "#");
      with.put("occurrence", 3);
      propNode.set("$gsm:dataProtection", dp);
      ObjectNode archetypeSchema = schemaWithProperty("card", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("card", "4111222233334444");

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));

      // LEFT = keep first 3 visible, mask the rest
      String masked = statement.get("card").asText();
      assertEquals("411#############", masked);
    }

    @Test
    void maskAtRest_shortValue_masksEntirely() {
      ObjectNode propNode = prop("string");
      ObjectNode dp = MAPPER.createObjectNode();
      ObjectNode atRest = dp.putObject("atRest");
      ObjectNode mask = atRest.putObject("mask");
      mask.put("from", "RIGHT");
      ObjectNode with = mask.putObject("with");
      with.put("occurrence", 4);
      // character omitted — defaults to '*'
      propNode.set("$gsm:dataProtection", dp);
      ObjectNode archetypeSchema = schemaWithProperty("pin", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("pin", "12");

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));

      // Value length (2) <= occurrence (4) → mask entirely
      String masked = statement.get("pin").asText();
      assertEquals("**", masked);
    }

    @Test
    void suppressionAtRest_removesField() {
      ObjectNode propNode = prop("string");
      ObjectNode dp = MAPPER.createObjectNode();
      ObjectNode atRest = dp.putObject("atRest");
      atRest.put("suppression", true);
      propNode.set("$gsm:dataProtection", dp);
      ObjectNode archetypeSchema = schemaWithProperty("secret", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("secret", "top-secret-value");

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));

      assertTrue(
          !statement.has("secret") || statement.get("secret").isNull(),
          "Expected field to be removed or null");
    }

    @Test
    void encryptionAtRest_silentlyIgnored() {
      ObjectNode propNode = prop("string");
      ObjectNode dp = MAPPER.createObjectNode();
      ObjectNode atRest = dp.putObject("atRest");
      atRest.putObject("encryption").put("algorithm", "AES-256-GCM");
      propNode.set("$gsm:dataProtection", dp);
      ObjectNode archetypeSchema = schemaWithProperty("card", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("card", "4111-1111-1111-1111");

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));
    }

    @Test
    void noDataProtection_unchanged() {
      ObjectNode archetypeSchema = schemaWithProperty("name", prop("string"));

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode().put("name", "Alice");

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));
      assertTrue(statement.get("name").asText().equals("Alice"));
    }

    @Test
    void missingFieldInStatement_skipped() {
      ObjectNode propNode = prop("string");
      ObjectNode dp = MAPPER.createObjectNode();
      ObjectNode atRest = dp.putObject("atRest");
      atRest.putObject("hash").put("algorithm", "SHA-256");
      propNode.set("$gsm:dataProtection", dp);
      ObjectNode archetypeSchema = schemaWithProperty("ssn", propNode);

      ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
      ObjectNode statement = MAPPER.createObjectNode(); // ssn not present

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));
      assertTrue(!statement.has("ssn"));
    }
  }

  // ========================================================================
  // extractUuidList — branches
  // ========================================================================

  @Nested
  class ExtractUuidList {

    @Test
    void validArray_returnsUuids() {
      UUID id1 = UUID.randomUUID();
      UUID id2 = UUID.randomUUID();
      ObjectNode statement = MAPPER.createObjectNode();
      statement.putArray("ids").add(id1.toString()).add(id2.toString());

      List<UUID> result = service.extractUuidList(statement, "ids");
      assertEquals(2, result.size());
      assertEquals(id1, result.get(0));
      assertEquals(id2, result.get(1));
    }

    @Test
    void missingField_returnsEmpty() {
      ObjectNode statement = MAPPER.createObjectNode();

      List<UUID> result = service.extractUuidList(statement, "ids");
      assertTrue(result.isEmpty());
    }

    @Test
    void nullField_returnsEmpty() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.putNull("ids");

      List<UUID> result = service.extractUuidList(statement, "ids");
      assertTrue(result.isEmpty());
    }

    @Test
    void notArray_returnsEmpty() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("ids", "not-an-array");

      List<UUID> result = service.extractUuidList(statement, "ids");
      assertTrue(result.isEmpty());
    }

    @Test
    void invalidElement_throws() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.putArray("ids").add("not-a-uuid");

      RuleViolationException ex = assertThrows(
          RuleViolationException.class, () -> service.extractUuidList(statement, "ids"));
      assertTrue(ex.getMessage().contains("Invalid UUID"));
    }
  }

  // ========================================================================
  // create() template method
  // ========================================================================

  @Nested
  class CreateMethod {

    @Test
    void happyPath_createsAndReturnsSavedEntity() {
      UUID defId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);
      when(definitionService.resolveOrCreate(eq(defId), any())).thenReturn(definition);

      // Minimal valid Archetype schema (no required fields, no $gsm:* annotations)
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      schema.put("type", "object");
      ArchetypeEntity archetypeRef = stubArchetypeWithSchema(schema);

      ObjectNode statement = MAPPER.createObjectNode();

      // Build entity stub
      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);

      // Override buildEntity and save for this test via a service that delegates
      // properly
      AbstractAscriptionService<AscriptionEntity> createService = new AbstractAscriptionService<>(
          definitionService,
          transitionService,
          ascriptionService,
          archetypeRepo,
          entityManager,
          new DataProtectionService()) {
        @Override
        public DefinitionSubjectType getSubjectType() {
          return DefinitionSubjectType.STRUCTURE;
        }

        @Override
        protected AbstractAscriptionRepository<AscriptionEntity> getRepository() {
          return null;
        }

        @Override
        public AscriptionEntity buildEntity(
            DefinitionEntity def, ArchetypeEntity arch, JsonNode stmt) {
          return entity;
        }

        @Override
        public AscriptionEntity save(AscriptionEntity e) {
          return e;
        }

        @Override
        public Page<AscriptionEntity> findAll(Pageable p) {
          return null;
        }

        @Override
        public Page<AscriptionEntity> findAllByStatus(AscriptionStatusType s, Pageable p) {
          return null;
        }

        @Override
        public List<AscriptionEntity> findAllByDefinitionId(UUID id) {
          return List.of();
        }

        @Override
        public List<AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID id, Collection<AscriptionStatusType> s) {
          return List.of();
        }

        @Override
        public Map<String, Object> getIdentityBoundValues(AscriptionEntity e) {
          return Map.of();
        }

        @Override
        public List<RefereeReference> getRefereeReferences(AscriptionEntity e) {
          return List.of();
        }
      };

      AscriptionEntity result = createService.create(archetypeRef, statement, defId);

      assertEquals(entity, result);
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

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

  private AscriptionEntity stubEntityWithDefinition(UUID defId) {
    AscriptionEntity entity = mock(AscriptionEntity.class);
    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(defId);
    when(entity.getDefinition()).thenReturn(def);
    return entity;
  }

  // ========================================================================
  // enforceAnnotations — early-return paths
  // ========================================================================

  @Nested
  class EnforceAnnotationsEarlyReturns {

    @Test
    void nullArchetypeStatement_returnsSilently() {
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(null);

      DefinitionEntity def = mock(DefinitionEntity.class);
      when(def.getId()).thenReturn(UUID.randomUUID());
      when(archetype.getDefinition()).thenReturn(def);

      ObjectNode statement = MAPPER.createObjectNode().put("x", "val");

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));
    }

    @Test
    void nullProperties_returnsSilently() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      // No "properties" key

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("x", "val");

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));
    }

    @Test
    void propertiesIsNotObject_returnsSilently() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      schema.put("properties", "not-an-object");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("x", "val");

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));
    }
  }

  // ========================================================================
  // Lifecycle hooks — default no-ops
  // ========================================================================

  @Nested
  class LifecycleHooksDefaults {

    @Test
    void onActivation_defaultNoOp() {
      AscriptionEntity entity = mock(AscriptionEntity.class);
      assertDoesNotThrow(() -> service.onActivation(entity));
    }

    @Test
    void onDeactivation_defaultNoOp() {
      AscriptionEntity entity = mock(AscriptionEntity.class);
      assertDoesNotThrow(() -> service.onDeactivation(entity));
    }

    @Test
    void afterCreate_defaultNoOp() {
      AscriptionEntity entity = mock(AscriptionEntity.class);
      assertDoesNotThrow(() -> service.afterCreate(entity));
    }
  }

  // ========================================================================
  // Delegation methods via getRepository() — concrete base implementations
  // ========================================================================

  @Nested
  class DelegationMethods {

    @Mock
    private AbstractAscriptionRepository<AscriptionEntity> mockRepo;

    private AbstractAscriptionService<AscriptionEntity> repoService;

    @BeforeEach
    void setUpRepoService() {
      repoService = new AbstractAscriptionService<>(
          definitionService,
          transitionService,
          ascriptionService,
          archetypeRepo,
          entityManager,
          new DataProtectionService()) {
        @Override
        public DefinitionSubjectType getSubjectType() {
          return DefinitionSubjectType.STRUCTURE;
        }

        @Override
        protected AbstractAscriptionRepository<AscriptionEntity> getRepository() {
          return mockRepo;
        }

        @Override
        public AscriptionEntity buildEntity(
            DefinitionEntity def, ArchetypeEntity arch, JsonNode stmt) {
          return null;
        }
      };
    }

    @Test
    void save_delegatesToRepo() {
      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(mockRepo.save(entity)).thenReturn(entity);

      AscriptionEntity result = repoService.save(entity);

      assertEquals(entity, result);
      verify(mockRepo).save(entity);
    }

    @Test
    void findAll_delegatesToRepo() {
      Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 10);
      when(mockRepo.findAll(pageable)).thenReturn(Page.empty());

      Page<AscriptionEntity> result = repoService.findAll(pageable);

      assertTrue(result.isEmpty());
      verify(mockRepo).findAll(pageable);
    }

    @Test
    void findAllByStatus_delegatesToRepo() {
      Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 10);
      when(mockRepo.findAllByStatus(AscriptionStatusType.ACTIVE, pageable))
          .thenReturn(Page.empty());

      Page<AscriptionEntity> result = repoService.findAllByStatus(AscriptionStatusType.ACTIVE, pageable);

      assertTrue(result.isEmpty());
      verify(mockRepo).findAllByStatus(AscriptionStatusType.ACTIVE, pageable);
    }

    @Test
    void findAllByDefinitionId_delegatesToRepo() {
      UUID defId = UUID.randomUUID();
      when(mockRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

      List<AscriptionEntity> result = repoService.findAllByDefinitionId(defId);

      assertTrue(result.isEmpty());
      verify(mockRepo).findAllByDefinitionIdOrderByTimestampDesc(defId);
    }

    @Test
    void findAllByDefinitionIdAndStatus_delegatesToRepo() {
      UUID defId = UUID.randomUUID();
      Collection<AscriptionStatusType> statuses = List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);
      when(mockRepo.findAllByDefinitionIdAndStatusIn(defId, statuses)).thenReturn(List.of());

      List<AscriptionEntity> result = repoService.findAllByDefinitionIdAndStatus(defId, statuses);

      assertTrue(result.isEmpty());
      verify(mockRepo).findAllByDefinitionIdAndStatusIn(defId, statuses);
    }

    @Test
    void getHistory_delegatesThroughFindAllByDefinitionId() {
      UUID defId = UUID.randomUUID();
      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(mockRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of(entity));

      List<AscriptionEntity> result = repoService.getHistory(defId);

      assertEquals(1, result.size());
      assertEquals(entity, result.get(0));
      verify(mockRepo).findAllByDefinitionIdOrderByTimestampDesc(defId);
    }

    @SuppressWarnings("unchecked") // Specification type erasure in JPA mock
    @Test
    void findAllFiltered_delegatesToRepoWithSpec() {
      UUID archDefId = UUID.randomUUID();
      Map<String, String> filters = Map.of("purpose", "compliance");
      Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 10);
      when(mockRepo.findAll(any(Specification.class), eq(pageable))).thenReturn(Page.empty());

      Page<AscriptionEntity> result = repoService.findAllFiltered(archDefId, filters, AscriptionStatusType.ACTIVE,
          pageable);

      assertTrue(result.isEmpty());
      verify(mockRepo).findAll(any(Specification.class), eq(pageable));
    }

    @SuppressWarnings("unchecked") // Specification type erasure in JPA mock
    @Test
    void findAllFiltered_withoutStatus_delegatesToRepo() {
      UUID archDefId = UUID.randomUUID();
      Map<String, String> filters = Map.of("region", "eu-west-1");
      Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 10);
      when(mockRepo.findAll(any(Specification.class), eq(pageable))).thenReturn(Page.empty());

      Page<AscriptionEntity> result = repoService.findAllFiltered(archDefId, filters, null, pageable);

      assertTrue(result.isEmpty());
      verify(mockRepo).findAll(any(Specification.class), eq(pageable));
    }
  }

  // ========================================================================
  // Default lifecycle descriptor implementations
  // ========================================================================

  @Nested
  class DefaultLifecycleDescriptors {

    private AbstractAscriptionService<AscriptionEntity> defaultService;

    @BeforeEach
    void setUpDefault() {
      defaultService = new AbstractAscriptionService<>(
          definitionService,
          transitionService,
          ascriptionService,
          archetypeRepo,
          entityManager,
          new DataProtectionService()) {
        @Override
        public DefinitionSubjectType getSubjectType() {
          return DefinitionSubjectType.STRUCTURE;
        }

        @Override
        protected AbstractAscriptionRepository<AscriptionEntity> getRepository() {
          return null;
        }

        @Override
        public AscriptionEntity buildEntity(
            DefinitionEntity def, ArchetypeEntity arch, JsonNode stmt) {
          return null;
        }
      };
    }

    @Test
    void getRefereeReferences_defaultReturnsEmpty() {
      AscriptionEntity entity = mock(AscriptionEntity.class);
      assertTrue(defaultService.getRefereeReferences(entity).isEmpty());
    }

    @Test
    void getCascadeTargetRoles_defaultReturnsEmpty() {
      assertTrue(defaultService.getCascadeTargetRoles().isEmpty());
    }

    @Test
    void findCascadeTargetsFrom_defaultReturnsEmpty() {
      assertTrue(
          defaultService
              .findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, UUID.randomUUID())
              .isEmpty());
    }

    @Test
    void getIdentityBoundValues_defaultReturnsEmpty() {
      AscriptionEntity entity = mock(AscriptionEntity.class);
      assertTrue(defaultService.getIdentityBoundValues(entity).isEmpty());
    }

    @Test
    void validateActivationUniqueness_defaultDoesNothing() {
      AscriptionEntity entity = mock(AscriptionEntity.class);
      assertDoesNotThrow(() -> defaultService.validateActivationUniqueness(entity));
    }
  }

  // ========================================================================
  // ValidateStatement — GSM base property classification
  // ========================================================================

  @Nested
  class ValidateStatementGsmBaseErrors {

    @Test
    void basePropertyViolation_throwsGsmRule() {
      // Schema requiring "purpose" (a GSM base property for STRUCTURE)
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "StructureSchema");
      schema.put("type", "object");
      schema.putObject("properties").putObject("purpose").put("type", "string");
      schema.putArray("required").add("purpose");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode(); // missing "purpose"

      RuleViolationException ex = assertThrows(
          RuleViolationException.class, () -> service.validateStatement(statement, archetype));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ex.getRuleType());
    }

    @Test
    void extensionPropertyViolation_throwsExtensionRule() {
      // Schema requiring "customField" (not a GSM base property for STRUCTURE)
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "StructureSchema");
      schema.put("type", "object");
      ObjectNode props = schema.putObject("properties");
      props.putObject("purpose").put("type", "string");
      props.putObject("customField").put("type", "integer");
      schema.putArray("required").add("customField");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      // Provide purpose but omit customField → extension violation
      ObjectNode statement = MAPPER.createObjectNode().put("purpose", "demo");

      RuleViolationException ex = assertThrows(
          RuleViolationException.class, () -> service.validateStatement(statement, archetype));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("tenant-extended"));
    }

    @Test
    void archetypeType_noExtensionRule_usesGsmRule() {
      // Archetype type has no extension rule (sealed)
      AbstractAscriptionService<AscriptionEntity> archetypeService = new AbstractAscriptionService<>(
          definitionService,
          transitionService,
          ascriptionService,
          archetypeRepo,
          entityManager,
          new DataProtectionService()) {
        @Override
        public DefinitionSubjectType getSubjectType() {
          return DefinitionSubjectType.ARCHETYPE;
        }

        @Override
        protected AbstractAscriptionRepository<AscriptionEntity> getRepository() {
          return null;
        }

        @Override
        public AscriptionEntity buildEntity(
            DefinitionEntity def, ArchetypeEntity arch, JsonNode stmt) {
          return null;
        }
      };

      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "ArchetypeSchema");
      schema.put("type", "object");
      schema.putObject("properties").putObject("needed").put("type", "integer");
      schema.putArray("required").add("needed");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode(); // missing "needed"

      RuleViolationException ex = assertThrows(
          RuleViolationException.class,
          () -> archetypeService.validateStatement(statement, archetype));
      // ARCHETYPE has extensionStatementValidationRule() → null → falls to
      // non-extensible fallback
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          ex.getRuleType());
    }
  }

  // ========================================================================
  // BuildFilterSpec — predicate construction
  // ========================================================================

  @Nested
  class BuildFilterSpec {

    @Test
    @SuppressWarnings("unchecked") // JPA Criteria API generics
    void specWithStatusAndFilters_buildsPredicates() {
      UUID archDefId = UUID.randomUUID();
      Map<String, String> filters = Map.of("purpose", "compliance");

      Specification<AscriptionEntity> spec = AbstractAscriptionService.buildFilterSpec(
          archDefId, filters, AscriptionStatusType.ACTIVE);

      // Exercise the specification lambda by calling toPredicate with mocks
      jakarta.persistence.criteria.Root<AscriptionEntity> root = mock(jakarta.persistence.criteria.Root.class);
      jakarta.persistence.criteria.CriteriaQuery<?> query = mock(jakarta.persistence.criteria.CriteriaQuery.class);
      jakarta.persistence.criteria.CriteriaBuilder cb = mock(jakarta.persistence.criteria.CriteriaBuilder.class);

      // Stub the join path for archetype.definition.id
      jakarta.persistence.criteria.Path<Object> archPath = mock(jakarta.persistence.criteria.Path.class);
      jakarta.persistence.criteria.Path<Object> defPath = mock(jakarta.persistence.criteria.Path.class);
      jakarta.persistence.criteria.Path<Object> idPath = mock(jakarta.persistence.criteria.Path.class);
      when(root.get("archetype")).thenReturn(archPath);
      when(archPath.get("definition")).thenReturn(defPath);
      when(defPath.get("id")).thenReturn(idPath);

      // Stub status path
      jakarta.persistence.criteria.Path<Object> statusPath = mock(jakarta.persistence.criteria.Path.class);
      when(root.get("status")).thenReturn(statusPath);

      // Stub statement path for jsonb_extract_path_text
      jakarta.persistence.criteria.Path<Object> stmtPath = mock(jakarta.persistence.criteria.Path.class);
      when(root.get("statement")).thenReturn(stmtPath);

      // Stub cb methods
      jakarta.persistence.criteria.Predicate archPred = mock(jakarta.persistence.criteria.Predicate.class);
      jakarta.persistence.criteria.Predicate statusPred = mock(jakarta.persistence.criteria.Predicate.class);
      jakarta.persistence.criteria.Predicate jsonbPred = mock(jakarta.persistence.criteria.Predicate.class);
      jakarta.persistence.criteria.Predicate combined = mock(jakarta.persistence.criteria.Predicate.class);
      when(cb.equal(idPath, archDefId)).thenReturn(archPred);
      when(cb.equal(statusPath, AscriptionStatusType.ACTIVE)).thenReturn(statusPred);
      jakarta.persistence.criteria.Expression<String> jsonExpr = mock(jakarta.persistence.criteria.Expression.class);
      when(cb.function(eq("jsonb_extract_path_text"), eq(String.class), eq(stmtPath), any()))
          .thenReturn(jsonExpr);
      when(cb.equal(jsonExpr, "compliance")).thenReturn(jsonbPred);
      when(cb.and(any(jakarta.persistence.criteria.Predicate[].class))).thenReturn(combined);

      jakarta.persistence.criteria.Predicate result = spec.toPredicate(root, query, cb);

      assertEquals(combined, result);
      verify(cb).equal(idPath, archDefId);
      verify(cb).equal(statusPath, AscriptionStatusType.ACTIVE);
    }

    @Test
    @SuppressWarnings("unchecked") // JPA Criteria API generics
    void specWithoutStatus_omitsStatusPredicate() {
      UUID archDefId = UUID.randomUUID();
      Map<String, String> filters = Map.of();

      Specification<AscriptionEntity> spec = AbstractAscriptionService.buildFilterSpec(archDefId, filters, null);

      jakarta.persistence.criteria.Root<AscriptionEntity> root = mock(jakarta.persistence.criteria.Root.class);
      jakarta.persistence.criteria.CriteriaQuery<?> query = mock(jakarta.persistence.criteria.CriteriaQuery.class);
      jakarta.persistence.criteria.CriteriaBuilder cb = mock(jakarta.persistence.criteria.CriteriaBuilder.class);

      jakarta.persistence.criteria.Path<Object> archPath = mock(jakarta.persistence.criteria.Path.class);
      jakarta.persistence.criteria.Path<Object> defPath = mock(jakarta.persistence.criteria.Path.class);
      jakarta.persistence.criteria.Path<Object> idPath = mock(jakarta.persistence.criteria.Path.class);
      when(root.get("archetype")).thenReturn(archPath);
      when(archPath.get("definition")).thenReturn(defPath);
      when(defPath.get("id")).thenReturn(idPath);

      jakarta.persistence.criteria.Predicate archPred = mock(jakarta.persistence.criteria.Predicate.class);
      jakarta.persistence.criteria.Predicate combined = mock(jakarta.persistence.criteria.Predicate.class);
      when(cb.equal(idPath, archDefId)).thenReturn(archPred);
      when(cb.and(any(jakarta.persistence.criteria.Predicate[].class))).thenReturn(combined);

      spec.toPredicate(root, query, cb);

      // Status should never be accessed
      verify(root, never()).get("status");
    }
  }

  // ========================================================================
  // Persistence exception translation
  // ========================================================================

  @Nested
  class ExtractConstraintName {

    @Test
    void extractsFromHibernateConstraintViolationException() {
      var cve = new ConstraintViolationException("violation", null, "uq_structure_purpose");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      assertEquals("uq_structure_purpose", AbstractAscriptionService.extractConstraintName(dive));
    }

    @Test
    void returnsNullWhenCauseIsNotConstraintViolation() {
      var dive = new DataIntegrityViolationException(
          "no hibernate cause", new RuntimeException("something else"));

      assertNull(AbstractAscriptionService.extractConstraintName(dive));
    }

    @Test
    void returnsNullWhenNoCause() {
      var dive = new DataIntegrityViolationException("no cause");

      assertNull(AbstractAscriptionService.extractConstraintName(dive));
    }
  }

  @Nested
  class TranslatePersistenceException {

    @Test
    void mappedConstraint_returnsRuleViolation() {
      var cve = new ConstraintViolationException("violation", null, "uq_structure_purpose");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      RuntimeException result = AbstractAscriptionService.translatePersistenceException(dive);

      assertInstanceOf(RuleViolationException.class, result);
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
          ((RuleViolationException) result).getRuleType());
    }

    @Test
    void archetypeIdFkeySuffix_returnsArchetypeReferenceIntegrity() {
      var cve = new ConstraintViolationException("violation", null, "ascription_archetype_id_fkey");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      RuntimeException result = AbstractAscriptionService.translatePersistenceException(dive);

      assertInstanceOf(RuleViolationException.class, result);
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_REFERENCE_INTEGRITY,
          ((RuleViolationException) result).getRuleType());
    }

    @Test
    void outputArchetypeIdFkeySuffix_returnsEffectorArchetypeReferenceIntegrity() {
      var cve = new ConstraintViolationException("violation", null, "effector_output_archetype_id_fkey");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      RuntimeException result = AbstractAscriptionService.translatePersistenceException(dive);

      assertInstanceOf(RuleViolationException.class, result);
      assertEquals(
          AscriptionConsistencyRuleType.EFFECTOR_ARCHETYPE_REFERENCE_INTEGRITY,
          ((RuleViolationException) result).getRuleType());
    }

    @Test
    void inputArchetypeIdFkeySuffix_returnsReceptorArchetypeReferenceIntegrity() {
      var cve = new ConstraintViolationException("violation", null, "receptor_input_archetype_id_fkey");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      RuntimeException result = AbstractAscriptionService.translatePersistenceException(dive);

      assertInstanceOf(RuleViolationException.class, result);
      assertEquals(
          AscriptionConsistencyRuleType.RECEPTOR_ARCHETYPE_REFERENCE_INTEGRITY,
          ((RuleViolationException) result).getRuleType());
    }

    @Test
    void mechanismStructureIdFkey_returnsMechanismStructureReferenceIntegrity() {
      var cve = new ConstraintViolationException("violation", null, "mechanism_structure_id_fkey");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      RuntimeException result = AbstractAscriptionService.translatePersistenceException(dive);

      assertInstanceOf(RuleViolationException.class, result);
      assertEquals(
          AscriptionConsistencyRuleType.MECHANISM_STRUCTURE_REFERENCE_INTEGRITY,
          ((RuleViolationException) result).getRuleType());
    }

    @Test
    void unmappedConstraint_returnsInternal() {
      var cve = new ConstraintViolationException("violation", null, "some_unknown_constraint");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      RuntimeException result = AbstractAscriptionService.translatePersistenceException(dive);

      assertInstanceOf(InternalException.class, result);
      assertTrue(result.getMessage().contains("some_unknown_constraint"));
    }

    @Test
    void noConstraintName_returnsInternal() {
      var dive = new DataIntegrityViolationException("no cause");

      RuntimeException result = AbstractAscriptionService.translatePersistenceException(dive);

      assertInstanceOf(InternalException.class, result);
    }
  }
}
