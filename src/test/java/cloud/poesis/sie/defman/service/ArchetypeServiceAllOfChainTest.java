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
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchetypeServiceAllOfChainTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ArchetypeRepository archetypeRepo;

    private ArchetypeService service;

    @BeforeEach
    void setUp() {
        service = new ArchetypeService(
                archetypeRepo,
                mock(JdbcTemplate.class));
    }

    // ========================================================================
    // GSM base archetypes are exempt
    // ========================================================================

    @Test
    void gsmBaseArchetype_exempt() {
        ObjectNode schema = MAPPER.createObjectNode().put("title", "StructureArchetype");
        assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }

    @Test
    void gsmBaseArchetype_allExempt() {
        for (String title : List.of(
                "StructureArchetype", "MechanismArchetype", "InteractionArchetype",
                "InterfaceArchetype", "Archetype", "EffectorArchetype",
                "ReceptorArchetype", "DirectiveArchetype", "NormArchetype")) {
            ObjectNode schema = MAPPER.createObjectNode().put("title", title);
            assertDoesNotThrow(() -> service.validateAllOfChain(schema), "Expected exempt: " + title);
        }
    }

    // ========================================================================
    // Rootless archetypes (no allOf or no structural base) — accepted
    // ========================================================================

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
        // SecurityProperties is rootless (no allOf itself)
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
        allOf.addObject().put("type", "object"); // inline schema, no $ref

        assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }

    @Test
    void structuralBaseWithRootlessFacet_accepted() {
        // StructureArchetype is a GSM base (non-sealed)
        ArchetypeEntity structBase = mockArchetype(schemaNode("StructureArchetype", false));
        // SecurityProperties is rootless
        ObjectNode facetSchema = schemaNode("SecurityProperties", false);
        ArchetypeEntity facet = mockArchetype(facetSchema);
        when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(structBase, facet));

        ObjectNode schema = MAPPER.createObjectNode().put("title", "SecuredStructure");
        var allOf = schema.putArray("allOf");
        allOf.addObject().put("$ref", "gsm://archetypes/StructureArchetype/v1");
        allOf.addObject().put("$ref", "gsm://archetypes/SecurityProperties/v1");

        assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }

    // ========================================================================
    // Valid allOf chain converging to one GSM base
    // ========================================================================

    @Test
    void directAllOfToGsmBase_accepted() {
        ArchetypeEntity baseArchetype = mockArchetype(schemaNode("StructureArchetype", false));
        when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(baseArchetype));

        ObjectNode schema = MAPPER.createObjectNode().put("title", "MyStructure");
        schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/StructureArchetype/v1");

        assertDoesNotThrow(() -> service.validateAllOfChain(schema));
    }

    // ========================================================================
    // Sealed schema enforcement
    // ========================================================================

    @Test
    void allOfToSealedBase_rejected() {
        ArchetypeEntity sealedArchetype = mockArchetype(schemaNode("DirectiveArchetype", true));
        when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(sealedArchetype));

        ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantDirective");
        schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/DirectiveArchetype/v1");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.validateAllOfChain(schema));
        assertTrue(ex.getMessage().contains("sealed"));
    }

    // ========================================================================
    // Invalid $ref format
    // ========================================================================

    @Test
    void invalidRefFormat_rejected() {
        ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantThing");
        schema.putArray("allOf").addObject().put("$ref", "https://example.com/not-gsm");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.validateAllOfChain(schema));
        assertTrue(ex.getMessage().contains("gsm://"));
    }

    // ========================================================================
    // Convergence to multiple bases
    // ========================================================================

    @Test
    void convergesToMultipleBases_rejected() {
        ArchetypeEntity struct = mockArchetype(schemaNode("StructureArchetype", false));
        ArchetypeEntity mech = mockArchetype(schemaNode("MechanismArchetype", false));
        when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(struct, mech));

        ObjectNode schema = MAPPER.createObjectNode().put("title", "ConfusedType");
        var allOf = schema.putArray("allOf");
        allOf.addObject().put("$ref", "gsm://archetypes/StructureArchetype/v1");
        allOf.addObject().put("$ref", "gsm://archetypes/MechanismArchetype/v1");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.validateAllOfChain(schema));
        assertTrue(ex.getMessage().contains("multiple"));
    }

    // ========================================================================
    // Cycle detection
    // ========================================================================

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

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.validateAllOfChain(schema));
        assertTrue(ex.getMessage().contains("Cycle") || ex.getMessage().contains("already visited"));
    }

    // ========================================================================
    // Intermediary archetype not found
    // ========================================================================

    @Test
    void unresolvableIntermediary_rejected() {
        when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of());

        ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantType");
        schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/NonExistent/v1");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.validateAllOfChain(schema));
        assertTrue(ex.getMessage().contains("Cannot resolve"));
    }

    // ========================================================================
    // extractTitleFromRef (package-private static, tested directly)
    // ========================================================================

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

    // ========================================================================
    // resolveForCreation — structural base enforcement for archetype_id
    // ========================================================================

    @Test
    void resolveForCreation_gsmBaseArchetype_resolvesDirectly() {
        UUID id = UUID.randomUUID();
        ArchetypeEntity base = mockArchetype(schemaNode("StructureArchetype", false));
        when(base.getId()).thenReturn(id);
        when(archetypeRepo.findById(id)).thenReturn(Optional.of(base));

        var resolution = service.resolveForCreation(id);
        assertEquals(DefinitionSubjectType.STRUCTURE, resolution.subjectType());
    }

    @Test
    void resolveForCreation_tenantStructuralArchetype_walksChain() {
        // Base archetype for allOf chain resolution
        ArchetypeEntity structBase = mockArchetype(schemaNode("StructureArchetype", false));
        when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(structBase));

        // Tenant archetype extending StructureArchetype
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
        // Base archetype
        ArchetypeEntity mechBase = mockArchetype(schemaNode("MechanismArchetype", false));
        // Intermediary tenant archetype
        ObjectNode intermediarySchema = schemaNode("BaseMechanismTemplate", false);
        intermediarySchema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/MechanismArchetype/v1");
        ArchetypeEntity intermediary = mockArchetype(intermediarySchema);
        when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(mechBase, intermediary));

        // Tenant archetype extending intermediary
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

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.resolveForCreation(id));
        assertTrue(ex.getMessage().contains("Rootless"));
        assertTrue(ex.getMessage().contains("archetype_id"));
    }

    @Test
    void resolveForCreation_rootlessWithAllOf_rejected() {
        // Rootless facet archetype extending another rootless facet
        ObjectNode baseFacet = schemaNode("BaseFacet", false);
        ArchetypeEntity baseFacetEntity = mockArchetype(baseFacet);
        when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(baseFacetEntity));

        UUID id = UUID.randomUUID();
        ObjectNode rootless = MAPPER.createObjectNode().put("title", "DetailedFacet");
        rootless.putArray("allOf").addObject().put("$ref", "gsm://archetypes/BaseFacet/v1");
        ArchetypeEntity entity = mockArchetype(rootless);
        when(entity.getId()).thenReturn(id);
        when(archetypeRepo.findById(id)).thenReturn(Optional.of(entity));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.resolveForCreation(id));
        assertTrue(ex.getMessage().contains("Rootless"));
        assertTrue(ex.getMessage().contains("archetype_id"));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

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
}
