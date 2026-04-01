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

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.InternalException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.service.AbstractAscriptionService.RefereeReference;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.RuleType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AbstractAscriptionServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private DefinitionService definitionService;

  @Mock private AscriptionService ascriptionService;

  @Mock private AscriptionStatusTransitionService transitionService;

  @Mock private ArchetypeRepository archetypeRepo;

  @Mock private EntityManager entityManager;

  /** Minimal concrete subclass for testing package-private base methods. */
  private AbstractAscriptionService<AscriptionEntity> service;

  // Configurable responses for abstract methods
  private List<AscriptionEntity> existingAscriptions = List.of();
  private Map<String, Object> identityBoundValues = Map.of();
  private List<RefereeReference> refereeReferences = List.of();

  @BeforeEach
  void setUp() {
    service =
        new AbstractAscriptionService<>(
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

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.enforceAnnotations(statement, archetype, defId));
      assertEquals(RuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS, ex.getRuleType());
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

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.enforceAnnotations(statement, archetype, defId));
      assertEquals(RuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION, ex.getRuleType());
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

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateStatement(statement, archetype));
      assertEquals(RuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE, ex.getRuleType());
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

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateStatement(statement, archetype));
      assertEquals(RuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE, ex.getRuleType());
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

      AbstractAscriptionService<AscriptionEntity> spyService =
          new AbstractAscriptionService<>(
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

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> spyService.validateIdentityBound(newEntity));
      assertEquals(RuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION, ex.getRuleType());
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

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateCreationPreconditions(entity));
      assertEquals(
          RuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
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

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateCreationPreconditions(entity));
      assertEquals(
          RuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
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

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateCreationPreconditions(entity));
      assertEquals(
          RuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
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

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateCreationPreconditions(entity));
      assertEquals(
          RuleType.ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Referee"));
    }
  }

  // ========================================================================
  // $gsm:validation evaluation at Ascription authoring
  // ========================================================================

  @Nested
  class EnforceValidation {

    @Test
    void celExpressionTrue_passes() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      ObjectNode props = schema.putObject("properties");
      props.set("budget", prop("number"));
      schema.set("$gsm:validation", MAPPER.createArrayNode().add("this.budget > 0.0"));

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("budget", 100.0);

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));
    }

    @Test
    void celExpressionFalse_rejected() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      ObjectNode props = schema.putObject("properties");
      props.set("budget", prop("number"));
      schema.set("$gsm:validation", MAPPER.createArrayNode().add("this.budget > 0.0"));

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("budget", -5.0);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));
      assertEquals(RuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
      assertTrue(ex.getMessage().contains("$gsm:validation[0]"));
      assertTrue(ex.getMessage().contains("constraint failed"));
    }

    @Test
    void multipleExpressions_allMustPass() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      ObjectNode props = schema.putObject("properties");
      props.set("min", prop("number"));
      props.set("max", prop("number"));
      schema.set(
          "$gsm:validation",
          MAPPER.createArrayNode().add("this.min > 0.0").add("this.min <= this.max"));

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement =
          MAPPER.createObjectNode().put("min", 10.0).put("max", 5.0); // violates 2nd
      // expression

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));
      assertEquals(RuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
      assertTrue(ex.getMessage().contains("$gsm:validation"));
      assertTrue(ex.getMessage().contains("constraint failed"));
    }

    @Test
    void allExpressionsTrue_passes() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      ObjectNode props = schema.putObject("properties");
      props.set("min", prop("number"));
      props.set("max", prop("number"));
      schema.set(
          "$gsm:validation",
          MAPPER.createArrayNode().add("this.min > 0.0").add("this.min <= this.max"));

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("min", 5.0).put("max", 10.0);

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));
    }

    @Test
    void noCelAnnotation_passes() {
      ObjectNode schema = schemaWithProperty("x", prop("string"));

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode().put("x", "hello");

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));
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
  // extractRequiredUuid — branches
  // ========================================================================

  @Nested
  class ExtractRequiredUuid {

    @Test
    void validUuid_extracted() {
      UUID expected = UUID.randomUUID();
      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("ref", expected.toString());

      UUID result = service.extractRequiredUuid(statement, "ref");
      assertEquals(expected, result);
    }

    @Test
    void missingField_throws() {
      ObjectNode statement = MAPPER.createObjectNode();

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.extractRequiredUuid(statement, "ref"));
      assertEquals(RuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
      assertTrue(ex.getMessage().contains("ref"));
    }

    @Test
    void blankField_throws() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("ref", "   ");

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.extractRequiredUuid(statement, "ref"));
      assertTrue(ex.getMessage().contains("ref"));
    }

    @Test
    void invalidUuid_throws() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("ref", "not-a-uuid");

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.extractRequiredUuid(statement, "ref"));
      assertTrue(ex.getMessage().contains("Invalid UUID"));
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

      RuleViolationException ex =
          assertThrows(
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
      AbstractAscriptionService<AscriptionEntity> createService =
          new AbstractAscriptionService<>(
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
  // evaluateValidation — edge cases
  // ========================================================================

  @Nested
  class EvaluateValidationEdgeCases {

    @Test
    void emptyArray_noOp() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      schema.putObject("properties");
      schema.putArray("$gsm:validation"); // empty array

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode();

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));
    }

    @Test
    void nonTextualElement_skipped() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      schema.putObject("properties");
      schema.putArray("$gsm:validation").add(42); // non-textual

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode();

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));
    }

    @Test
    void blankExpression_skipped() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      schema.putObject("properties");
      schema.putArray("$gsm:validation").add("   "); // blank

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode();

      assertDoesNotThrow(() -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));
    }

    @Test
    void celCompileError_throws() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      schema.putObject("properties");
      schema.putArray("$gsm:validation").add(">>>invalid_cel<<<");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode();

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));
      assertTrue(ex.getMessage().contains("$gsm:validation"));
    }

    @Test
    void nonBooleanResult_throws() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("title", "TestSchema");
      schema.putObject("properties");
      // This expression returns a string, not a boolean
      schema.putArray("$gsm:validation").add("\"hello\"");

      ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
      ObjectNode statement = MAPPER.createObjectNode();

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.enforceAnnotations(statement, archetype, UUID.randomUUID()));
      assertTrue(ex.getMessage().contains("$gsm:validation"));
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
      var dive =
          new DataIntegrityViolationException(
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
          RuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
          ((RuleViolationException) result).getRuleType());
    }

    @Test
    void archetypeIdFkeySuffix_returnsArchetypeReferenceIntegrity() {
      var cve = new ConstraintViolationException("violation", null, "ascription_archetype_id_fkey");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      RuntimeException result = AbstractAscriptionService.translatePersistenceException(dive);

      assertInstanceOf(RuleViolationException.class, result);
      assertEquals(
          RuleType.ASCRIPTION_ARCHETYPE_REFERENCE_INTEGRITY,
          ((RuleViolationException) result).getRuleType());
    }

    @Test
    void outputArchetypeIdFkeySuffix_returnsEffectorArchetypeReferenceIntegrity() {
      var cve =
          new ConstraintViolationException("violation", null, "effector_output_archetype_id_fkey");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      RuntimeException result = AbstractAscriptionService.translatePersistenceException(dive);

      assertInstanceOf(RuleViolationException.class, result);
      assertEquals(
          RuleType.EFFECTOR_ARCHETYPE_REFERENCE_INTEGRITY,
          ((RuleViolationException) result).getRuleType());
    }

    @Test
    void inputArchetypeIdFkeySuffix_returnsReceptorArchetypeReferenceIntegrity() {
      var cve =
          new ConstraintViolationException("violation", null, "receptor_input_archetype_id_fkey");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      RuntimeException result = AbstractAscriptionService.translatePersistenceException(dive);

      assertInstanceOf(RuleViolationException.class, result);
      assertEquals(
          RuleType.RECEPTOR_ARCHETYPE_REFERENCE_INTEGRITY,
          ((RuleViolationException) result).getRuleType());
    }

    @Test
    void mechanismStructureIdFkey_returnsMechanismStructureReferenceIntegrity() {
      var cve = new ConstraintViolationException("violation", null, "mechanism_structure_id_fkey");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      RuntimeException result = AbstractAscriptionService.translatePersistenceException(dive);

      assertInstanceOf(RuleViolationException.class, result);
      assertEquals(
          RuleType.MECHANISM_STRUCTURE_REFERENCE_INTEGRITY,
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
