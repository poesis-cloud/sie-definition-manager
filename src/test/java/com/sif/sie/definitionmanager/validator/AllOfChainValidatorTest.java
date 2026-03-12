package com.sif.sie.definitionmanager.validator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
import com.sif.sie.definitionmanager.repository.ArchetypeRepository;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;

@ExtendWith(MockitoExtension.class)
class AllOfChainValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ArchetypeRepository archetypeRepo;

    private AllOfChainValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AllOfChainValidator(archetypeRepo);
    }

    // ========================================================================
    // GSM base archetypes are exempt
    // ========================================================================

    @Test
    void gsmBaseArchetype_exempt() {
        ObjectNode schema = MAPPER.createObjectNode().put("title", "StructureArchetype");
        assertDoesNotThrow(() -> validator.validate(schema));
    }

    @Test
    void gsmBaseArchetype_allExempt() {
        for (String title : List.of(
                "StructureArchetype", "MechanismArchetype", "InteractionArchetype",
                "InterfaceArchetype", "Archetype", "EffectorArchetype",
                "ReceptorArchetype", "DirectiveArchetype", "NormArchetype")) {
            ObjectNode schema = MAPPER.createObjectNode().put("title", title);
            assertDoesNotThrow(() -> validator.validate(schema), "Expected exempt: " + title);
        }
    }

    // ========================================================================
    // Missing allOf
    // ========================================================================

    @Test
    void missingAllOf_rejected() {
        ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantArchetype");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(schema));
        assertTrue(ex.getMessage().contains("allOf"));
    }

    @Test
    void emptyAllOf_rejected() {
        ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantArchetype");
        schema.putArray("allOf");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(schema));
        assertTrue(ex.getMessage().contains("allOf"));
    }

    // ========================================================================
    // Valid allOf chain converging to one GSM base
    // ========================================================================

    @Test
    void directAllOfToGsmBase_accepted() {
        // Mock: StructureArchetype is not sealed
        ArchetypeEntity baseArchetype = mockArchetype(schemaNode("StructureArchetype", false));
        when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(baseArchetype));

        ObjectNode schema = MAPPER.createObjectNode().put("title", "MyStructure");
        schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/StructureArchetype/v1");

        assertDoesNotThrow(() -> validator.validate(schema));
    }

    // ========================================================================
    // Sealed schema enforcement
    // ========================================================================

    @Test
    void allOfToSealedBase_rejected() {
        // DirectiveArchetype is sealed
        ArchetypeEntity sealedArchetype = mockArchetype(schemaNode("DirectiveArchetype", true));
        when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(sealedArchetype));

        ObjectNode schema = MAPPER.createObjectNode().put("title", "TenantDirective");
        schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/DirectiveArchetype/v1");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(schema));
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
                () -> validator.validate(schema));
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
                () -> validator.validate(schema));
        assertTrue(ex.getMessage().contains("multiple"));
    }

    // ========================================================================
    // Cycle detection
    // ========================================================================

    @Test
    void cycleInAllOfChain_rejected() {
        // tenant archetype "A" references "B", "B" references "A"
        ObjectNode schemaA = schemaNode("A", false);
        schemaA.putArray("allOf").addObject().put("$ref", "gsm://archetypes/B/v1");

        ObjectNode schemaB = schemaNode("B", false);
        schemaB.putArray("allOf").addObject().put("$ref", "gsm://archetypes/A/v1");

        ArchetypeEntity archetypeB = mockArchetype(schemaB);
        when(archetypeRepo.findAllByStatusIn(anyCollection())).thenReturn(List.of(archetypeB));

        ObjectNode schema = MAPPER.createObjectNode().put("title", "A");
        schema.putArray("allOf").addObject().put("$ref", "gsm://archetypes/B/v1");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(schema));
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
                () -> validator.validate(schema));
        assertTrue(ex.getMessage().contains("Cannot resolve"));
    }

    // ========================================================================
    // extractTitleFromRef (package-private, tested directly)
    // ========================================================================

    @Test
    void extractTitleFromRef_validUri() {
        assertEquals("SecurityProperties",
                AllOfChainValidator.extractTitleFromRef("gsm://archetypes/SecurityProperties/v1"));
        assertEquals("MyType",
                AllOfChainValidator.extractTitleFromRef("gsm://archetypes/MyType/v42"));
    }

    @Test
    void extractTitleFromRef_invalidUri() {
        assertNull(AllOfChainValidator.extractTitleFromRef("https://example.com/schema"));
        assertNull(AllOfChainValidator.extractTitleFromRef("not-a-uri"));
        assertNull(AllOfChainValidator.extractTitleFromRef("gsm://archetypes/NoVersion"));
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
        ObjectNode stmt = MAPPER.createObjectNode();
        stmt.set("schema", schema);
        when(entity.getStatement()).thenReturn(stmt);
        when(entity.getId()).thenReturn(UUID.randomUUID());
        return entity;
    }
}
