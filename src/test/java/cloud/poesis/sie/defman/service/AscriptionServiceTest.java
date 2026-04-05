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
import org.mockito.ArgumentCaptor;
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
  @Mock private AscriptionParsingValidationService statementValidation;
  @Mock private AscriptionIdentityBoundValidationService identityBoundValidation;
  @Mock private AscriptionUniquenessValidationService uniquenessValidation;
  @Mock private AscriptionProtectionService statementProtection;
  @Mock private EntityManager entityManager;

  @SuppressWarnings("unchecked")
  private final AscriptionSubtypeService<AscriptionEntity> structureHandler =
      mock(AscriptionSubtypeService.class);

  private AscriptionService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    when(structureHandler.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
    when(structureHandler.getIdentityBoundValues(any())).thenReturn(Map.of());
    when(structureHandler.getRefereeReferences(any())).thenReturn(List.of());
    when(structureHandler.getCascadeTargetRoles()).thenReturn(Map.of());

    List<AscriptionSubtypeService<?>> handlers = new ArrayList<>();
    handlers.add(structureHandler);
    for (DefinitionSubjectType type : DefinitionSubjectType.values()) {
      if (type == DefinitionSubjectType.STRUCTURE) continue;
      AscriptionSubtypeService<?> h = mock(AscriptionSubtypeService.class);
      when(h.getSubjectType()).thenReturn(type);
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
            identityBoundValidation,
            uniquenessValidation,
            statementProtection,
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
      AscriptionSubtypeService<?> dup1 = mock(AscriptionSubtypeService.class);
      AscriptionSubtypeService<?> dup2 = mock(AscriptionSubtypeService.class);
      when(dup1.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
      when(dup2.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);

      AscriptionService svc =
          new AscriptionService(
              ascriptionRepository,
              archetypeService,
              definitionService,
              stateMachine,
              statementValidation,
              identityBoundValidation,
              uniquenessValidation,
              statementProtection,
              entityManager,
              List.of(dup1, dup2));

      assertThrows(IllegalStateException.class, svc::afterSingletonsInstantiated);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingHandler_throws() {
      AscriptionSubtypeService<?> only = mock(AscriptionSubtypeService.class);
      when(only.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);

      AscriptionService svc =
          new AscriptionService(
              ascriptionRepository,
              archetypeService,
              definitionService,
              stateMachine,
              statementValidation,
              identityBoundValidation,
              uniquenessValidation,
              statementProtection,
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
      when(definitionService.resolve(eq(defId), any())).thenReturn(definition);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetypeService.resolveForCreation(archetypeId))
          .thenReturn(
              new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));
      ObjectNode statement = MAPPER.createObjectNode();

      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(structureHandler.create(definition, archetype, statement)).thenReturn(entity);
      when(structureHandler.save(entity)).thenReturn(entity);
      when(structureHandler.findAllByDefinitionId(defId)).thenReturn(List.of());

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
      when(definitionService.resolve(eq(defId), any())).thenReturn(definition);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetypeService.resolveForCreation(archetypeId))
          .thenReturn(
              new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));
      ObjectNode statement = MAPPER.createObjectNode();

      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(structureHandler.create(definition, archetype, statement)).thenReturn(entity);

      // Simulate identity-bound validation failure via delegated service
      RuleViolationException expected =
          RuleViolationException.of(
              AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION,
              "Identity-bound field 'purpose' differs");
      doThrow(expected).when(identityBoundValidation).validate(structureHandler, entity, archetype);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.create(archetypeId, statement, defId));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION,
          ex.getRuleType());
    }

    @Test
    void creationPreconditionFailure_propagates() {
      UUID defId = UUID.randomUUID();
      UUID archetypeId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);
      when(definitionService.resolve(eq(defId), any())).thenReturn(definition);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetypeService.resolveForCreation(archetypeId))
          .thenReturn(
              new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));
      ObjectNode statement = MAPPER.createObjectNode();

      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(structureHandler.create(definition, archetype, statement)).thenReturn(entity);
      when(structureHandler.findAllByDefinitionId(defId)).thenReturn(List.of());

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
      when(definitionService.resolve(eq(defId), any())).thenReturn(definition);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetypeService.resolveForCreation(archetypeId))
          .thenReturn(
              new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));
      ObjectNode statement = MAPPER.createObjectNode();

      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(structureHandler.create(definition, archetype, statement)).thenReturn(entity);
      when(structureHandler.save(entity)).thenThrow(new DataIntegrityViolationException("dup"));

      assertThrows(InternalException.class, () -> service.create(archetypeId, statement, defId));
    }
  }

  // ========================================================================
  // Generic CRUD delegation
  // ========================================================================

  @Nested
  class GenericCrud {

    @Test
    @SuppressWarnings("unchecked")
    void findAllFiltered_delegatesToHandler() {
      String archetypeTitle = "TestArchetype";
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

      when(archetypeService.findInEffectByTitle(archetypeTitle)).thenReturn(Optional.of(archetype));

      ArgumentCaptor<Specification<AscriptionEntity>> specCaptor =
          ArgumentCaptor.forClass(Specification.class);
      when(structureHandler.findAll(specCaptor.capture(), eq(pageable))).thenReturn(Page.empty());

      Page<? extends AscriptionEntity> result =
          service.findAllFiltered(
              DefinitionSubjectType.STRUCTURE,
              archetypeTitle,
              filters,
              AscriptionStatusType.ACTIVE,
              pageable);

      assertTrue(result.isEmpty());

      // Exercise the captured spec to cover buildFilterSpec internals
      Specification<AscriptionEntity> capturedSpec = specCaptor.getValue();
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

      Predicate predicate = capturedSpec.toPredicate(root, query, cb);

      assertEquals(combined, predicate);
      verify(cb).equal(idPath, archDefId);
      verify(cb).equal(statusPath, AscriptionStatusType.ACTIVE);
    }

    @Test
    void findAllFiltered_nullArchetypeWithFilters_throwsIllegalArgument() {
      Map<String, String> filters = Map.of("purpose", "compliance");
      Pageable pageable = PageRequest.of(0, 10);

      assertThrows(
          IllegalArgumentException.class,
          () ->
              service.findAllFiltered(
                  DefinitionSubjectType.STRUCTURE,
                  null,
                  filters,
                  AscriptionStatusType.ACTIVE,
                  pageable));
    }

    @Test
    void findAllFiltered_unknownArchetypeTitle_throwsIllegalArgument() {
      when(archetypeService.findInEffectByTitle("Unknown")).thenReturn(Optional.empty());
      Pageable pageable = PageRequest.of(0, 10);

      assertThrows(
          IllegalArgumentException.class,
          () ->
              service.findAllFiltered(
                  DefinitionSubjectType.STRUCTURE,
                  "Unknown",
                  Map.of("purpose", "compliance"),
                  AscriptionStatusType.ACTIVE,
                  pageable));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findAllFiltered_archetypeOnlyNoStatementFilters_delegatesToSpec() {
      String archetypeTitle = "TestArchetype";
      UUID archDefId = UUID.randomUUID();
      Pageable pageable = PageRequest.of(0, 10);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      DefinitionEntity archDef = mock(DefinitionEntity.class);
      when(archDef.getId()).thenReturn(archDefId);
      when(archetype.getDefinition()).thenReturn(archDef);

      when(archetypeService.findInEffectByTitle(archetypeTitle)).thenReturn(Optional.of(archetype));
      when(structureHandler.findAll(any(Specification.class), eq(pageable)))
          .thenReturn(Page.empty());

      Page<? extends AscriptionEntity> result =
          service.findAllFiltered(
              DefinitionSubjectType.STRUCTURE, archetypeTitle, Map.of(), null, pageable);

      assertTrue(result.isEmpty());
      verify(structureHandler).findAll(any(Specification.class), eq(pageable));
    }
  }

  // ========================================================================
  // Apply data protection ($gsm:dataProtection)
  // ========================================================================

  @Nested
  class ApplyDataProtection {

    @Test
    void annotatedProperty_callsProtectionService() {
      UUID defId = UUID.randomUUID();
      UUID archetypeId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);
      when(definitionService.resolve(eq(defId), any())).thenReturn(definition);

      // Archetype with $gsm:dataProtection annotation on "ssn"
      ObjectNode archetypeSchema = MAPPER.createObjectNode();
      ObjectNode props = archetypeSchema.putObject("properties");
      ObjectNode ssnProp = props.putObject("ssn");
      ssnProp.put("type", "string");
      ObjectNode dpNode = ssnProp.putObject("$gsm:dataProtection");
      dpNode.put("atRest", "AES256");

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(archetypeSchema);
      when(archetype.getId()).thenReturn(archetypeId);
      when(archetypeService.resolveForCreation(archetypeId))
          .thenReturn(
              new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));

      ObjectNode statement = MAPPER.createObjectNode().put("ssn", "123-45-6789");

      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(entity.getStatement()).thenReturn(statement);
      when(structureHandler.create(definition, archetype, statement)).thenReturn(entity);
      when(structureHandler.save(entity)).thenReturn(entity);
      when(structureHandler.findAllByDefinitionId(defId)).thenReturn(List.of());

      service.create(archetypeId, statement, defId);

      verify(statementProtection).applyAtRestProtection(eq(dpNode), eq("ssn"), eq(statement));
    }

    @Test
    void noAnnotation_doesNotCallProtection() {
      UUID defId = UUID.randomUUID();
      UUID archetypeId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);
      when(definitionService.resolve(eq(defId), any())).thenReturn(definition);

      // Archetype without $gsm:dataProtection
      ObjectNode archetypeSchema = MAPPER.createObjectNode();
      archetypeSchema.putObject("properties").putObject("name").put("type", "string");

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(archetypeSchema);
      when(archetypeService.resolveForCreation(archetypeId))
          .thenReturn(
              new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));

      ObjectNode statement = MAPPER.createObjectNode().put("name", "test");

      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(entity.getStatement()).thenReturn(statement);
      when(structureHandler.create(definition, archetype, statement)).thenReturn(entity);
      when(structureHandler.save(entity)).thenReturn(entity);
      when(structureHandler.findAllByDefinitionId(defId)).thenReturn(List.of());

      service.create(archetypeId, statement, defId);

      verify(statementProtection, org.mockito.Mockito.never())
          .applyAtRestProtection(any(), any(), any());
    }

    @Test
    void nullArchetypeStatement_skips() {
      UUID defId = UUID.randomUUID();
      UUID archetypeId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);
      when(definitionService.resolve(eq(defId), any())).thenReturn(definition);

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(null);
      when(archetypeService.resolveForCreation(archetypeId))
          .thenReturn(
              new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));

      ObjectNode statement = MAPPER.createObjectNode();

      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(entity.getStatement()).thenReturn(statement);
      when(structureHandler.create(definition, archetype, statement)).thenReturn(entity);
      when(structureHandler.save(entity)).thenReturn(entity);

      service.create(archetypeId, statement, defId);

      verify(statementProtection, org.mockito.Mockito.never())
          .applyAtRestProtection(any(), any(), any());
    }

    @Test
    void propertyNotInStatement_skips() {
      UUID defId = UUID.randomUUID();
      UUID archetypeId = UUID.randomUUID();
      DefinitionEntity definition = mock(DefinitionEntity.class);
      when(definition.getId()).thenReturn(defId);
      when(definitionService.resolve(eq(defId), any())).thenReturn(definition);

      ObjectNode archetypeSchema = MAPPER.createObjectNode();
      ObjectNode props = archetypeSchema.putObject("properties");
      ObjectNode ssnProp = props.putObject("ssn");
      ssnProp.put("type", "string");
      ssnProp.putObject("$gsm:dataProtection").put("atRest", "AES256");

      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(archetypeSchema);
      when(archetypeService.resolveForCreation(archetypeId))
          .thenReturn(
              new ArchetypeService.ArchetypeResolution(archetype, DefinitionSubjectType.STRUCTURE));

      ObjectNode statement = MAPPER.createObjectNode(); // no "ssn" field

      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(entity.getDefinition()).thenReturn(definition);
      when(entity.getStatement()).thenReturn(statement);
      when(structureHandler.create(definition, archetype, statement)).thenReturn(entity);
      when(structureHandler.save(entity)).thenReturn(entity);
      when(structureHandler.findAllByDefinitionId(defId)).thenReturn(List.of());

      service.create(archetypeId, statement, defId);

      verify(statementProtection, org.mockito.Mockito.never())
          .applyAtRestProtection(any(), any(), any());
    }
  }

  // ========================================================================
  // SubtypeHandler defaults
  // ========================================================================

  @Nested
  class SubtypeHandlerDefaults {

    @SuppressWarnings("unchecked")
    private final AbstractAscriptionRepository<AscriptionEntity> mockRepo =
        mock(AbstractAscriptionRepository.class);

    private final AscriptionSubtypeService<AscriptionEntity> defaults =
        new AscriptionSubtypeService<>() {
          @Override
          public DefinitionSubjectType getSubjectType() {
            return DefinitionSubjectType.STRUCTURE;
          }

          @Override
          public AbstractAscriptionRepository<AscriptionEntity> getRepository() {
            return mockRepo;
          }

          @Override
          public AscriptionEntity create(
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

    @Test
    void save_delegatesToRepository() {
      AscriptionEntity entity = mock(AscriptionEntity.class);
      when(mockRepo.save(entity)).thenReturn(entity);

      AscriptionEntity result = defaults.save(entity);

      assertEquals(entity, result);
      verify(mockRepo).save(entity);
    }

    @Test
    void findAll_delegatesToRepository() {
      Pageable pageable = PageRequest.of(0, 10);
      when(mockRepo.findAll(pageable)).thenReturn(Page.empty());

      Page<AscriptionEntity> result = defaults.findAll(pageable);

      assertTrue(result.isEmpty());
      verify(mockRepo).findAll(pageable);
    }

    @Test
    void findAllByStatus_delegatesToRepository() {
      Pageable pageable = PageRequest.of(0, 10);
      when(mockRepo.findAllByStatus(AscriptionStatusType.ACTIVE, pageable))
          .thenReturn(Page.empty());

      Page<AscriptionEntity> result =
          defaults.findAllByStatus(AscriptionStatusType.ACTIVE, pageable);

      assertTrue(result.isEmpty());
      verify(mockRepo).findAllByStatus(AscriptionStatusType.ACTIVE, pageable);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findAllBySpec_delegatesToRepository() {
      Pageable pageable = PageRequest.of(0, 10);
      Specification<AscriptionEntity> spec = mock(Specification.class);
      when(mockRepo.findAll(spec, pageable)).thenReturn(Page.empty());

      Page<AscriptionEntity> result = defaults.findAll(spec, pageable);

      assertTrue(result.isEmpty());
      verify(mockRepo).findAll(spec, pageable);
    }

    @Test
    void findAllByDefinitionId_delegatesToRepository() {
      UUID defId = UUID.randomUUID();
      when(mockRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

      List<AscriptionEntity> result = defaults.findAllByDefinitionId(defId);

      assertTrue(result.isEmpty());
      verify(mockRepo).findAllByDefinitionIdOrderByTimestampDesc(defId);
    }

    @Test
    void findAllByDefinitionIdAndStatus_delegatesToRepository() {
      UUID defId = UUID.randomUUID();
      var statuses = List.of(AscriptionStatusType.ACTIVE);
      when(mockRepo.findAllByDefinitionIdAndStatusIn(defId, statuses)).thenReturn(List.of());

      List<AscriptionEntity> result = defaults.findAllByDefinitionIdAndStatus(defId, statuses);

      assertTrue(result.isEmpty());
      verify(mockRepo).findAllByDefinitionIdAndStatusIn(defId, statuses);
    }
  }
}
