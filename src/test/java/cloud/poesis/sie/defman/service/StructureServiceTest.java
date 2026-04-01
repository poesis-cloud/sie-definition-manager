package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.repository.StructureRepository;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.EnumSet;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StructureServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private StructureRepository structureRepo;

  private StructureService service;

  @BeforeEach
  void setUp() {
    service =
        new StructureService(
            structureRepo,
            mock(ArchetypeRepository.class),
            mock(DefinitionService.class),
            mock(AscriptionStatusTransitionService.class),
            mock(AscriptionService.class),
            mock(EntityManager.class),
            mock(DataProtectionService.class));
  }

  // ========================================================================
  // Activation uniqueness: purpose (GSM Structure validation rules)
  // ========================================================================

  @Nested
  class ActivationUniqueness {

    @Test
    void uniquePurpose_valid() {
      StructureEntity entity = stubStructure("order-processing", UUID.randomUUID());

      when(structureRepo.findAllByStatusIn(
              EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
          .thenReturn(List.of());

      assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
    }

    @Test
    void duplicatePurpose_differentDefinition_rejected() {
      UUID thisDefId = UUID.randomUUID();
      UUID otherDefId = UUID.randomUUID();

      StructureEntity entity = stubStructure("order-processing", thisDefId);
      StructureEntity existing = stubStructure("order-processing", otherDefId);

      when(structureRepo.findAllByStatusIn(
              EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
          .thenReturn(List.of(existing));

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class, () -> service.validateActivationUniqueness(entity));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
          ex.getRuleType());
      assertTrue(ex.getMessage().contains("order-processing"));
      assertTrue(ex.getMessage().contains("already in"));
    }

    @Test
    void samePurpose_sameDefinition_valid() {
      UUID defId = UUID.randomUUID();

      StructureEntity entity = stubStructure("order-processing", defId);
      StructureEntity existing = stubStructure("order-processing", defId);

      when(structureRepo.findAllByStatusIn(
              EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
          .thenReturn(List.of(existing));

      assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
    }

    @Test
    void differentPurpose_valid() {
      UUID thisDefId = UUID.randomUUID();
      UUID otherDefId = UUID.randomUUID();

      StructureEntity entity = stubStructure("order-processing", thisDefId);
      StructureEntity existing = stubStructure("payment-service", otherDefId);

      when(structureRepo.findAllByStatusIn(
              EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
          .thenReturn(List.of(existing));

      assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
    }
  }

  // ========================================================================
  // Identity-bound values (Structure)
  // ========================================================================

  @Nested
  class IdentityBound {

    @Test
    void purposeExtracted() {
      StructureEntity entity = stubStructure("order-processing", UUID.randomUUID());
      Map<String, Object> values = service.getIdentityBoundValues(entity);

      assertEquals(Map.of("purpose", "order-processing"), values);
    }

    @Test
    void noPurpose_emptyMap() {
      StructureEntity entity = stubStructureNoPurpose(UUID.randomUUID());
      Map<String, Object> values = service.getIdentityBoundValues(entity);

      assertTrue(values.isEmpty());
    }
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private StructureEntity stubStructure(String purpose, UUID defId) {
    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(defId);

    ObjectNode stmt = MAPPER.createObjectNode();
    stmt.put("purpose", purpose);

    StructureEntity entity = mock(StructureEntity.class);
    when(entity.getId()).thenReturn(UUID.randomUUID());
    when(entity.getDefinition()).thenReturn(def);
    when(entity.getStatement()).thenReturn(stmt);

    return entity;
  }

  private StructureEntity stubStructureNoPurpose(UUID defId) {
    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(defId);

    ObjectNode stmt = MAPPER.createObjectNode();

    StructureEntity entity = mock(StructureEntity.class);
    when(entity.getId()).thenReturn(UUID.randomUUID());
    when(entity.getDefinition()).thenReturn(def);
    when(entity.getStatement()).thenReturn(stmt);

    return entity;
  }

  // ========================================================================
  // BuildEntity
  // ========================================================================

  @Nested
  class BuildEntity {

    @Test
    void returnsEntity() {
      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("purpose", "test-structure");

      StructureEntity result = service.buildEntity(def, archetype, stmt);
      assertEquals(def, result.getDefinition());
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
      StructureEntity entity = mock(StructureEntity.class);
      when(structureRepo.findById(id)).thenReturn(Optional.of(entity));

      assertEquals(entity, service.findEntityById(id));
    }

    @Test
    void notFound_throwsResourceNotFound() {
      UUID id = UUID.randomUUID();
      when(structureRepo.findById(id)).thenReturn(Optional.empty());

      assertThrows(ResourceNotFoundException.class, () -> service.findEntityById(id));
    }
  }

  // ========================================================================
  // GetSubjectType
  // ========================================================================

  @Test
  void getSubjectType_returnsStructure() {
    assertEquals(DefinitionSubjectType.STRUCTURE, service.getSubjectType());
  }
}
