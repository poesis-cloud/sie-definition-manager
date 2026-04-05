package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.DirectiveEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.DirectiveRepository;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    service = new DirectiveService(directiveRepo, structureService, archetypeService);
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
        String purpose = "test-purpose";

        DirectiveEntity entity = stubDirectiveLifecycle(structDefId, qualDefId, purpose);

        var values = service.getIdentityBoundValues(entity);

        assertEquals(structDefId, values.get("structure"));
        assertEquals(qualDefId, values.get("qualifier"));
        assertEquals(purpose, values.get("purpose"));
      }
    }

    @Nested
    class RefereeReferences {

      @Test
      void referencesStructureQualifierPurpose() {
        UUID structDefId = UUID.randomUUID();
        UUID qualDefId = UUID.randomUUID();
        String purpose = "test-purpose";

        DirectiveEntity entity = stubDirectiveLifecycle(structDefId, qualDefId, purpose);

        var refs = service.getRefereeReferences(entity);

        assertEquals(2, refs.size());
        assertTrue(refs.stream().anyMatch(r -> r.getValue().equals("structure")));
        assertTrue(refs.stream().anyMatch(r -> r.getValue().equals("qualifier")));
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
              RuleViolationException.class, () -> service.create(def, archetype, emptyStatement));
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
    String purpose = "test-purpose";

    DefinitionEntity defEntity = mock(DefinitionEntity.class);
    when(defEntity.getId()).thenReturn(definitionId);

    DefinitionEntity qualifierDef = mock(DefinitionEntity.class);
    when(qualifierDef.getId()).thenReturn(qualifierDefId);
    ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
    when(qualifier.getDefinition()).thenReturn(qualifierDef);

    ObjectNode stmt =
        MAPPER.createObjectNode().put("verb", verb).put("modal", modal).put("purpose", purpose);

    DirectiveEntity directive = mock(DirectiveEntity.class);
    when(directive.getDefinition()).thenReturn(defEntity);
    when(directive.getQualifier()).thenReturn(qualifier);
    when(directive.getStatement()).thenReturn(stmt);
    when(directive.getId()).thenReturn(UUID.randomUUID());

    return directive;
  }

  private DirectiveEntity stubDirectiveLifecycle(UUID structDefId, UUID qualDefId, String purpose) {
    StructureEntity structure = mock(StructureEntity.class);
    DefinitionEntity structDef = mock(DefinitionEntity.class);
    when(structDef.getId()).thenReturn(structDefId);
    when(structure.getDefinition()).thenReturn(structDef);

    ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
    DefinitionEntity qualDef = mock(DefinitionEntity.class);
    when(qualDef.getId()).thenReturn(qualDefId);
    when(qualifier.getDefinition()).thenReturn(qualDef);

    ObjectNode stmt = MAPPER.createObjectNode().put("purpose", purpose);

    DirectiveEntity entity = mock(DirectiveEntity.class);
    when(entity.getStructure()).thenReturn(structure);
    when(entity.getQualifier()).thenReturn(qualifier);
    when(entity.getStatement()).thenReturn(stmt);

    return entity;
  }

  // ========================================================================
  // Create
  // ========================================================================

  @Nested
  class Create {

    @Test
    void validStatement_returnsEntity() {
      UUID structId = UUID.randomUUID();
      UUID qualId = UUID.randomUUID();
      String purpose = "payment-processing";

      StructureEntity structure = mock(StructureEntity.class);
      when(structureService.findEntityById(structId)).thenReturn(structure);

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      when(archetypeService.findEntityById(qualId)).thenReturn(qualifier);

      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);

      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("structure", structId.toString());
      stmt.put("qualifier", qualId.toString());
      stmt.put("purpose", purpose);
      stmt.put("modal", "MUST");
      stmt.put("verb", "ENSURE");

      DirectiveEntity result = service.create(def, archetype, stmt);
      assertEquals(def, result.getDefinition());
      assertEquals(structure, result.getStructure());
      assertEquals(qualifier, result.getQualifier());
    }

    @Test
    void missingStructure_rejected() {
      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("qualifier", UUID.randomUUID().toString());
      stmt.put("purpose", "some-purpose");

      assertThrows(RuleViolationException.class, () -> service.create(def, archetype, stmt));
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
      stmt.put("purpose", "some-purpose");

      assertThrows(ResourceNotFoundException.class, () -> service.create(def, archetype, stmt));
    }

    @Test
    void invalidUuidForStructure_rejected() {
      DefinitionEntity def = mock(DefinitionEntity.class);
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode stmt = MAPPER.createObjectNode();
      stmt.put("structure", "not-a-valid-uuid");
      stmt.put("qualifier", UUID.randomUUID().toString());
      stmt.put("purpose", "some-purpose");

      assertThrows(RuleViolationException.class, () -> service.create(def, archetype, stmt));
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
  // FindAllByDefinitionId (exercises getRepository bridge)
  // ========================================================================

  @Nested
  class FindAllByDefinitionIdTests {

    @Test
    void delegatesToRepo() {
      UUID defId = UUID.randomUUID();
      DirectiveEntity d1 = mock(DirectiveEntity.class);
      when(directiveRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of(d1));

      var result = service.findAllByDefinitionId(defId);
      assertEquals(1, result.size());
    }
  }

  // ========================================================================
  // IdentityBound — null purpose branch
  // ========================================================================

  @Nested
  class IdentityBoundNullPurpose {

    @Test
    void noPurposeField_returnsStructureAndQualifierOnly() {
      StructureEntity structure = mock(StructureEntity.class);
      DefinitionEntity structDef = mock(DefinitionEntity.class);
      UUID structDefId = UUID.randomUUID();
      when(structDef.getId()).thenReturn(structDefId);
      when(structure.getDefinition()).thenReturn(structDef);

      ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
      DefinitionEntity qualDef = mock(DefinitionEntity.class);
      UUID qualDefId = UUID.randomUUID();
      when(qualDef.getId()).thenReturn(qualDefId);
      when(qualifier.getDefinition()).thenReturn(qualDef);

      ObjectNode stmt = MAPPER.createObjectNode();

      DirectiveEntity entity = mock(DirectiveEntity.class);
      when(entity.getStructure()).thenReturn(structure);
      when(entity.getQualifier()).thenReturn(qualifier);
      when(entity.getStatement()).thenReturn(stmt);

      var values = service.getIdentityBoundValues(entity);

      assertEquals(2, values.size());
      assertEquals(structDefId, values.get("structure"));
      assertEquals(qualDefId, values.get("qualifier"));
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
