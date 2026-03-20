package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.RuleType;
import jakarta.persistence.EntityManager;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchetypeServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ArchetypeRepository archetypeRepo;

    private ArchetypeService service;

    @BeforeEach
    void setUp() {
        service = new ArchetypeService(
                archetypeRepo,
                mock(JdbcTemplate.class),
                mock(DefinitionService.class),
                mock(AscriptionStatusTransitionService.class),
                mock(AscriptionRepository.class),
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

                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateActivationUniqueness(entity));
                assertTrue(ex.getMessage().contains("SecurityProperties"));
                assertTrue(ex.getMessage().contains("already in"));
                assertEquals(RuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS, ex.getRuleType());
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
            void emptyTitle_rejected() {
                UUID thisDefId = UUID.randomUUID();
                ArchetypeEntity entity = stubArchetype("", thisDefId);

                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateActivationUniqueness(entity));
                assertTrue(ex.getMessage().contains("must not be"));
                assertEquals(RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
            }

            @Test
            void missingSchema_rejected() {
                UUID thisDefId = UUID.randomUUID();
                ArchetypeEntity entity = stubArchetypeNoSchema(thisDefId);

                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateActivationUniqueness(entity));
                assertTrue(ex.getMessage().contains("must not be"));
                assertEquals(RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
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
            for (String title : List.of(
                    "StructureArchetype", "MechanismArchetype", "InteractionArchetype",
                    "Archetype", "EffectorArchetype",
                    "ReceptorArchetype", "DirectiveArchetype", "NormArchetype")) {
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
            ArchetypeEntity sealedArchetype = mockArchetype(schemaNode("DirectiveArchetype", true));
            when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(sealedArchetype));

            ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantDirective");
            schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/DirectiveArchetype/v1");

            RuleViolationException ex = assertThrows(RuleViolationException.class,
                    () -> service.validateAllOfChain(schema));
            assertTrue(ex.getMessage().contains("sealed"));
            assertEquals(RuleType.ARCHETYPE_ALLOF_SEAL, ex.getRuleType());
        }

        @Test
        void invalidRefFormat_rejected() {
            ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantThing");
            schema.putArray("allOf").addObject().put("$ref", "https://example.com/not-gsm");

            RuleViolationException ex = assertThrows(RuleViolationException.class,
                    () -> service.validateAllOfChain(schema));
            assertTrue(ex.getMessage().contains("gsm://"));
            assertEquals(RuleType.ARCHETYPE_ALLOF_CHAIN_EXCLUSIVE_BASE_CONVERGENCE, ex.getRuleType());
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

            RuleViolationException ex = assertThrows(RuleViolationException.class,
                    () -> service.validateAllOfChain(schema));
            assertTrue(ex.getMessage().contains("multiple"));
            assertEquals(RuleType.ARCHETYPE_ALLOF_CHAIN_EXCLUSIVE_BASE_CONVERGENCE, ex.getRuleType());
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

            RuleViolationException ex = assertThrows(RuleViolationException.class,
                    () -> service.validateAllOfChain(schema));
            assertTrue(ex.getMessage().contains("Cycle") || ex.getMessage().contains("already visited"));
            assertEquals(RuleType.ARCHETYPE_ALLOF_CHAIN_ACYCLICITY, ex.getRuleType());
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
            RuleViolationException ex = assertThrows(RuleViolationException.class,
                    () -> service.validateAllOfChain(schema, true));
            assertTrue(ex.getMessage().contains("Cannot resolve"));
            assertEquals(RuleType.ARCHETYPE_ALLOF_CHAIN_EXCLUSIVE_BASE_CONVERGENCE, ex.getRuleType());
        }

        @Test
        void extractTitleFromRef_validUri() {
            assertEquals("SecurityProperties",
                    ArchetypeService.extractTitleFromRef("gsm://archetypes/SecurityProperties/v1"));
            assertEquals("MyType",
                    ArchetypeService.extractTitleFromRef("gsm://archetypes/MyType/v42"));
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
            tenantSchema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/StructureArchetype/v1");
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
            intermediarySchema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/MechanismArchetype/v1");
            ArchetypeEntity intermediary = mockArchetype(intermediarySchema);
            when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(mechBase, intermediary));

            UUID tenantId = UUID.randomUUID();
            ObjectNode tenantSchema = MAPPER.createObjectNode().put("title", "SpecificMechanism");
            tenantSchema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/BaseMechanismTemplate/v1");
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

            RuleViolationException ex = assertThrows(RuleViolationException.class,
                    () -> service.resolveForCreation(id));
            assertTrue(ex.getMessage().contains("Rootless"));
            assertTrue(ex.getMessage().contains("archetype_id"));
            assertEquals(RuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE, ex.getRuleType());
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

            RuleViolationException ex = assertThrows(RuleViolationException.class,
                    () -> service.resolveForCreation(id));
            assertTrue(ex.getMessage().contains("Rootless"));
            assertTrue(ex.getMessage().contains("archetype_id"));
            assertEquals(RuleType.ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE, ex.getRuleType());
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

                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(schema, defId));
                assertEquals(RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
            }

            @Test
            void unknownPropertyAnnotation_rejected() {
                ObjectNode propNode = prop("string");
                propNode.put("$gsm:foobar", true);
                ObjectNode schema = schemaWithProperty("x", propNode);

                UUID defId = UUID.randomUUID();
                when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(schema, defId));
                assertTrue(ex.getMessage().contains("$gsm:foobar"));
                assertEquals(RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
            }

            @Test
            void topLevelAnnotationOnProperty_rejected() {
                ObjectNode propNode = prop("string");
                propNode.put("$gsm:sealed", true);
                ObjectNode schema = schemaWithProperty("x", propNode);

                UUID defId = UUID.randomUUID();

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(schema, defId));
                assertTrue(ex.getMessage().contains("top-level only"));
                assertEquals(RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
            }
        }

        @Nested
        class QueryableAnnotation {

            @Test
            void queryableString_valid() {
                ObjectNode propNode = prop("string");
                propNode.put("$gsm:queryable", true);
                ObjectNode schema = schemaWithProperty("env", propNode);

                UUID defId = UUID.randomUUID();
                when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

                assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
            }

            @Test
            void queryableObject_rejected() {
                ObjectNode propNode = prop("object");
                propNode.put("$gsm:queryable", true);
                ObjectNode schema = schemaWithProperty("data", propNode);

                UUID defId = UUID.randomUUID();

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(schema, defId));
                assertTrue(ex.getMessage().contains("$gsm:queryable"));
                assertTrue(ex.getMessage().contains("object"));
                assertEquals(RuleType.ARCHETYPE_ANNOTATION_QUERYABLE, ex.getRuleType());
            }

            @Test
            void queryableNoType_rejected() {
                ObjectNode propNode = MAPPER.createObjectNode();
                propNode.put("$gsm:queryable", true);
                ObjectNode schema = schemaWithProperty("x", propNode);

                UUID defId = UUID.randomUUID();

                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(schema, defId));
                assertEquals(RuleType.ARCHETYPE_ANNOTATION_QUERYABLE, ex.getRuleType());
            }

            @Test
            void queryableArrayOfScalars_valid() {
                ObjectNode items = MAPPER.createObjectNode().put("type", "string");
                ObjectNode propNode = MAPPER.createObjectNode();
                propNode.put("type", "array");
                propNode.set("items", items);
                propNode.put("$gsm:queryable", true);
                ObjectNode schema = schemaWithProperty("tags", propNode);

                UUID defId = UUID.randomUUID();
                when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

                assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
            }

            @Test
            void queryableArrayOfObjects_rejected() {
                ObjectNode items = MAPPER.createObjectNode().put("type", "object");
                ObjectNode propNode = MAPPER.createObjectNode();
                propNode.put("type", "array");
                propNode.set("items", items);
                propNode.put("$gsm:queryable", true);
                ObjectNode schema = schemaWithProperty("entries", propNode);

                UUID defId = UUID.randomUUID();

                RuleViolationException ex = assertThrows(RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(schema, defId));
                assertEquals(RuleType.ARCHETYPE_ANNOTATION_QUERYABLE, ex.getRuleType());
            }

            @Test
            void tooManyQueryable_rejected() {
                ObjectNode schema = MAPPER.createObjectNode();
                schema.put("title", "TooMany");
                ObjectNode props = schema.putObject("properties");
                for (int i = 0; i < 9; i++) {
                    ObjectNode p = props.putObject("p" + i);
                    p.put("type", "string");
                    p.put("$gsm:queryable", true);
                }

                UUID defId = UUID.randomUUID();

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(schema, defId));
                assertTrue(ex.getMessage().contains("Too many $gsm:queryable"));
                assertEquals(RuleType.ARCHETYPE_ANNOTATION_QUERYABLE, ex.getRuleType());
            }
        }

        @Nested
        class DataProtectionAnnotation {

            @Test
            void hashAtRest_valid() {
                ObjectNode propNode = prop("string");
                ObjectNode dp = MAPPER.createObjectNode();
                ObjectNode atRest = dp.putObject("atRest");
                ObjectNode hash = atRest.putObject("hash");
                hash.put("algorithm", "SHA-256");
                propNode.set("$gsm:dataProtection", dp);
                ObjectNode schema = schemaWithProperty("ssn", propNode);

                UUID defId = UUID.randomUUID();
                when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

                assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
            }

            @Test
            void maskInTransit_valid() {
                ObjectNode propNode = prop("string");
                ObjectNode dp = MAPPER.createObjectNode();
                ObjectNode inTransit = dp.putObject("inTransit");
                ObjectNode mask = inTransit.putObject("mask");
                mask.put("from", "LEFT");
                ObjectNode with = mask.putObject("with");
                with.put("character", "*");
                with.put("occurrence", 4);
                propNode.set("$gsm:dataProtection", dp);
                ObjectNode schema = schemaWithProperty("phone", propNode);

                UUID defId = UUID.randomUUID();
                when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

                assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
            }

            @Test
            void suppressionAtRest_valid() {
                ObjectNode propNode = prop("string");
                ObjectNode dp = MAPPER.createObjectNode();
                ObjectNode atRest = dp.putObject("atRest");
                atRest.put("suppression", true);
                propNode.set("$gsm:dataProtection", dp);
                ObjectNode schema = schemaWithProperty("secret", propNode);

                UUID defId = UUID.randomUUID();
                when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

                assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
            }

            @Test
            void encryptionAtRest_silentlyIgnored() {
                ObjectNode propNode = prop("string");
                ObjectNode dp = MAPPER.createObjectNode();
                ObjectNode atRest = dp.putObject("atRest");
                atRest.putObject("encryption").put("algorithm", "AES-256-GCM");
                propNode.set("$gsm:dataProtection", dp);
                ObjectNode schema = schemaWithProperty("secret", propNode);

                UUID defId = UUID.randomUUID();

                assertDoesNotThrow(
                        () -> service.validateArchetypeAnnotations(schema, defId));
            }

            @Test
            void encryptionInTransit_silentlyIgnored() {
                ObjectNode propNode = prop("string");
                ObjectNode dp = MAPPER.createObjectNode();
                ObjectNode inTransit = dp.putObject("inTransit");
                inTransit.putObject("encryption").put("algorithm", "AES-256-GCM");
                propNode.set("$gsm:dataProtection", dp);
                ObjectNode schema = schemaWithProperty("secret", propNode);

                UUID defId = UUID.randomUUID();

                assertDoesNotThrow(
                        () -> service.validateArchetypeAnnotations(schema, defId));
            }

            @Test
            void crossPhase_atRestHashWithInTransitHash_rejected() {
                ObjectNode propNode = prop("string");
                ObjectNode dp = MAPPER.createObjectNode();
                dp.putObject("atRest").putObject("hash").put("algorithm", "SHA-256");
                dp.putObject("inTransit").putObject("hash").put("algorithm", "SHA-256");
                propNode.set("$gsm:dataProtection", dp);
                ObjectNode schema = schemaWithProperty("ssn", propNode);

                UUID defId = UUID.randomUUID();

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(schema, defId));
                assertTrue(ex.getMessage().contains("atRest.hash constrains inTransit"));
                assertEquals(RuleType.ARCHETYPE_ANNOTATION_DATA_PROTECTION, ex.getRuleType());
            }

            @Test
            void crossPhase_atRestSuppressionWithInTransit_rejected() {
                ObjectNode propNode = prop("string");
                ObjectNode dp = MAPPER.createObjectNode();
                dp.putObject("atRest").put("suppression", true);
                dp.putObject("inTransit").put("suppression", true);
                propNode.set("$gsm:dataProtection", dp);
                ObjectNode schema = schemaWithProperty("secret", propNode);

                UUID defId = UUID.randomUUID();

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(schema, defId));
                assertTrue(ex.getMessage().contains("atRest.suppression requires inTransit to be absent"));
                assertEquals(RuleType.ARCHETYPE_ANNOTATION_DATA_PROTECTION, ex.getRuleType());
            }

            @Test
            void crossPhase_atRestHashWithInTransitSuppression_valid() {
                ObjectNode propNode = prop("string");
                ObjectNode dp = MAPPER.createObjectNode();
                dp.putObject("atRest").putObject("hash").put("algorithm", "SHA-256");
                dp.putObject("inTransit").put("suppression", true);
                propNode.set("$gsm:dataProtection", dp);
                ObjectNode schema = schemaWithProperty("ssn", propNode);

                UUID defId = UUID.randomUUID();
                when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

                assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
            }

            @Test
            void queryableWithHashAtRest_valid() {
                ObjectNode propNode = prop("string");
                ObjectNode dp = MAPPER.createObjectNode();
                dp.putObject("atRest").putObject("hash").put("algorithm", "SHA-256");
                propNode.set("$gsm:dataProtection", dp);
                propNode.put("$gsm:queryable", true);
                ObjectNode schema = schemaWithProperty("email", propNode);

                UUID defId = UUID.randomUUID();
                when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

                assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
            }

            @Test
            void dataProtectionWithoutQueryable_valid() {
                ObjectNode propNode = prop("string");
                ObjectNode dp = MAPPER.createObjectNode();
                dp.putObject("atRest").putObject("hash").put("algorithm", "SHA-512");
                propNode.set("$gsm:dataProtection", dp);
                ObjectNode schema = schemaWithProperty("password", propNode);

                UUID defId = UUID.randomUUID();
                when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

                assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
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
                when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of(existing));

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
                when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of(existing));

                ObjectNode newProp = prop("string");
                newProp.put("$gsm:identityBound", true);
                ObjectNode newSchema = schemaWithProperty("name", newProp);

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(newSchema, defId));
                assertTrue(ex.getMessage().contains("identityBound set immutability"));
                assertEquals(RuleType.ARCHETYPE_ANNOTATION_IDENTITY_BOUND_SET_IMMUTABILITY, ex.getRuleType());
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

        @Nested
        class ValidationAnnotation {

            @Test
            void validCelExpressions_accepted() {
                ObjectNode schema = schemaWithProperty("budget", prop("number"));
                schema.set("$gsm:validation", MAPPER.createArrayNode()
                        .add("this.budget > 0.0"));

                UUID defId = UUID.randomUUID();
                when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

                assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
            }

            @Test
            void multipleCelExpressions_accepted() {
                ObjectNode schema = MAPPER.createObjectNode();
                schema.put("title", "TestSchema");
                ObjectNode props = schema.putObject("properties");
                props.set("min", prop("number"));
                props.set("max", prop("number"));
                schema.set("$gsm:validation", MAPPER.createArrayNode()
                        .add("this.min <= this.max")
                        .add("this.min > 0"));

                UUID defId = UUID.randomUUID();
                when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

                assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
            }

            @Test
            void invalidCelSyntax_rejected() {
                ObjectNode schema = schemaWithProperty("x", prop("number"));
                schema.set("$gsm:validation", MAPPER.createArrayNode()
                        .add("this.x >>>> 0"));

                UUID defId = UUID.randomUUID();

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(schema, defId));
                assertTrue(ex.getMessage().contains("$gsm:validation[0]"));
                assertTrue(ex.getMessage().contains("CEL parse error")
                        || ex.getMessage().contains("CEL validation error"));
                assertEquals(RuleType.ARCHETYPE_VALIDATION_CEL_PARSING, ex.getRuleType());
            }

            @Test
            void notAnArray_rejected() {
                ObjectNode schema = schemaWithProperty("x", prop("number"));
                schema.put("$gsm:validation", "this.x > 0");

                UUID defId = UUID.randomUUID();

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(schema, defId));
                assertTrue(ex.getMessage().contains("must be an array"));
                assertEquals(RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
            }

            @Test
            void nonStringElement_rejected() {
                ObjectNode schema = schemaWithProperty("x", prop("number"));
                schema.set("$gsm:validation", MAPPER.createArrayNode()
                        .add(42));

                UUID defId = UUID.randomUUID();

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(schema, defId));
                assertTrue(ex.getMessage().contains("$gsm:validation[0]"));
                assertTrue(ex.getMessage().contains("must be a string"));
                assertEquals(RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
            }

            @Test
            void blankExpression_rejected() {
                ObjectNode schema = schemaWithProperty("x", prop("number"));
                schema.set("$gsm:validation", MAPPER.createArrayNode()
                        .add("  "));

                UUID defId = UUID.randomUUID();

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(schema, defId));
                assertTrue(ex.getMessage().contains("$gsm:validation[0]"));
                assertTrue(ex.getMessage().contains("must not be blank"));
                assertEquals(RuleType.ARCHETYPE_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE, ex.getRuleType());
            }

            @Test
            void emptyArray_accepted() {
                ObjectNode schema = schemaWithProperty("x", prop("number"));
                schema.set("$gsm:validation", MAPPER.createArrayNode());

                UUID defId = UUID.randomUUID();
                when(archetypeRepo.findAllByDefinitionIdOrderByTimestampDesc(defId)).thenReturn(List.of());

                assertDoesNotThrow(() -> service.validateArchetypeAnnotations(schema, defId));
            }

            @Test
            void unboundIdent_rejected() {
                ObjectNode schema = schemaWithProperty("bar", prop("number"));
                schema.set("$gsm:validation", MAPPER.createArrayNode()
                        .add("foo.bar > 0"));

                UUID defId = UUID.randomUUID();

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(schema, defId));
                assertEquals(RuleType.ARCHETYPE_VALIDATION_CEL_THIS_ROOT_BINDING, ex.getRuleType());
                assertTrue(ex.getMessage().contains("unbound"));
            }

            @Test
            void arithmeticTopLevel_rejected() {
                ObjectNode schema = schemaWithProperty("a", prop("number"));
                schema.set("$gsm:validation", MAPPER.createArrayNode()
                        .add("this.a + 1"));

                UUID defId = UUID.randomUUID();

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(schema, defId));
                assertEquals(RuleType.ARCHETYPE_VALIDATION_CEL_BOOLEAN_RESULT, ex.getRuleType());
                assertTrue(ex.getMessage().contains("arithmetic"));
            }

            @ParameterizedTest
            @ValueSource(strings = {
                    "this.tags.size()",
                    "int(this.a)",
                    "double(this.a)",
                    "uint(this.a)",
                    "string(this.a)",
                    "duration('5m')",
                    "timestamp('2024-01-01T00:00:00Z')"
            })
            void nonBooleanFunctionTopLevel_rejected(String expression) {
                ObjectNode schema = MAPPER.createObjectNode();
                schema.put("title", "TestSchema");
                ObjectNode props = schema.putObject("properties");
                props.set("tags", prop("array"));
                props.set("a", prop("number"));
                schema.set("$gsm:validation", MAPPER.createArrayNode()
                        .add(expression));

                UUID defId = UUID.randomUUID();

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(schema, defId));
                assertEquals(RuleType.ARCHETYPE_VALIDATION_CEL_BOOLEAN_RESULT, ex.getRuleType());
                assertTrue(ex.getMessage().contains("not a known boolean-producing operation"),
                        "Expected rejection for: " + expression);
            }

            @Test
            void nonBooleanConstant_rejected() {
                ObjectNode schema = schemaWithProperty("x", prop("number"));
                schema.set("$gsm:validation", MAPPER.createArrayNode()
                        .add("42"));

                UUID defId = UUID.randomUUID();

                RuleViolationException ex = assertThrows(
                        RuleViolationException.class,
                        () -> service.validateArchetypeAnnotations(schema, defId));
                assertEquals(RuleType.ARCHETYPE_VALIDATION_CEL_BOOLEAN_RESULT, ex.getRuleType());
                assertTrue(ex.getMessage().contains("non-boolean constant"));
            }
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
}
