package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AscriptionArchetypeSchemaAnnotationEnforcementServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private AscriptionService ascriptionService;
  @Mock private AscriptionProtectionService protectionService;

  private AscriptionArchetypeSchemaAnnotationEnforcementService service;

  @BeforeEach
  void setUp() {
    service =
        new AscriptionArchetypeSchemaAnnotationEnforcementService(
            ascriptionService, protectionService);
  }

  // ==============================================================
  // enforceAnnotations
  // ==============================================================

  @Nested
  class EnforceAnnotations {

    private final UUID definitionId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private final Function<UUID, List<? extends AscriptionEntity>> finder = mock(Function.class);

    @Test
    void nullArchetypeStatement_returnsImmediately() {
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(null);

      ObjectNode statement = MAPPER.createObjectNode().put("name", "Alice");

      assertDoesNotThrow(
          () -> service.enforceAnnotations(statement, archetype, definitionId, finder));
    }

    @Test
    void noPropertiesKey_returnsImmediately() {
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getStatement()).thenReturn(MAPPER.createObjectNode());

      ObjectNode statement = MAPPER.createObjectNode().put("name", "Alice");

      assertDoesNotThrow(
          () -> service.enforceAnnotations(statement, archetype, definitionId, finder));
    }

    @Test
    void propertiesNotObject_returnsImmediately() {
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode archetypeStmt = MAPPER.createObjectNode();
      archetypeStmt.put("properties", "not-an-object");
      when(archetype.getStatement()).thenReturn(archetypeStmt);

      ObjectNode statement = MAPPER.createObjectNode().put("name", "Alice");

      assertDoesNotThrow(
          () -> service.enforceAnnotations(statement, archetype, definitionId, finder));
    }

    @Test
    void propertyNotInStatement_skipped() {
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode archetypeStmt = MAPPER.createObjectNode();
      ObjectNode props = archetypeStmt.putObject("properties");
      props.putObject("missing").put("$gsm:unique", true);
      when(archetype.getStatement()).thenReturn(archetypeStmt);

      ObjectNode statement = MAPPER.createObjectNode().put("other", "value");

      assertDoesNotThrow(
          () -> service.enforceAnnotations(statement, archetype, definitionId, finder));
    }

    @Test
    void dataProtectionAnnotation_delegatesToProtectionService() {
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode archetypeStmt = MAPPER.createObjectNode();
      ObjectNode props = archetypeStmt.putObject("properties");
      ObjectNode ssnProp = props.putObject("ssn");
      ssnProp.putObject("$gsm:dataProtection").put("measure", "hash");
      when(archetype.getStatement()).thenReturn(archetypeStmt);

      ObjectNode statement = MAPPER.createObjectNode().put("ssn", "123-45-6789");

      service.enforceAnnotations(statement, archetype, definitionId, finder);

      verify(protectionService)
          .applyAtRestProtection(eq(ssnProp.get("$gsm:dataProtection")), eq("ssn"), eq(statement));
    }
  }

  // ==============================================================
  // enforceUnique (via enforceAnnotations)
  // ==============================================================

  @Nested
  class EnforceUnique {

    private final UUID definitionId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private final Function<UUID, List<? extends AscriptionEntity>> finder = mock(Function.class);

    private ArchetypeEntity archetypeWithUnique(String propName) {
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      when(archetype.getId()).thenReturn(UUID.randomUUID());
      ObjectNode archetypeStmt = MAPPER.createObjectNode();
      ObjectNode props = archetypeStmt.putObject("properties");
      props.putObject(propName).put("$gsm:unique", true);
      when(archetype.getStatement()).thenReturn(archetypeStmt);
      return archetype;
    }

    @Test
    void noDuplicates_passes() {
      ArchetypeEntity archetype = archetypeWithUnique("code");
      ObjectNode statement = MAPPER.createObjectNode().put("code", "ABC");

      when(ascriptionService.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
              eq(archetype.getId()),
              eq(EnumSet.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)),
              eq(definitionId)))
          .thenReturn(List.of());

      assertDoesNotThrow(
          () -> service.enforceAnnotations(statement, archetype, definitionId, finder));
    }

    @Test
    void duplicateValue_throws() {
      ArchetypeEntity archetype = archetypeWithUnique("code");
      ObjectNode statement = MAPPER.createObjectNode().put("code", "DUP");

      AscriptionEntity existing = mock(AscriptionEntity.class);
      DefinitionEntity existingDef = mock(DefinitionEntity.class);
      when(existing.getId()).thenReturn(UUID.randomUUID());
      when(existing.getDefinition()).thenReturn(existingDef);
      when(existingDef.getId()).thenReturn(UUID.randomUUID());
      when(existing.getStatement()).thenReturn(MAPPER.createObjectNode().put("code", "DUP"));

      when(ascriptionService.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
              eq(archetype.getId()), any(), eq(definitionId)))
          .thenReturn(List.of(existing));

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.enforceAnnotations(statement, archetype, definitionId, finder));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
          ex.getRuleType());
    }

    @Test
    void differentValue_passes() {
      ArchetypeEntity archetype = archetypeWithUnique("code");
      ObjectNode statement = MAPPER.createObjectNode().put("code", "NEW");

      AscriptionEntity existing = mock(AscriptionEntity.class);
      when(existing.getStatement()).thenReturn(MAPPER.createObjectNode().put("code", "OLD"));

      when(ascriptionService.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
              eq(archetype.getId()), any(), eq(definitionId)))
          .thenReturn(List.of(existing));

      assertDoesNotThrow(
          () -> service.enforceAnnotations(statement, archetype, definitionId, finder));
    }

    @Test
    void existingWithoutProperty_passes() {
      ArchetypeEntity archetype = archetypeWithUnique("code");
      ObjectNode statement = MAPPER.createObjectNode().put("code", "ABC");

      AscriptionEntity existing = mock(AscriptionEntity.class);
      when(existing.getStatement()).thenReturn(MAPPER.createObjectNode());

      when(ascriptionService.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
              eq(archetype.getId()), any(), eq(definitionId)))
          .thenReturn(List.of(existing));

      assertDoesNotThrow(
          () -> service.enforceAnnotations(statement, archetype, definitionId, finder));
    }

    @Test
    void nonTextualValueDuplicate_usesToString() {
      ArchetypeEntity archetype = archetypeWithUnique("count");
      ObjectNode statement = MAPPER.createObjectNode().put("count", 42);

      AscriptionEntity existing = mock(AscriptionEntity.class);
      DefinitionEntity existingDef = mock(DefinitionEntity.class);
      when(existing.getId()).thenReturn(UUID.randomUUID());
      when(existing.getDefinition()).thenReturn(existingDef);
      when(existingDef.getId()).thenReturn(UUID.randomUUID());
      when(existing.getStatement()).thenReturn(MAPPER.createObjectNode().put("count", 42));

      when(ascriptionService.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
              eq(archetype.getId()), any(), eq(definitionId)))
          .thenReturn(List.of(existing));

      assertThrows(
          RuleViolationException.class,
          () -> service.enforceAnnotations(statement, archetype, definitionId, finder));
    }
  }

  // ==============================================================
  // enforceStatementIdentityBound (via enforceAnnotations)
  // ==============================================================

  @Nested
  class EnforceIdentityBound {

    private final UUID definitionId = UUID.randomUUID();

    private ArchetypeEntity archetypeWithIdentityBound(String propName) {
      ArchetypeEntity archetype = mock(ArchetypeEntity.class);
      ObjectNode archetypeStmt = MAPPER.createObjectNode();
      ObjectNode props = archetypeStmt.putObject("properties");
      props.putObject(propName).put("$gsm:identityBound", true);
      when(archetype.getStatement()).thenReturn(archetypeStmt);
      return archetype;
    }

    @Test
    void noExisting_passes() {
      ArchetypeEntity archetype = archetypeWithIdentityBound("name");
      ObjectNode statement = MAPPER.createObjectNode().put("name", "Alice");

      @SuppressWarnings("unchecked")
      Function<UUID, List<? extends AscriptionEntity>> finder = mock(Function.class);
      doReturn(List.of()).when(finder).apply(definitionId);

      assertDoesNotThrow(
          () -> service.enforceAnnotations(statement, archetype, definitionId, finder));
    }

    @Test
    void sameValue_passes() {
      ArchetypeEntity archetype = archetypeWithIdentityBound("name");
      ObjectNode statement = MAPPER.createObjectNode().put("name", "Alice");

      AscriptionEntity first = mock(AscriptionEntity.class);
      when(first.getStatement()).thenReturn(MAPPER.createObjectNode().put("name", "Alice"));

      @SuppressWarnings("unchecked")
      Function<UUID, List<? extends AscriptionEntity>> finder = mock(Function.class);
      doReturn(List.of(first)).when(finder).apply(definitionId);

      assertDoesNotThrow(
          () -> service.enforceAnnotations(statement, archetype, definitionId, finder));
    }

    @Test
    void changedValue_throws() {
      ArchetypeEntity archetype = archetypeWithIdentityBound("name");
      ObjectNode statement = MAPPER.createObjectNode().put("name", "Bob");

      AscriptionEntity first = mock(AscriptionEntity.class);
      when(first.getStatement()).thenReturn(MAPPER.createObjectNode().put("name", "Alice"));

      @SuppressWarnings("unchecked")
      Function<UUID, List<? extends AscriptionEntity>> finder = mock(Function.class);
      doReturn(List.of(first)).when(finder).apply(definitionId);

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.enforceAnnotations(statement, archetype, definitionId, finder));
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION,
          ex.getRuleType());
    }

    @Test
    void existingLacksProperty_passes() {
      ArchetypeEntity archetype = archetypeWithIdentityBound("name");
      ObjectNode statement = MAPPER.createObjectNode().put("name", "Alice");

      AscriptionEntity first = mock(AscriptionEntity.class);
      when(first.getStatement()).thenReturn(MAPPER.createObjectNode());

      @SuppressWarnings("unchecked")
      Function<UUID, List<? extends AscriptionEntity>> finder = mock(Function.class);
      doReturn(List.of(first)).when(finder).apply(definitionId);

      assertDoesNotThrow(
          () -> service.enforceAnnotations(statement, archetype, definitionId, finder));
    }

    @Test
    void nonTextualValues_comparesToString() {
      ArchetypeEntity archetype = archetypeWithIdentityBound("priority");
      ObjectNode statement = MAPPER.createObjectNode().put("priority", 2);

      AscriptionEntity first = mock(AscriptionEntity.class);
      when(first.getStatement()).thenReturn(MAPPER.createObjectNode().put("priority", 1));

      @SuppressWarnings("unchecked")
      Function<UUID, List<? extends AscriptionEntity>> finder = mock(Function.class);
      doReturn(List.of(first)).when(finder).apply(definitionId);

      assertThrows(
          RuleViolationException.class,
          () -> service.enforceAnnotations(statement, archetype, definitionId, finder));
    }
  }
}
