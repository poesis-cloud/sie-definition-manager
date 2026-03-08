package org.sif.sie.dm.api;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.sif.sie.dm.registry.SchemaRegistryClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Integration tests for the Ascription REST API against a real PostgreSQL
 * instance (Testcontainers). Flyway runs V1-V3 migrations, seeding 9 base
 * archetypes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("tc")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AscriptionApiIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16.3-alpine");

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
    }

    @MockitoBean
    SchemaRegistryClient schemaRegistryClient;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    // ================================================================
    // Shared state across ordered tests
    // ================================================================
    static UUID seedArchetypeRevisionId;
    static UUID structureArchetypeRevisionId;
    static UUID mechanismArchetypeRevisionId;

    static UUID createdArchetypeRevisionId;
    static UUID createdArchetypeId;
    static UUID siblingArchetypeRevisionId;

    static UUID createdStructureRevisionId;
    static UUID createdMechanismRevisionId;

    // ================================================================
    // SEED DATA TESTS
    // ================================================================

    @Test
    @Order(1)
    void listSeedArchetypes_returns9Active() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/ascriptions")
                .param("type", "archetype")
                .param("status", "ACTIVE")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.ascriptionResponseList", hasSize(9)))
                .andReturn();

        // Capture a seed archetype revisionId for FK references
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        JsonNode items = body.at("/_embedded/ascriptionResponseList");
        for (JsonNode item : items) {
            String defStr = item.get("definition").toString();
            if (defStr.contains("\"ArchetypeArchetype\"") || defStr.contains("archetype_archetype")) {
                seedArchetypeRevisionId = UUID.fromString(item.get("revisionId").asText());
            }
            if (defStr.contains("\"StructureArchetype\"") || defStr.contains("structure_archetype")) {
                structureArchetypeRevisionId = UUID.fromString(item.get("revisionId").asText());
            }
            if (defStr.contains("\"MechanismArchetype\"") || defStr.contains("mechanism_archetype")) {
                mechanismArchetypeRevisionId = UUID.fromString(item.get("revisionId").asText());
            }
        }

        // If we didn't match by definition content, just use the first few
        if (seedArchetypeRevisionId == null) {
            seedArchetypeRevisionId = UUID.fromString(items.get(0).get("revisionId").asText());
        }
        if (structureArchetypeRevisionId == null) {
            structureArchetypeRevisionId = UUID.fromString(items.get(1).get("revisionId").asText());
        }
        if (mechanismArchetypeRevisionId == null) {
            mechanismArchetypeRevisionId = UUID.fromString(items.get(2).get("revisionId").asText());
        }
    }

    // ================================================================
    // CREATE TESTS
    // ================================================================

    @Test
    @Order(10)
    void createArchetype_returnsDraftWithHalLinks() throws Exception {
        ObjectNode definition = mapper.createObjectNode();
        definition.put("name", "TestArchetype");
        definition.set("schema", mapper.createObjectNode()
                .put("type", "object")
                .put("$schema", "https://json-schema.org/draft/2020-12/schema"));

        ObjectNode request = mapper.createObjectNode();
        request.put("gsmType", "archetype");
        request.put("archetypeId", seedArchetypeRevisionId.toString());
        request.set("definition", definition);

        MvcResult result = mvc.perform(post("/api/v1/ascriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.gsmType", is("archetype")))
                .andExpect(jsonPath("$.status", is("DRAFT")))
                .andExpect(jsonPath("$.revisionId").exists())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$._links.self.href").exists())
                .andExpect(jsonPath("$._links.revisions.href").exists())
                .andExpect(jsonPath("$._links.transitions.href").exists())
                .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        createdArchetypeRevisionId = UUID.fromString(body.get("revisionId").asText());
        createdArchetypeId = UUID.fromString(body.get("id").asText());
    }

    @Test
    @Order(11)
    void createSiblingArchetypeRevision_sameIdDifferentRevision() throws Exception {
        ObjectNode definition = mapper.createObjectNode();
        definition.put("name", "TestArchetype-v2");
        definition.set("schema", mapper.createObjectNode()
                .put("type", "object")
                .put("$schema", "https://json-schema.org/draft/2020-12/schema"));

        ObjectNode request = mapper.createObjectNode();
        request.put("gsmType", "archetype");
        request.put("id", createdArchetypeId.toString()); // reuse same id
        request.put("archetypeId", seedArchetypeRevisionId.toString());
        request.set("definition", definition);

        MvcResult result = mvc.perform(post("/api/v1/ascriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(createdArchetypeId.toString())))
                .andExpect(jsonPath("$.revisionId", not(is(createdArchetypeRevisionId.toString()))))
                .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        siblingArchetypeRevisionId = UUID.fromString(body.get("revisionId").asText());
    }

    // ================================================================
    // READ TESTS
    // ================================================================

    @Test
    @Order(20)
    void getByRevisionId_returnsCorrectEntity() throws Exception {
        mvc.perform(get("/api/v1/ascriptions/{revisionId}", createdArchetypeRevisionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionId", is(createdArchetypeRevisionId.toString())))
                .andExpect(jsonPath("$.gsmType", is("archetype")))
                .andExpect(jsonPath("$.status", is("DRAFT")));
    }

    @Test
    @Order(21)
    void getByRevisionId_notFound_returns400() throws Exception {
        UUID bogus = UUID.randomUUID();
        mvc.perform(get("/api/v1/ascriptions/{revisionId}", bogus))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(22)
    void getRevisionHistory_returnsBothRevisions() throws Exception {
        mvc.perform(get("/api/v1/ascriptions/{revisionId}/revisions", createdArchetypeRevisionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    @Order(23)
    void getTransitions_showsInitialDraft() throws Exception {
        mvc.perform(get("/api/v1/ascriptions/{revisionId}/transitions", createdArchetypeRevisionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].postStatus", is("DRAFT")))
                .andExpect(jsonPath("$[0].preStatus").doesNotExist());
    }

    // ================================================================
    // LIFECYCLE: HAPPY PATH
    // ================================================================

    @Test
    @Order(30)
    void transition_draftToProposed() throws Exception {
        performTransition(createdArchetypeRevisionId, "PROPOSED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preStatus", is("DRAFT")))
                .andExpect(jsonPath("$.postStatus", is("PROPOSED")));
    }

    @Test
    @Order(31)
    void transition_proposedToApproved_assignsVersionAndTerminatesSibling() throws Exception {
        // The sibling (siblingArchetypeRevisionId) is still DRAFT
        performTransition(createdArchetypeRevisionId, "APPROVED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postStatus", is("APPROVED")));

        // Verify version was assigned
        mvc.perform(get("/api/v1/ascriptions/{revisionId}", createdArchetypeRevisionId))
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.status", is("APPROVED")));

        // Verify sibling was auto-terminated (DRAFT → ABANDONED)
        mvc.perform(get("/api/v1/ascriptions/{revisionId}", siblingArchetypeRevisionId))
                .andExpect(jsonPath("$.status", is("ABANDONED")));
    }

    @Test
    @Order(32)
    void transition_approvedToActive() throws Exception {
        performTransition(createdArchetypeRevisionId, "ACTIVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postStatus", is("ACTIVE")));

        mvc.perform(get("/api/v1/ascriptions/{revisionId}", createdArchetypeRevisionId))
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    @Order(33)
    void transition_activeToDeprecated() throws Exception {
        performTransition(createdArchetypeRevisionId, "DEPRECATED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postStatus", is("DEPRECATED")));
    }

    @Test
    @Order(34)
    void transition_deprecatedToRetired() throws Exception {
        performTransition(createdArchetypeRevisionId, "RETIRED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postStatus", is("RETIRED")));
    }

    // ================================================================
    // LIFECYCLE: INVALID TRANSITIONS
    // ================================================================

    @Test
    @Order(40)
    void transition_retiredIsTerminal_rejects() throws Exception {
        // RETIRED is terminal — any transition attempt should fail
        performTransition(createdArchetypeRevisionId, "ACTIVE")
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(41)
    void transition_abandonedIsTerminal_rejects() throws Exception {
        // siblingArchetypeRevisionId was auto-ABANDONED
        performTransition(siblingArchetypeRevisionId, "PROPOSED")
                .andExpect(status().isBadRequest());
    }

    // ================================================================
    // STRUCTURE + MECHANISM (FK CHAIN)
    // ================================================================

    @Test
    @Order(50)
    void createStructure_withArchetypeFk() throws Exception {
        ObjectNode definition = mapper.createObjectNode();
        definition.put("purpose", "test-payment-processing");

        ObjectNode request = mapper.createObjectNode();
        request.put("gsmType", "structure");
        request.put("archetypeId", structureArchetypeRevisionId.toString());
        request.set("definition", definition);

        MvcResult result = mvc.perform(post("/api/v1/ascriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gsmType", is("structure")))
                .andExpect(jsonPath("$.status", is("DRAFT")))
                .andReturn();

        createdStructureRevisionId = UUID.fromString(
                mapper.readTree(result.getResponse().getContentAsString())
                        .get("revisionId").asText());
    }

    @Test
    @Order(51)
    void createMechanism_withStructureFk() throws Exception {
        ObjectNode definition = mapper.createObjectNode();
        definition.put("function", "PaymentValidation");
        definition.put("rule", "on(\"PaymentRequest\")\nresult = sys.emit(\"PaymentResult\", {\"ok\": True})");
        definition.put("ruleLanguage", "STARLARK");

        ObjectNode request = mapper.createObjectNode();
        request.put("gsmType", "mechanism");
        request.put("archetypeId", mechanismArchetypeRevisionId.toString());
        request.put("structureId", createdStructureRevisionId.toString());
        request.set("definition", definition);

        MvcResult result = mvc.perform(post("/api/v1/ascriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gsmType", is("mechanism")))
                .andExpect(jsonPath("$.structureId", is(createdStructureRevisionId.toString())))
                .andReturn();

        createdMechanismRevisionId = UUID.fromString(
                mapper.readTree(result.getResponse().getContentAsString())
                        .get("revisionId").asText());
    }

    @Test
    @Order(52)
    void createMechanism_missingStructureId_returns400() throws Exception {
        ObjectNode definition = mapper.createObjectNode();
        definition.put("function", "Orphan");

        ObjectNode request = mapper.createObjectNode();
        request.put("gsmType", "mechanism");
        request.put("archetypeId", mechanismArchetypeRevisionId.toString());
        // structureId intentionally omitted
        request.set("definition", definition);

        mvc.perform(post("/api/v1/ascriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(53)
    void createMechanism_bogusStructureId_returns400() throws Exception {
        ObjectNode definition = mapper.createObjectNode();
        definition.put("function", "Orphan");

        ObjectNode request = mapper.createObjectNode();
        request.put("gsmType", "mechanism");
        request.put("archetypeId", mechanismArchetypeRevisionId.toString());
        request.put("structureId", UUID.randomUUID().toString());
        request.set("definition", definition);

        mvc.perform(post("/api/v1/ascriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ================================================================
    // PAGINATION
    // ================================================================

    @Test
    @Order(60)
    void listWithPagination_returnsPageMetadata() throws Exception {
        mvc.perform(get("/api/v1/ascriptions")
                .param("type", "archetype")
                .param("page", "0")
                .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size", is(5)))
                .andExpect(jsonPath("$.page.number", is(0)))
                .andExpect(jsonPath("$.page.totalElements", greaterThanOrEqualTo(9)));
    }

    // ================================================================
    // ACTIVATION CASCADE
    // ================================================================

    @Test
    @Order(70)
    void activationCascade_previousActiveBecomes_deprecated() throws Exception {
        // Create a new structure revision, progress it to ACTIVE
        ObjectNode def = mapper.createObjectNode().put("purpose", "cascade-test");
        ObjectNode req = mapper.createObjectNode();
        req.put("gsmType", "structure");
        req.put("archetypeId", structureArchetypeRevisionId.toString());
        req.set("definition", def);

        MvcResult r1 = mvc.perform(post("/api/v1/ascriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID rev1 = UUID.fromString(mapper.readTree(r1.getResponse().getContentAsString())
                .get("revisionId").asText());
        UUID structId = UUID.fromString(mapper.readTree(r1.getResponse().getContentAsString())
                .get("id").asText());

        // Progress rev1: DRAFT → PROPOSED → APPROVED → ACTIVE
        performTransition(rev1, "PROPOSED");
        performTransition(rev1, "APPROVED");
        performTransition(rev1, "ACTIVE");

        // Create second revision of same structure
        ObjectNode def2 = mapper.createObjectNode().put("purpose", "cascade-test-v2");
        ObjectNode req2 = mapper.createObjectNode();
        req2.put("gsmType", "structure");
        req2.put("id", structId.toString());
        req2.put("archetypeId", structureArchetypeRevisionId.toString());
        req2.set("definition", def2);

        MvcResult r2 = mvc.perform(post("/api/v1/ascriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req2)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID rev2 = UUID.fromString(mapper.readTree(r2.getResponse().getContentAsString())
                .get("revisionId").asText());

        // Progress rev2: DRAFT → PROPOSED → APPROVED → ACTIVE
        performTransition(rev2, "PROPOSED");
        performTransition(rev2, "APPROVED");
        performTransition(rev2, "ACTIVE");

        // Verify rev2 is ACTIVE
        mvc.perform(get("/api/v1/ascriptions/{revisionId}", rev2))
                .andExpect(jsonPath("$.status", is("ACTIVE")));

        // Verify rev1 was cascaded to DEPRECATED
        mvc.perform(get("/api/v1/ascriptions/{revisionId}", rev1))
                .andExpect(jsonPath("$.status", is("DEPRECATED")));
    }

    // ================================================================
    // AUDIT TRAIL
    // ================================================================

    @Test
    @Order(80)
    void transitionAuditTrail_recordsAllChanges() throws Exception {
        // Create a fresh archetype, move through lifecycle, check trail
        ObjectNode def = mapper.createObjectNode().put("name", "AuditTest");
        def.set("schema", mapper.createObjectNode().put("type", "object"));
        ObjectNode req = mapper.createObjectNode();
        req.put("gsmType", "archetype");
        req.put("archetypeId", seedArchetypeRevisionId.toString());
        req.set("definition", def);

        MvcResult r = mvc.perform(post("/api/v1/ascriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID revId = UUID.fromString(mapper.readTree(r.getResponse().getContentAsString())
                .get("revisionId").asText());

        performTransition(revId, "PROPOSED");
        performTransition(revId, "APPROVED");
        performTransition(revId, "ACTIVE");

        // Should have 4 transitions: [null→DRAFT, DRAFT→PROPOSED, PROPOSED→APPROVED,
        // APPROVED→ACTIVE]
        mvc.perform(get("/api/v1/ascriptions/{revisionId}/transitions", revId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].postStatus", is("DRAFT")))
                .andExpect(jsonPath("$[1].preStatus", is("DRAFT")))
                .andExpect(jsonPath("$[1].postStatus", is("PROPOSED")))
                .andExpect(jsonPath("$[2].preStatus", is("PROPOSED")))
                .andExpect(jsonPath("$[2].postStatus", is("APPROVED")))
                .andExpect(jsonPath("$[3].preStatus", is("APPROVED")))
                .andExpect(jsonPath("$[3].postStatus", is("ACTIVE")));
    }

    // ================================================================
    // HELPERS
    // ================================================================

    private org.springframework.test.web.servlet.ResultActions performTransition(UUID revisionId, String targetStatus)
            throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("targetStatus", targetStatus);
        return mvc.perform(post("/api/v1/ascriptions/{revisionId}/transitions", revisionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }
}
