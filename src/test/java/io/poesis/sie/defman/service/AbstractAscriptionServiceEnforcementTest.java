package io.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.poesis.sie.defman.entity.ArchetypeEntity;
import io.poesis.sie.defman.entity.AscriptionEntity;
import io.poesis.sie.defman.entity.DefinitionEntity;
import io.poesis.sie.defman.repository.AscriptionRepository;
import io.poesis.sie.defman.repository.DefinitionRepository;
import io.poesis.sie.defman.service.AbstractAscriptionService.RefereeReference;
import io.poesis.sie.defman.type.AscriptionStatusType;
import io.poesis.sie.defman.type.DefinitionSubjectType;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AbstractAscriptionServiceEnforcementTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private DefinitionRepository definitionRepo;

    @Mock
    private AscriptionRepository ascriptionRepo;

    /** Minimal concrete subclass for testing package-private base methods. */
    private AbstractAscriptionService service;

    // Configurable responses for abstract methods
    private List<AscriptionEntity> existingAscriptions = List.of();
    private Map<String, Object> identityBoundValues = Map.of();
    private List<RefereeReference> refereeReferences = List.of();

    @BeforeEach
    void setUp() {
        service = new AbstractAscriptionService() {
            @Override
            public DefinitionSubjectType getSubjectType() {
                return DefinitionSubjectType.STRUCTURE;
            }

            @Override
            public AscriptionEntity buildEntity(DefinitionEntity def, ArchetypeEntity arch, JsonNode stmt) {
                return null;
            }

            @Override
            public AscriptionEntity save(AscriptionEntity entity) {
                return null;
            }

            @Override
            public Page<? extends AscriptionEntity> findAll(Pageable pageable) {
                return null;
            }

            @Override
            public Page<? extends AscriptionEntity> findAllByStatus(AscriptionStatusType s, Pageable p) {
                return null;
            }

            @Override
            public List<? extends AscriptionEntity> findAllByDefinitionId(UUID id) {
                return existingAscriptions;
            }

            @Override
            public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(UUID id,
                    Collection<AscriptionStatusType> s) {
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
        ReflectionTestUtils.setField(service, "definitionRepository", definitionRepo);
        ReflectionTestUtils.setField(service, "ascriptionRepository", ascriptionRepo);
    }

    // ========================================================================
    // $gsm:referential enforcement
    // ========================================================================

    @Nested
    class EnforceReferential {

        @Test
        void referentialPropertyExists_valid() {
            UUID refId = UUID.randomUUID();
            DefinitionEntity refDef = mock(DefinitionEntity.class);
            when(refDef.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
            when(definitionRepo.findById(refId)).thenReturn(Optional.of(refDef));

            ObjectNode ann = MAPPER.createObjectNode().put("subjectType", "STRUCTURE");
            ObjectNode propNode = prop("string");
            propNode.set("$gsm:referential", ann);
            ObjectNode archetypeSchema = schemaWithProperty("ownerId", propNode);

            ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
            ObjectNode statement = MAPPER.createObjectNode().put("ownerId", refId.toString());

            assertDoesNotThrow(() -> service.enforceGsmAnnotations(statement, archetype, UUID.randomUUID()));
        }

        @Test
        void referentialPropertyMissing_rejected() {
            UUID refId = UUID.randomUUID();
            when(definitionRepo.findById(refId)).thenReturn(Optional.empty());

            ObjectNode ann = MAPPER.createObjectNode();
            ObjectNode propNode = prop("string");
            propNode.set("$gsm:referential", ann);
            ObjectNode archetypeSchema = schemaWithProperty("ownerId", propNode);

            ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
            ObjectNode statement = MAPPER.createObjectNode().put("ownerId", refId.toString());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.enforceGsmAnnotations(statement, archetype, UUID.randomUUID()));
            assertTrue(ex.getMessage().contains("does not exist"));
        }

        @Test
        void referentialPropertyWrongSubjectType_rejected() {
            UUID refId = UUID.randomUUID();
            DefinitionEntity refDef = mock(DefinitionEntity.class);
            when(refDef.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
            when(definitionRepo.findById(refId)).thenReturn(Optional.of(refDef));

            ObjectNode ann = MAPPER.createObjectNode().put("subjectType", "STRUCTURE");
            ObjectNode propNode = prop("string");
            propNode.set("$gsm:referential", ann);
            ObjectNode archetypeSchema = schemaWithProperty("ownerId", propNode);

            ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
            ObjectNode statement = MAPPER.createObjectNode().put("ownerId", refId.toString());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.enforceGsmAnnotations(statement, archetype, UUID.randomUUID()));
            assertTrue(ex.getMessage().contains("subjectType"));
        }

        @Test
        void referentialPropertyNotUuid_rejected() {
            ObjectNode ann = MAPPER.createObjectNode();
            ObjectNode propNode = prop("string");
            propNode.set("$gsm:referential", ann);
            ObjectNode archetypeSchema = schemaWithProperty("ref", propNode);

            ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
            ObjectNode statement = MAPPER.createObjectNode().put("ref", "not-a-uuid");

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.enforceGsmAnnotations(statement, archetype, UUID.randomUUID()));
            assertTrue(ex.getMessage().contains("not a valid UUID"));
        }
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
            when(ascriptionRepo.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
                    any(), any(), eq(defId))).thenReturn(List.of());

            assertDoesNotThrow(() -> service.enforceGsmAnnotations(statement, archetype, defId));
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
            when(existing.getStatement()).thenReturn(
                    MAPPER.createObjectNode().put("code", "ALPHA"));

            when(ascriptionRepo.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
                    any(), any(), eq(defId))).thenReturn(List.of(existing));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.enforceGsmAnnotations(statement, archetype, defId));
            assertTrue(ex.getMessage().contains("$gsm:unique"));
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
            when(existing.getStatement()).thenReturn(
                    MAPPER.createObjectNode().put("code", "BETA"));

            when(ascriptionRepo.findAllByArchetypeIdAndStatusInAndDefinitionIdNot(
                    any(), any(), eq(defId))).thenReturn(List.of(existing));

            assertDoesNotThrow(() -> service.enforceGsmAnnotations(statement, archetype, defId));
        }
    }

    // ========================================================================
    // $gsm:deprecated enforcement (warning, not error)
    // ========================================================================

    @Nested
    class EnforceDeprecated {

        @Test
        void deprecatedProperty_noError() {
            ObjectNode propNode = prop("string");
            propNode.put("$gsm:deprecated", true);
            ObjectNode archetypeSchema = schemaWithProperty("oldField", propNode);

            ArchetypeEntity archetype = stubArchetypeWithSchema(archetypeSchema);
            ObjectNode statement = MAPPER.createObjectNode().put("oldField", "value");

            assertDoesNotThrow(() -> service.enforceGsmAnnotations(statement, archetype, UUID.randomUUID()));
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

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateStatement(statement, archetype));
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

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateStatement(statement, archetype));
            assertTrue(ex.getMessage().contains("Statement validation failed"));
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

            AbstractAscriptionService spyService = new AbstractAscriptionService() {
                @Override
                public DefinitionSubjectType getSubjectType() {
                    return DefinitionSubjectType.STRUCTURE;
                }

                @Override
                public AscriptionEntity buildEntity(DefinitionEntity def, ArchetypeEntity arch, JsonNode stmt) {
                    return null;
                }

                @Override
                public AscriptionEntity save(AscriptionEntity entity) {
                    return null;
                }

                @Override
                public Page<? extends AscriptionEntity> findAll(Pageable pageable) {
                    return null;
                }

                @Override
                public Page<? extends AscriptionEntity> findAllByStatus(AscriptionStatusType s, Pageable p) {
                    return null;
                }

                @Override
                public List<? extends AscriptionEntity> findAllByDefinitionId(UUID id) {
                    return existingAscriptions;
                }

                @Override
                public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(UUID id,
                        Collection<AscriptionStatusType> s) {
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

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> spyService.validateIdentityBound(newEntity));
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

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateCreationPreconditions(entity));
            assertTrue(ex.getMessage().contains("Referee precondition failed"));
            assertTrue(ex.getMessage().contains("RETIRED"));
        }

        @Test
        void referenceInSuspended_rejected() {
            AscriptionEntity ref = mock(AscriptionEntity.class);
            when(ref.getStatus()).thenReturn(AscriptionStatusType.SUSPENDED);
            when(ref.getId()).thenReturn(UUID.randomUUID());
            refereeReferences = List.of(new RefereeReference(ref, "structure"));

            AscriptionEntity entity = mock(AscriptionEntity.class);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateCreationPreconditions(entity));
            assertTrue(ex.getMessage().contains("Referee precondition failed"));
        }

        @Test
        void referenceInDeprecated_rejected() {
            AscriptionEntity ref = mock(AscriptionEntity.class);
            when(ref.getStatus()).thenReturn(AscriptionStatusType.DEPRECATED);
            when(ref.getId()).thenReturn(UUID.randomUUID());
            refereeReferences = List.of(new RefereeReference(ref, "structure"));

            AscriptionEntity entity = mock(AscriptionEntity.class);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateCreationPreconditions(entity));
            assertTrue(ex.getMessage().contains("Referee precondition failed"));
        }

        @Test
        void referenceInAbandoned_rejected() {
            AscriptionEntity ref = mock(AscriptionEntity.class);
            when(ref.getStatus()).thenReturn(AscriptionStatusType.ABANDONED);
            when(ref.getId()).thenReturn(UUID.randomUUID());
            refereeReferences = List.of(new RefereeReference(ref, "structure"));

            AscriptionEntity entity = mock(AscriptionEntity.class);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateCreationPreconditions(entity));
            assertTrue(ex.getMessage().contains("Referee precondition failed"));
        }
    }

    // ========================================================================
    // $gsm:validationCEL evaluation at Ascription authoring (V6)
    // ========================================================================

    @Nested
    class EnforceValidationCel {

        @Test
        void celExpressionTrue_passes() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("title", "TestSchema");
            ObjectNode props = schema.putObject("properties");
            props.set("budget", prop("number"));
            schema.set("$gsm:validationCEL", MAPPER.createArrayNode()
                    .add("this.budget > 0.0"));

            ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
            ObjectNode statement = MAPPER.createObjectNode().put("budget", 100.0);

            assertDoesNotThrow(() -> service.enforceGsmAnnotations(statement, archetype, UUID.randomUUID()));
        }

        @Test
        void celExpressionFalse_rejected() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("title", "TestSchema");
            ObjectNode props = schema.putObject("properties");
            props.set("budget", prop("number"));
            schema.set("$gsm:validationCEL", MAPPER.createArrayNode()
                    .add("this.budget > 0.0"));

            ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
            ObjectNode statement = MAPPER.createObjectNode().put("budget", -5.0);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.enforceGsmAnnotations(statement, archetype, UUID.randomUUID()));
            assertTrue(ex.getMessage().contains("$gsm:validationCEL[0]"));
            assertTrue(ex.getMessage().contains("constraint failed"));
        }

        @Test
        void multipleExpressions_allMustPass() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("title", "TestSchema");
            ObjectNode props = schema.putObject("properties");
            props.set("min", prop("number"));
            props.set("max", prop("number"));
            schema.set("$gsm:validationCEL", MAPPER.createArrayNode()
                    .add("this.min > 0.0")
                    .add("this.min <= this.max"));

            ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
            ObjectNode statement = MAPPER.createObjectNode()
                    .put("min", 10.0)
                    .put("max", 5.0); // violates 2nd expression

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.enforceGsmAnnotations(statement, archetype, UUID.randomUUID()));
            assertTrue(ex.getMessage().contains("$gsm:validationCEL"));
            assertTrue(ex.getMessage().contains("constraint failed"));
        }

        @Test
        void allExpressionsTrue_passes() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("title", "TestSchema");
            ObjectNode props = schema.putObject("properties");
            props.set("min", prop("number"));
            props.set("max", prop("number"));
            schema.set("$gsm:validationCEL", MAPPER.createArrayNode()
                    .add("this.min > 0.0")
                    .add("this.min <= this.max"));

            ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
            ObjectNode statement = MAPPER.createObjectNode()
                    .put("min", 5.0)
                    .put("max", 10.0);

            assertDoesNotThrow(() -> service.enforceGsmAnnotations(statement, archetype, UUID.randomUUID()));
        }

        @Test
        void noCelAnnotation_passes() {
            ObjectNode schema = schemaWithProperty("x", prop("string"));

            ArchetypeEntity archetype = stubArchetypeWithSchema(schema);
            ObjectNode statement = MAPPER.createObjectNode().put("x", "hello");

            assertDoesNotThrow(() -> service.enforceGsmAnnotations(statement, archetype, UUID.randomUUID()));
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
        ObjectNode statement = MAPPER.createObjectNode();
        statement.set("schema", schema);

        ArchetypeEntity archetype = mock(ArchetypeEntity.class);
        when(archetype.getStatement()).thenReturn(statement);

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
}
