package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.InternalException;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AscriptionServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final AscriptionConsistencyRuleType RULE =
      AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;

  @Mock private AscriptionRepository ascriptionRepository;
  @Mock private ArchetypeService archetypeService;
  @Mock private DefinitionService definitionService;
  @Mock private AscriptionStateMachineService stateMachine;
  @Mock private AscriptionStatementValidationService statementValidation;
  @Mock private EntityManager entityManager;

  @SuppressWarnings("unchecked")
  private final AbstractAscriptionRepository<AscriptionEntity> structureRepo =
      mock(AbstractAscriptionRepository.class);

  @SuppressWarnings("unchecked")
  private final SubtypeHandler<AscriptionEntity> structureHandler = mock(SubtypeHandler.class);

  private AscriptionService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    when(structureHandler.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    when(structureHandler.getRepository()).thenReturn(structureRepo);
    when(structureHandler.getIdentityBoundValues(any())).thenReturn(Map.of());
    when(structureHandler.getRefereeReferences(any())).thenReturn(List.of());
    when(structureHandler.getCascadeTargetRoles()).thenReturn(Map.of());

    List<SubtypeHandler<?>> handlers = new ArrayList<>();
    handlers.add(structureHandler);
    for (DefinitionSubjectType type : DefinitionSubjectType.values()) {
      if (type == DefinitionSubjectType.STRUCTURE) continue;
      SubtypeHandler<?> h = mock(SubtypeHandler.class);
      when(h.getSubjectType()).thenReturn(type);
      when(h.getRepository()).thenReturn(mock(AbstractAscriptionRepository.class));
      when(h.getCascadeTargetRoles()).thenReturn(Map.of());
      handlers.add(h);
    }

    service =
        new AscriptionService(
            ascriptionRepository,
            archetypeService,
            definitionService,
            stateMachine,
            statementValidation,
            entityManager,
            handlers);
    service.afterSingletonsInstantiated();
  }

  // ========================================================================
  // Initialization
  // ========================================================================

  @Nested
  class Initialization {

    @Test
    @SuppressWarnings("unchecked")
    void duplicateHandler_throws() {
      SubtypeHandler<?> dup1 = mock(SubtypeHandler.class);
      SubtypeHandler<?> dup2 = mock(SubtypeHandler.class);
      when(dup1.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
      when(dup2.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);

      AscriptionService svc =
          new AscriptionService(
              ascriptionRepository,
              archetypeService,
              definitionService,
              stateMachine,
              statementValidation,
              entityManager,
              List.of(dup1, dup2));

      assertThrows(IllegalStateException.class, svc::afterSingletonsInstantiated);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingHandler_throws() {
      SubtypeHandler<?> only = mock(SubtypeHandler.class);
      when(only.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);

      AscriptionService svc =
          new AscriptionService(
              ascriptionRepository,
              archetypeService,
              definitionService,
              stateMachine,
              statementValidation,
              entityManager,
              List.of(only));

      assertThrows(IllegalStateException.class, svc::afterSingletonsInstantiated);
    }
  }

  // ========================================================================
  // Cross-subtype lookups
  // ========================================================================

  @Nested
  class CrossSubtypeLookups {

    @Test
    void getById_returnsEntity_whenFound() {
      UUID id = UUID.randomUUID();
      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(ascriptionRepository.findById(id)).thenReturn(Optional.of(entity));

      AscriptionEntity result = service.getById(id);

      assertNotNull(result);
      assertEquals(entity, result);
    }

    @Test
    void getById_throwsResourceNotFound_whenNotFound() {
      UUID id = UUID.randomUUID();
      when(ascriptionRepository.findById(id)).thenReturn(Optional.empty());

      ResourceNotFoundException ex =
          assertThrows(ResourceNotFoundException.class, () -> service.getById(id));
      assertEquals(id, ex.getResourceId());
    }

    @Test
    void findAllByArchetypeIdAndStatusInAndDefinitionIdNot_delegatesToRepo() {
      UUID archetypeId = UUID.randomUUID();
      UUID excludeDefId = UUID.randomUUID();
      List<AscriptionStatusType> statuses =
          List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);
      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(ascriptionRepository.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
              archetypeId, statuses, excludeDefId))
          .thenReturn(List.of(entity));

      List<AscriptionEntity> result =
          service.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
              archetypeId, statuses, excludeDefId);

      assertEquals(1, result.size());
      assertEquals(entity, result.get(0));
    }
  }

  // ========================================================================
  // Handler access
  // ========================================================================

  @Nested
  class HandlerAccess {

    @Test
    void getHandler_returnsRegisteredHandler() {
      SubtypeHandler<?> handler = service.getHandler(DefinitionSubjectType.STRUCTURE);
      assertNotNull(handler);
      assertEquals(DefinitionSubjectType.STRUCTURE, handler.getSubjectType());
    }
  }

  // ========================================================================
  // Create template
  // ========================================================================

  @Nested
  class CreateTemplate {

    @Test
    void happyPath_createsAndReturns() {
      UUID defId = UUID.randomUUID();
      UUID archetypeId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);
      when(definitionService.resolveOrCreate(eq(defId), any())).thenReturn(definition);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetypeService.resolveForCreation(archetypeId))
          .thenReturn(
              new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));
      ObjectNode statement = MAPPER.createObjectNode();

      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(structureHandler.buildEntity(definition, archetype, statement)).thenReturn(entity);
      when(structureRepo.save(entity)).thenReturn(entity);
      when(structureRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

      AscriptionEntity result = service.create(archetypeId, statement, defId);

      assertEquals(entity, result);
      verify(statementValidation)
          .validateStatement(statement, archetype, DefinitionSubjectType.STRUCTURE);
      verify(entityManager).flush();
      verify(entityManager).refresh(entity);
      verify(structureHandler).afterCreate(entity);
    }

    @Test
    void identityBoundViolation_throws() {
      UUID defId = UUID.randomUUID();
      UUID archetypeId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);
      when(definitionService.resolveOrCreate(eq(defId), any())).thenReturn(definition);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetypeService.resolveForCreation(archetypeId))
          .thenReturn(
              new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));
      ObjectNode statement = MAPPER.createObjectNode();

      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(structureHandler.buildEntity(definition, archetype, statement)).thenReturn(entity);

      // New entity claims purpose=new-purpose
      when(structureHandler.getIdentityBoundValues(any()))
          .thenReturn(Map.of("purpose", "new-purpose"));

      // Existing ascription with purpose=old-purpose
      AscriptionEntity existing = mock(AscriptionEntity.class);
      when(structureHandler.findAllByDefinitionId(defId)).thenReturn(List.of(existing));

      // Override getIdentityBoundValues to return different values for existing
      when(structureHandler.getIdentityBoundValues(existing))
          .thenReturn(Map.of("purpose", "old-purpose"));

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.create(archetypeId, statement, defId));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Identity-bound field"));
    }

    @Test
    void creationPreconditionFailure_propagates() {
      UUID defId = UUID.randomUUID();
      UUID archetypeId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);
      when(definitionService.resolveOrCreate(eq(defId), any())).thenReturn(definition);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetypeService.resolveForCreation(archetypeId))
          .thenReturn(
              new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));
      ObjectNode statement = MAPPER.createObjectNode();

      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(structureHandler.buildEntity(definition, archetype, statement)).thenReturn(entity);
      when(structureRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

      // Referee in terminal status
      AscriptionEntity badRef = mock(AscriptionEntity.class);
      when(badRef.getStatus()).thenReturn(AscriptionStatusType.RETIRED);
      when(badRef.getId()).thenReturn(UUID.randomUUID());
      when(structureHandler.getRefereeReferences(entity))
          .thenReturn(List.of(Map.entry(badRef, "structure")));

      // StateMachine will throw for terminal referee
      RuleViolationException expected =
          RuleViolationException.of(
              cloud.poesis.sie.defman.type.AscriptionStatusTransitionRuleType
                  .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
              "Referee in terminal status");
      doThrow(expected).when(stateMachine).validateRefereePreconditions(any(), any(), any());

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.create(archetypeId, statement, defId));
      assertEquals(expected, ex);
    }

    @Test
    void dataIntegrityViolation_translated() {
      UUID defId = UUID.randomUUID();
      UUID archetypeId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);
      when(definitionService.resolveOrCreate(eq(defId), any())).thenReturn(definition);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetypeService.resolveForCreation(archetypeId))
          .thenReturn(
              new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));
      ObjectNode statement = MAPPER.createObjectNode();

      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(structureHandler.buildEntity(definition, archetype, statement)).thenReturn(entity);
      when(structureRepo.save(entity)).thenThrow(new DataIntegrityViolationException("dup"));

      assertThrows(InternalException.class, () -> service.create(archetypeId, statement, defId));
    }
  }

  // ========================================================================
  // Identity-bound validation branches
  // ========================================================================

  @Nested
  class ValidateIdentityBound {

    @Test
    void noIdentityBoundFields_passes() {
      UUID defId = UUID.randomUUID();
      UUID archetypeId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);
      when(definitionService.resolveOrCreate(eq(defId), any())).thenReturn(definition);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetypeService.resolveForCreation(archetypeId))
          .thenReturn(
              new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));
      ObjectNode statement = MAPPER.createObjectNode();

      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(structureHandler.buildEntity(definition, archetype, statement)).thenReturn(entity);
      when(structureHandler.getIdentityBoundValues(any())).thenReturn(Map.of());
      when(structureRepo.save(entity)).thenReturn(entity);
      when(structureRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

      assertDoesNotThrow(() -> service.create(archetypeId, statement, defId));
    }

    @Test
    void noExistingAscriptions_passes() {
      UUID defId = UUID.randomUUID();
      UUID archetypeId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);
      when(definitionService.resolveOrCreate(eq(defId), any())).thenReturn(definition);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetypeService.resolveForCreation(archetypeId))
          .thenReturn(
              new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));
      ObjectNode statement = MAPPER.createObjectNode();

      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(structureHandler.buildEntity(definition, archetype, statement)).thenReturn(entity);
      when(structureHandler.getIdentityBoundValues(any()))
          .thenReturn(Map.of("purpose", "compliance"));
      when(structureRepo.save(entity)).thenReturn(entity);
      when(structureRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

      assertDoesNotThrow(() -> service.create(archetypeId, statement, defId));
    }

    @Test
    void sameIdentityBoundValues_passes() {
      UUID defId = UUID.randomUUID();
      UUID archetypeId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);
      when(definitionService.resolveOrCreate(eq(defId), any())).thenReturn(definition);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetypeService.resolveForCreation(archetypeId))
          .thenReturn(
              new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));
      ObjectNode statement = MAPPER.createObjectNode();

      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(structureHandler.buildEntity(definition, archetype, statement)).thenReturn(entity);

      AscriptionEntity existing = mock(AscriptionEntity.class);
      when(structureHandler.getIdentityBoundValues(any()))
          .thenReturn(Map.of("purpose", "compliance"));
      when(structureRepo.save(entity)).thenReturn(entity);
      when(structureRepo.findAllByDefinitionIdOrderByTimestampDesc(defId))
          .thenReturn(List.of(existing));

      assertDoesNotThrow(() -> service.create(archetypeId, statement, defId));
    }
  }

  // ========================================================================
  // Generic CRUD delegation
  // ========================================================================

  @Nested
  class GenericCrud {

    @Test
    void findAll_delegatesToHandlerRepo() {
      Pageable pageable = PageRequest.of(0, 10);
      when(structureRepo.findAll(pageable)).thenReturn(Page.empty());

      Page<AscriptionEntity> result = service.findAll(DefinitionSubjectType.STRUCTURE, pageable);

      assertTrue(result.isEmpty());
      verify(structureRepo).findAll(pageable);
    }

    @Test
    void findAllByStatus_delegatesToHandlerRepo() {
      Pageable pageable = PageRequest.of(0, 10);
      when(structureRepo.findAllByStatus(AscriptionStatusType.ACTIVE, pageable))
          .thenReturn(Page.empty());

      Page<AscriptionEntity> result =
          service.findAllByStatus(
              DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE, pageable);

      assertTrue(result.isEmpty());
      verify(structureRepo).findAllByStatus(AscriptionStatusType.ACTIVE, pageable);
    }

    @Test
    void findAllByDefinitionId_delegatesToHandlerRepo() {
      UUID defId = UUID.randomUUID();
      when(structureRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

      List<AscriptionEntity> result =
          service.findAllByDefinitionId(DefinitionSubjectType.STRUCTURE, defId);

      assertTrue(result.isEmpty());
      verify(structureRepo).findAllByDefinitionIdOrderByTimestampDesc(defId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findAllFiltered_delegatesToHandlerRepo() {
      UUID archetypeId = UUID.randomUUID();
      UUID archDefId = UUID.randomUUID();
      Map<String, String> filters = Map.of("purpose", "compliance");
      Pageable pageable = PageRequest.of(0, 10);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      DefinitionEntity archDef = mock(DefinitionEntity.class);
      when(archDef.getId()).thenReturn(archDefId);
      when(archetype.getDefinition()).thenReturn(archDef);

      // Schema with $gsm:queryable on "purpose"
      ObjectNode schema = MAPPER.createObjectNode();
      ObjectNode props = schema.putObject("properties");
      props.putObject("purpose").put("type", "string").put("$gsm:queryable", true);
      when(archetype.getStatement()).thenReturn(schema);

      when(archetypeService.findEntityById(archetypeId)).thenReturn(archetype);
      when(structureRepo.findAll(any(Specification.class), eq(pageable))).thenReturn(Page.empty());

      Page<? extends AscriptionEntity> result =
          service.findAllFiltered(
              DefinitionSubjectType.STRUCTURE,
              archetypeId.toString(),
              filters,
              AscriptionStatusType.ACTIVE,
              pageable);

      assertTrue(result.isEmpty());
      verify(structureRepo).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void getHistory_delegatesToFindAllByDefinitionId() {
      UUID defId = UUID.randomUUID();
      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(structureRepo.findAllByDefinitionIdOrderByTimestampDesc(defId))
          .thenReturn(List.of(entity));

      List<AscriptionEntity> result = service.getHistory(DefinitionSubjectType.STRUCTURE, defId);

      assertEquals(1, result.size());
      assertEquals(entity, result.get(0));
    }
  }

  // ========================================================================
  // extractRequiredUuid
  // ========================================================================

  @Nested
  class ExtractRequiredUuidTests {

    @Test
    void valid_returnsUuid() {
      UUID expected = UUID.randomUUID();
      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("field", expected.toString());

      UUID result = AscriptionService.extractRequiredUuid(statement, "field", RULE);

      assertEquals(expected, result);
    }

    @Test
    void missingField_throws() {
      ObjectNode statement = MAPPER.createObjectNode();

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> AscriptionService.extractRequiredUuid(statement, "field", RULE));
      assertTrue(ex.getMessage().contains("Required field"));
    }

    @Test
    void nullField_throws() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.putNull("field");

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> AscriptionService.extractRequiredUuid(statement, "field", RULE));
      assertTrue(ex.getMessage().contains("Required field"));
    }

    @Test
    void blankField_throws() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("field", "   ");

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> AscriptionService.extractRequiredUuid(statement, "field", RULE));
      assertTrue(ex.getMessage().contains("Required field"));
    }

    @Test
    void invalidUuid_throws() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("field", "not-a-uuid");

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> AscriptionService.extractRequiredUuid(statement, "field", RULE));
      assertTrue(ex.getMessage().contains("Invalid UUID"));
    }
  }

  // ========================================================================
  // extractUuidList
  // ========================================================================

  @Nested
  class ExtractUuidListTests {

    @Test
    void validArray_returnsUuids() {
      UUID id1 = UUID.randomUUID();
      UUID id2 = UUID.randomUUID();
      ObjectNode statement = MAPPER.createObjectNode();
      statement.putArray("ids").add(id1.toString()).add(id2.toString());

      List<UUID> result = AscriptionService.extractUuidList(statement, "ids", RULE);

      assertEquals(2, result.size());
      assertEquals(id1, result.get(0));
      assertEquals(id2, result.get(1));
    }

    @Test
    void missingField_returnsEmpty() {
      ObjectNode statement = MAPPER.createObjectNode();

      List<UUID> result = AscriptionService.extractUuidList(statement, "ids", RULE);

      assertTrue(result.isEmpty());
    }

    @Test
    void nullField_returnsEmpty() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.putNull("ids");

      List<UUID> result = AscriptionService.extractUuidList(statement, "ids", RULE);

      assertTrue(result.isEmpty());
    }

    @Test
    void notArray_returnsEmpty() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.put("ids", "not-an-array");

      List<UUID> result = AscriptionService.extractUuidList(statement, "ids", RULE);

      assertTrue(result.isEmpty());
    }

    @Test
    void invalidElement_throws() {
      ObjectNode statement = MAPPER.createObjectNode();
      statement.putArray("ids").add("not-a-uuid");

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> AscriptionService.extractUuidList(statement, "ids", RULE));
      assertTrue(ex.getMessage().contains("Invalid UUID"));
    }
  }

  // ========================================================================
  // buildFilterSpec
  // ========================================================================

  @Nested
  class BuildFilterSpecTests {

    @Test
    @SuppressWarnings("unchecked")
    void withStatusAndFilters_buildsPredicates() {
      UUID archDefId = UUID.randomUUID();
      Map<String, String> filters = Map.of("purpose", "compliance");

      Specification<AscriptionEntity> spec =
          AscriptionService.buildFilterSpec(archDefId, filters, AscriptionStatusType.ACTIVE);

      Root<AscriptionEntity> root = mock(Root.class);
      CriteriaQuery<?> query = mock(CriteriaQuery.class);
      CriteriaBuilder cb = mock(CriteriaBuilder.class);

      Path<Object> archPath = mock(Path.class);
      Path<Object> defPath = mock(Path.class);
      Path<Object> idPath = mock(Path.class);
      when(root.get("archetype")).thenReturn(archPath);
      when(archPath.get("definition")).thenReturn(defPath);
      when(defPath.get("id")).thenReturn(idPath);

      Path<Object> statusPath = mock(Path.class);
      when(root.get("status")).thenReturn(statusPath);

      Path<Object> stmtPath = mock(Path.class);
      when(root.get("statement")).thenReturn(stmtPath);

      Predicate archPred = mock(Predicate.class);
      Predicate statusPred = mock(Predicate.class);
      Predicate jsonbPred = mock(Predicate.class);
      Predicate combined = mock(Predicate.class);
      when(cb.equal(idPath, archDefId)).thenReturn(archPred);
      when(cb.equal(statusPath, AscriptionStatusType.ACTIVE)).thenReturn(statusPred);
      Expression<String> jsonExpr = mock(Expression.class);
      when(cb.function(eq("jsonb_extract_path_text"), eq(String.class), eq(stmtPath), any()))
          .thenReturn(jsonExpr);
      when(cb.equal(jsonExpr, "compliance")).thenReturn(jsonbPred);
      when(cb.and(any(Predicate[].class))).thenReturn(combined);

      Predicate result = spec.toPredicate(root, query, cb);

      assertEquals(combined, result);
      verify(cb).equal(idPath, archDefId);
      verify(cb).equal(statusPath, AscriptionStatusType.ACTIVE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutStatus_omitsStatusPredicate() {
      UUID archDefId = UUID.randomUUID();
      Map<String, String> filters = Map.of();

      Specification<AscriptionEntity> spec =
          AscriptionService.buildFilterSpec(archDefId, filters, null);

      Root<AscriptionEntity> root = mock(Root.class);
      CriteriaQuery<?> query = mock(CriteriaQuery.class);
      CriteriaBuilder cb = mock(CriteriaBuilder.class);

      Path<Object> archPath = mock(Path.class);
      Path<Object> defPath = mock(Path.class);
      Path<Object> idPath = mock(Path.class);
      when(root.get("archetype")).thenReturn(archPath);
      when(archPath.get("definition")).thenReturn(defPath);
      when(defPath.get("id")).thenReturn(idPath);

      Predicate archPred = mock(Predicate.class);
      Predicate combined = mock(Predicate.class);
      when(cb.equal(idPath, archDefId)).thenReturn(archPred);
      when(cb.and(any(Predicate[].class))).thenReturn(combined);

      spec.toPredicate(root, query, cb);

      verify(root, never()).get("status");
    }
  }

  // ========================================================================
  // validatePropertyUniquenessAcrossDefinitions
  // ========================================================================

  @Nested
  class ValidatePropertyUniqueness {

    @Test
    void unique_passes() {
      UUID thisDefId = UUID.randomUUID();

      AscriptionEntity sibling = mock(AscriptionEntity.class);
      DefinitionEntity sibDef = mock(DefinitionEntity.class);
      when(sibDef.getId()).thenReturn(UUID.randomUUID());
      when(sibling.getDefinition()).thenReturn(sibDef);
      ObjectNode sibStmt = MAPPER.createObjectNode();
      sibStmt.put("purpose", "other-purpose");
      when(sibling.getStatement()).thenReturn(sibStmt);

      assertDoesNotThrow(
          () ->
              AscriptionService.validatePropertyUniquenessAcrossDefinitions(
                  DefinitionSubjectType.STRUCTURE,
                  "purpose",
                  "compliance",
                  thisDefId,
                  List.of(sibling)));
    }

    @Test
    void duplicate_throws() {
      UUID thisDefId = UUID.randomUUID();

      AscriptionEntity sibling = mock(AscriptionEntity.class);
      DefinitionEntity sibDef = mock(DefinitionEntity.class);
      when(sibDef.getId()).thenReturn(UUID.randomUUID());
      when(sibling.getDefinition()).thenReturn(sibDef);
      ObjectNode sibStmt = MAPPER.createObjectNode();
      sibStmt.put("purpose", "compliance");
      when(sibling.getStatement()).thenReturn(sibStmt);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () ->
                  AscriptionService.validatePropertyUniquenessAcrossDefinitions(
                      DefinitionSubjectType.STRUCTURE,
                      "purpose",
                      "compliance",
                      thisDefId,
                      List.of(sibling)));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
          ex.getRuleType());
    }

    @Test
    void sameDefinition_skipped() {
      UUID thisDefId = UUID.randomUUID();

      AscriptionEntity sibling = mock(AscriptionEntity.class);
      DefinitionEntity sibDef = mock(DefinitionEntity.class);
      when(sibDef.getId()).thenReturn(thisDefId);
      when(sibling.getDefinition()).thenReturn(sibDef);
      ObjectNode sibStmt = MAPPER.createObjectNode();
      sibStmt.put("purpose", "compliance");
      when(sibling.getStatement()).thenReturn(sibStmt);

      assertDoesNotThrow(
          () ->
              AscriptionService.validatePropertyUniquenessAcrossDefinitions(
                  DefinitionSubjectType.STRUCTURE,
                  "purpose",
                  "compliance",
                  thisDefId,
                  List.of(sibling)));
    }
  }

  // ========================================================================
  // SubtypeHandler defaults
  // ========================================================================

  @Nested
  class SubtypeHandlerDefaults {

    private final SubtypeHandler<AscriptionEntity> defaults =
        new SubtypeHandler<>() {
          @Override
          public DefinitionSubjectType getSubjectType() {
            return DefinitionSubjectType.STRUCTURE;
          }

          @Override
          public AbstractAscriptionRepository<AscriptionEntity> getRepository() {
            return null;
          }

          @Override
          public AscriptionEntity buildEntity(
              DefinitionEntity def,
              ArchetypeEntity arch,
              com.fasterxml.jackson.databind.JsonNode stmt) {
            return null;
          }

          @Override
          public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
            return Map.of();
          }

          @Override
          public List<Map.Entry<AscriptionEntity, String>> getRefereeReferences(
              AscriptionEntity entity) {
            return List.of();
          }

          @Override
          public Map<
                  DefinitionSubjectType,
                  cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType>
              getCascadeTargetRoles() {
            return Map.of();
          }

          @Override
          public List<? extends AscriptionEntity> findCascadeTargetsFrom(
              DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
            return List.of();
          }
        };

    @Test
    void onActivation_defaultNoOp() {
      AscriptionEntity entity = mock(AscriptionEntity.class);
      assertDoesNotThrow(() -> defaults.onActivation(entity));
    }

    @Test
    void onDeactivation_defaultNoOp() {
      AscriptionEntity entity = mock(AscriptionEntity.class);
      assertDoesNotThrow(() -> defaults.onDeactivation(entity));
    }

    @Test
    void afterCreate_defaultNoOp() {
      AscriptionEntity entity = mock(AscriptionEntity.class);
      assertDoesNotThrow(() -> defaults.afterCreate(entity));
    }

    @Test
    void validateActivationUniqueness_defaultNoOp() {
      AscriptionEntity entity = mock(AscriptionEntity.class);
      assertDoesNotThrow(() -> defaults.validateActivationUniqueness(entity));
    }

    @Test
    void statementValidationRule_defaultReturnsGsmArchetypeCompliance() {
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          defaults.statementValidationRule());
    }
  }
}
