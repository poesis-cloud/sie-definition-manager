package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AbstractAscriptionServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private DefinitionService definitionService;

  @Mock private AscriptionStateMachineService stateMachine;

  @Mock private AscriptionStatementValidationService ascriptionStatementValidationService;

  @Mock private EntityManager entityManager;

  /** Minimal concrete subclass for testing package-private base methods. */
  private AbstractAscriptionService<AscriptionEntity> service;

  // Configurable responses for abstract methods
  private List<AscriptionEntity> existingAscriptions = List.of();
  private Map<String, Object> identityBoundValues = Map.of();
  private List<Map.Entry<AscriptionEntity, String>> refereeReferences = List.of();

  @BeforeEach
  void setUp() {
    service =
        new AbstractAscriptionService<>(
            definitionService, stateMachine, ascriptionStatementValidationService, entityManager) {
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
          public List<Map.Entry<AscriptionEntity, String>> getRefereeReferences(
              AscriptionEntity entity) {
            return refereeReferences;
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
              stateMachine,
              ascriptionStatementValidationService,
              entityManager) {
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

            @Override
            public List<Map.Entry<AscriptionEntity, String>> getRefereeReferences(
                AscriptionEntity e) {
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

      RuleViolationException ex =
          assertThrows(
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
    void setUpRealStateMachine() {
      AscriptionStatusTransitionService transitionService =
          new AscriptionStatusTransitionService(
              mock(cloud.poesis.sie.defman.repository.AscriptionStatusTransitionRepository.class),
              entityManager);
      AscriptionStateMachineService realStateMachine =
          new AscriptionStateMachineService(transitionService);
      org.springframework.test.util.ReflectionTestUtils.setField(
          service, "stateMachine", realStateMachine);
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
      refereeReferences = List.of(Map.entry(ref, "structure"));

      AscriptionEntity entity = mock(AscriptionEntity.class);

      assertDoesNotThrow(() -> service.validateCreationPreconditions(entity));
    }

    @Test
    void referenceInDraft_passes() {
      AscriptionEntity ref = mock(AscriptionEntity.class);
      when(ref.getStatus()).thenReturn(AscriptionStatusType.DRAFT);
      when(ref.getId()).thenReturn(UUID.randomUUID());
      refereeReferences = List.of(Map.entry(ref, "structure"));

      AscriptionEntity entity = mock(AscriptionEntity.class);

      assertDoesNotThrow(() -> service.validateCreationPreconditions(entity));
    }

    @Test
    void referenceInProposed_passes() {
      AscriptionEntity ref = mock(AscriptionEntity.class);
      when(ref.getStatus()).thenReturn(AscriptionStatusType.PROPOSED);
      when(ref.getId()).thenReturn(UUID.randomUUID());
      refereeReferences = List.of(Map.entry(ref, "structure"));

      AscriptionEntity entity = mock(AscriptionEntity.class);

      assertDoesNotThrow(() -> service.validateCreationPreconditions(entity));
    }

    @Test
    void referenceInApproved_passes() {
      AscriptionEntity ref = mock(AscriptionEntity.class);
      when(ref.getStatus()).thenReturn(AscriptionStatusType.APPROVED);
      when(ref.getId()).thenReturn(UUID.randomUUID());
      refereeReferences = List.of(Map.entry(ref, "structure"));

      AscriptionEntity entity = mock(AscriptionEntity.class);

      assertDoesNotThrow(() -> service.validateCreationPreconditions(entity));
    }

    @Test
    void referenceInRetired_rejected() {
      AscriptionEntity ref = mock(AscriptionEntity.class);
      when(ref.getStatus()).thenReturn(AscriptionStatusType.RETIRED);
      when(ref.getId()).thenReturn(UUID.randomUUID());
      refereeReferences = List.of(Map.entry(ref, "structure"));

      AscriptionEntity entity = mock(AscriptionEntity.class);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateCreationPreconditions(entity));
      assertEquals(
          AscriptionStatusTransitionRuleType
              .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Referee"));
      assertTrue(ex.getMessage().contains("RETIRED"));
    }

    @Test
    void referenceInSuspended_rejected() {
      AscriptionEntity ref = mock(AscriptionEntity.class);
      when(ref.getStatus()).thenReturn(AscriptionStatusType.SUSPENDED);
      when(ref.getId()).thenReturn(UUID.randomUUID());
      refereeReferences = List.of(Map.entry(ref, "structure"));

      AscriptionEntity entity = mock(AscriptionEntity.class);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateCreationPreconditions(entity));
      assertEquals(
          AscriptionStatusTransitionRuleType
              .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Referee"));
    }

    @Test
    void referenceInDeprecated_rejected() {
      AscriptionEntity ref = mock(AscriptionEntity.class);
      when(ref.getStatus()).thenReturn(AscriptionStatusType.DEPRECATED);
      when(ref.getId()).thenReturn(UUID.randomUUID());
      refereeReferences = List.of(Map.entry(ref, "structure"));

      AscriptionEntity entity = mock(AscriptionEntity.class);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateCreationPreconditions(entity));
      assertEquals(
          AscriptionStatusTransitionRuleType
              .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Referee"));
    }

    @Test
    void referenceInAbandoned_rejected() {
      AscriptionEntity ref = mock(AscriptionEntity.class);
      when(ref.getStatus()).thenReturn(AscriptionStatusType.ABANDONED);
      when(ref.getId()).thenReturn(UUID.randomUUID());
      refereeReferences = List.of(Map.entry(ref, "structure"));

      AscriptionEntity entity = mock(AscriptionEntity.class);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateCreationPreconditions(entity));
      assertEquals(
          AscriptionStatusTransitionRuleType
              .ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("Referee"));
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
              stateMachine,
              ascriptionStatementValidationService,
              entityManager) {
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
            public List<Map.Entry<AscriptionEntity, String>> getRefereeReferences(
                AscriptionEntity e) {
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

      AscriptionEntity result = createService.create(archetypeRef, statement, defId);

      assertEquals(entity, result);
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

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

    @Mock private AbstractAscriptionRepository<AscriptionEntity> mockRepo;

    private AbstractAscriptionService<AscriptionEntity> repoService;

    @BeforeEach
    void setUpRepoService() {
      repoService =
          new AbstractAscriptionService<>(
              definitionService,
              stateMachine,
              ascriptionStatementValidationService,
              entityManager) {
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

            @Override
            public Map<String, Object> getIdentityBoundValues(AscriptionEntity e) {
              return Map.of();
            }

            @Override
            public List<Map.Entry<AscriptionEntity, String>> getRefereeReferences(
                AscriptionEntity e) {
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

      Page<AscriptionEntity> result =
          repoService.findAllByStatus(AscriptionStatusType.ACTIVE, pageable);

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
      Collection<AscriptionStatusType> statuses =
          List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);
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

      Page<AscriptionEntity> result =
          repoService.findAllFiltered(archDefId, filters, AscriptionStatusType.ACTIVE, pageable);

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

      Page<AscriptionEntity> result =
          repoService.findAllFiltered(archDefId, filters, null, pageable);

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
      defaultService =
          new AbstractAscriptionService<>(
              definitionService,
              stateMachine,
              ascriptionStatementValidationService,
              entityManager) {
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
            public Map<String, Object> getIdentityBoundValues(AscriptionEntity e) {
              return Map.of();
            }

            @Override
            public List<Map.Entry<AscriptionEntity, String>> getRefereeReferences(
                AscriptionEntity e) {
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
  // BuildFilterSpec — predicate construction
  // ========================================================================

  @Nested
  class BuildFilterSpec {

    @Test
    @SuppressWarnings("unchecked") // JPA Criteria API generics
    void specWithStatusAndFilters_buildsPredicates() {
      UUID archDefId = UUID.randomUUID();
      Map<String, String> filters = Map.of("purpose", "compliance");

      Specification<AscriptionEntity> spec =
          AbstractAscriptionService.buildFilterSpec(
              archDefId, filters, AscriptionStatusType.ACTIVE);

      jakarta.persistence.criteria.Root<AscriptionEntity> root =
          mock(jakarta.persistence.criteria.Root.class);
      jakarta.persistence.criteria.CriteriaQuery<?> query =
          mock(jakarta.persistence.criteria.CriteriaQuery.class);
      jakarta.persistence.criteria.CriteriaBuilder cb =
          mock(jakarta.persistence.criteria.CriteriaBuilder.class);

      jakarta.persistence.criteria.Path<Object> archPath =
          mock(jakarta.persistence.criteria.Path.class);
      jakarta.persistence.criteria.Path<Object> defPath =
          mock(jakarta.persistence.criteria.Path.class);
      jakarta.persistence.criteria.Path<Object> idPath =
          mock(jakarta.persistence.criteria.Path.class);
      when(root.get("archetype")).thenReturn(archPath);
      when(archPath.get("definition")).thenReturn(defPath);
      when(defPath.get("id")).thenReturn(idPath);

      jakarta.persistence.criteria.Path<Object> statusPath =
          mock(jakarta.persistence.criteria.Path.class);
      when(root.get("status")).thenReturn(statusPath);

      jakarta.persistence.criteria.Path<Object> stmtPath =
          mock(jakarta.persistence.criteria.Path.class);
      when(root.get("statement")).thenReturn(stmtPath);

      jakarta.persistence.criteria.Predicate archPred =
          mock(jakarta.persistence.criteria.Predicate.class);
      jakarta.persistence.criteria.Predicate statusPred =
          mock(jakarta.persistence.criteria.Predicate.class);
      jakarta.persistence.criteria.Predicate jsonbPred =
          mock(jakarta.persistence.criteria.Predicate.class);
      jakarta.persistence.criteria.Predicate combined =
          mock(jakarta.persistence.criteria.Predicate.class);
      when(cb.equal(idPath, archDefId)).thenReturn(archPred);
      when(cb.equal(statusPath, AscriptionStatusType.ACTIVE)).thenReturn(statusPred);
      jakarta.persistence.criteria.Expression<String> jsonExpr =
          mock(jakarta.persistence.criteria.Expression.class);
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

      Specification<AscriptionEntity> spec =
          AbstractAscriptionService.buildFilterSpec(archDefId, filters, null);

      jakarta.persistence.criteria.Root<AscriptionEntity> root =
          mock(jakarta.persistence.criteria.Root.class);
      jakarta.persistence.criteria.CriteriaQuery<?> query =
          mock(jakarta.persistence.criteria.CriteriaQuery.class);
      jakarta.persistence.criteria.CriteriaBuilder cb =
          mock(jakarta.persistence.criteria.CriteriaBuilder.class);

      jakarta.persistence.criteria.Path<Object> archPath =
          mock(jakarta.persistence.criteria.Path.class);
      jakarta.persistence.criteria.Path<Object> defPath =
          mock(jakarta.persistence.criteria.Path.class);
      jakarta.persistence.criteria.Path<Object> idPath =
          mock(jakarta.persistence.criteria.Path.class);
      when(root.get("archetype")).thenReturn(archPath);
      when(archPath.get("definition")).thenReturn(defPath);
      when(defPath.get("id")).thenReturn(idPath);

      jakarta.persistence.criteria.Predicate archPred =
          mock(jakarta.persistence.criteria.Predicate.class);
      jakarta.persistence.criteria.Predicate combined =
          mock(jakarta.persistence.criteria.Predicate.class);
      when(cb.equal(idPath, archDefId)).thenReturn(archPred);
      when(cb.and(any(jakarta.persistence.criteria.Predicate[].class))).thenReturn(combined);

      spec.toPredicate(root, query, cb);

      verify(root, never()).get("status");
    }
  }
}
