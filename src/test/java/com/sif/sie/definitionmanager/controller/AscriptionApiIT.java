package com.sif.sie.definitionmanager.controller;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sif.sie.definitionmanager.client.SchemaRegistryClient;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

/**
  * Integration tests for the Ascription REST API against a real PostgreSQL instance
  * (Testcontainers). Flyway runs V1-V3 migrations, seeding 9 base archetypes.
  *
  * <p>API shape: gsmType is derived from archetype's schema URI; FK references live in the
  * definition payload; revisions are queried via {@code /revisions?id=X&type=Y}.
  */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("tc")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AscriptionApiIT {

    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16.3-alpine");

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
    }

    @MockitoBean SchemaRegistryClient schemaRegistryClient;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

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
        MvcResult result =
                mvc.perform(
                                get("/api/v1/ascriptions")
                                        .param("type", "archetype")
                                        .param("status", "ACTIVE")
                                        .param("size", "20"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$._embedded.ascriptionResponseList", hasSize(9)))
                        .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        JsonNode items = body.at("/_embedded/ascriptionResponseList");
        for (JsonNode item : items) {
            String defStr = item.get("definition").toString();
            if (defStr.contains("Archetype.schema.json")) {
                seedArchetypeRevisionId = UUID.fromString(item.get("revisionId").asText());
            }
            if (defStr.contains("Structure.schema.json")) {
                structureArchetypeRevisionId = UUID.fromString(item.get("revisionId").asText());
            }
            if (defStr.contains("Mechanism.schema.json")) {
                mechanismArchetypeRevisionId = UUID.fromString(item.get("revisionId").asText());
            }
        }

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
        definition.set(
                "schema",
                mapper
                        .createObjectNode()
                        .put("type", "object")
                        .put("$schema", "https://json-schema.org/draft/2020-12/schema"));

        ObjectNode request = mapper.createObjectNode();
        request.put("archetypeId", seedArchetypeRevisionId.toString());
        request.set("definition", definition);

        MvcResult result =
                mvc.perform(
                                post("/api/v1/ascriptions")
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
        definition.set(
                "schema",
                mapper
                        .createObjectNode()
                        .put("type", "object")
                        .put("$schema", "https://json-schema.org/draft/2020-12/schema"));

        ObjectNode request = mapper.createObjectNode();
        request.put("id", createdArchetypeId.toString());
        request.put("archetypeId", seedArchetypeRevisionId.toString());
        request.set("definition", definition);

        MvcResult result =
                mvc.perform(
                                post("/api/v1/ascriptions")
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
        mvc.perform(get("/api/v1/ascriptions/{revisionId}", bogus)).andExpect(status().isBadRequest());
    }

    @Test
    @Order(22)
    void getRevisionHistory_returnsBothRevisions() throws Exception {
        mvc.perform(
                        get("/api/v1/ascriptions/revisions")
                                .param("id", createdArchetypeId.toString())
                                .param("type", "archetype"))
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
        performTransition(createdArchetypeRevisionId, "APPROVED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postStatus", is("APPROVED")));

        mvc.perform(get("/api/v1/ascriptions/{revisionId}", createdArchetypeRevisionId))
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.status", is("APPROVED")));

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
        performTransition(createdArchetypeRevisionId, "ACTIVE").andExpect(status().isBadRequest());
    }

    @Test
    @Order(41)
    void transition_abandonedIsTerminal_rejects() throws Exception {
        performTransition(siblingArchetypeRevisionId, "PROPOSED").andExpect(status().isBadRequest());
    }

    // ================================================================
    // STRUCTURE + MECHANISM (FK CHAIN VIA DEFINITION)
    // ================================================================

    @Test
    @Order(50)
    void createStructure_withArchetypeFk() throws Exception {
        ObjectNode definition = mapper.createObjectNode();
        definition.put("purpose", "test-payment-processing");

        ObjectNode request = mapper.createObjectNode();
        request.put("archetypeId", structureArchetypeRevisionId.toString());
        request.set("definition", definition);

        MvcResult result =
                mvc.perform(
                                post("/api/v1/ascriptions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(mapper.writeValueAsString(request)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.gsmType", is("structure")))
                        .andExpect(jsonPath("$.status", is("DRAFT")))
                        .andReturn();

        createdStructureRevisionId =
                UUID.fromString(
                        mapper.readTree(result.getResponse().getContentAsString()).get("revisionId").asText());
    }

    @Test
    @Order(51)
    void createMechanism_withStructureFkInDefinition() throws Exception {
        ObjectNode definition = mapper.createObjectNode();
        definition.put("function", "PaymentValidation");
        definition.put(
                "rule", "on(\"PaymentRequest\")\nresult = sys.emit(\"PaymentResult\", {\"ok\": True})");
        definition.put("ruleLanguage", "STARLARK");
        definition.put("structureId", createdStructureRevisionId.toString());

        ObjectNode request = mapper.createObjectNode();
        request.put("archetypeId", mechanismArchetypeRevisionId.toString());
        request.set("definition", definition);

        MvcResult result =
                mvc.perform(
                                post("/api/v1/ascriptions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(mapper.writeValueAsString(request)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.gsmType", is("mechanism")))
                        .andReturn();

        createdMechanismRevisionId =
                UUID.fromString(
                        mapper.readTree(result.getResponse().getContentAsString()).get("revisionId").asText());
    }

    @Test
    @Order(52)
    void createMechanism_missingStructureInDefinition_returns400() throws Exception {
        ObjectNode definition = mapper.createObjectNode();
        definition.put("function", "Orphan");
        definition.put("rule", "on(\"X\")\nsys.emit(\"Y\", {})");
        definition.put("ruleLanguage", "STARLARK");
        // structureId intentionally omitted from definition

        ObjectNode request = mapper.createObjectNode();
        request.put("archetypeId", mechanismArchetypeRevisionId.toString());
        request.set("definition", definition);

        mvc.perform(
                        post("/api/v1/ascriptions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(53)
    void createMechanism_bogusStructureInDefinition_returns400() throws Exception {
        ObjectNode definition = mapper.createObjectNode();
        definition.put("function", "Orphan");
        definition.put("rule", "on(\"X\")\nsys.emit(\"Y\", {})");
        definition.put("ruleLanguage", "STARLARK");
        definition.put("structureId", UUID.randomUUID().toString());

        ObjectNode request = mapper.createObjectNode();
        request.put("archetypeId", mechanismArchetypeRevisionId.toString());
        request.set("definition", definition);

        mvc.perform(
                        post("/api/v1/ascriptions")
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
        mvc.perform(
                        get("/api/v1/ascriptions")
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
        ObjectNode def = mapper.createObjectNode().put("purpose", "cascade-test");
        ObjectNode req = mapper.createObjectNode();
        req.put("archetypeId", structureArchetypeRevisionId.toString());
        req.set("definition", def);

        MvcResult r1 =
                mvc.perform(
                                post("/api/v1/ascriptions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(mapper.writeValueAsString(req)))
                        .andExpect(status().isCreated())
                        .andReturn();
        UUID rev1 =
                UUID.fromString(
                        mapper.readTree(r1.getResponse().getContentAsString()).get("revisionId").asText());
        UUID structId =
                UUID.fromString(mapper.readTree(r1.getResponse().getContentAsString()).get("id").asText());

        performTransition(rev1, "PROPOSED");
        performTransition(rev1, "APPROVED");
        performTransition(rev1, "ACTIVE");

        ObjectNode def2 = mapper.createObjectNode().put("purpose", "cascade-test-v2");
        ObjectNode req2 = mapper.createObjectNode();
        req2.put("id", structId.toString());
        req2.put("archetypeId", structureArchetypeRevisionId.toString());
        req2.set("definition", def2);

        MvcResult r2 =
                mvc.perform(
                                post("/api/v1/ascriptions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(mapper.writeValueAsString(req2)))
                        .andExpect(status().isCreated())
                        .andReturn();
        UUID rev2 =
                UUID.fromString(
                        mapper.readTree(r2.getResponse().getContentAsString()).get("revisionId").asText());

        performTransition(rev2, "PROPOSED");
        performTransition(rev2, "APPROVED");
        performTransition(rev2, "ACTIVE");

        mvc.perform(get("/api/v1/ascriptions/{revisionId}", rev2))
                .andExpect(jsonPath("$.status", is("ACTIVE")));

        mvc.perform(get("/api/v1/ascriptions/{revisionId}", rev1))
                .andExpect(jsonPath("$.status", is("DEPRECATED")));
    }

    // ================================================================
    // AUDIT TRAIL
    // ================================================================

    @Test
    @Order(80)
    void transitionAuditTrail_recordsAllChanges() throws Exception {
        ObjectNode def = mapper.createObjectNode().put("name", "AuditTest");
        def.set("schema", mapper.createObjectNode().put("type", "object"));
        ObjectNode req = mapper.createObjectNode();
        req.put("archetypeId", seedArchetypeRevisionId.toString());
        req.set("definition", def);

        MvcResult r =
                mvc.perform(
                                post("/api/v1/ascriptions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(mapper.writeValueAsString(req)))
                        .andExpect(status().isCreated())
                        .andReturn();
        UUID revId =
                UUID.fromString(
                        mapper.readTree(r.getResponse().getContentAsString()).get("revisionId").asText());

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
    // DYNAMIC OPENAPI
    // ================================================================

    @Test
    @Order(90)
    void openApiSpec_containsDynamicArchetypeSchemas() throws Exception {
        MvcResult result =
                mvc.perform(get("/api/v1/openapi"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.openapi", is("3.1.0")))
                        .andExpect(jsonPath("$.info.title", is("SIE Definition Manager API")))
                        .andExpect(jsonPath("$.paths./ascriptions.post").exists())
                        .andExpect(jsonPath("$.paths./ascriptions.get").exists())
                        .andExpect(jsonPath("$.paths./ascriptions/{revisionId}.get").exists())
                        .andExpect(jsonPath("$.paths./ascriptions/revisions.get").exists())
                        .andExpect(jsonPath("$.components.schemas.AscriptionRequest").exists())
                        .andExpect(jsonPath("$.components.schemas.AscriptionResponse").exists())
                        .andExpect(jsonPath("$.components.schemas.TransitionRequest").exists())
                        .andExpect(jsonPath("$.components.schemas.TransitionResponse").exists())
                        // Dynamic archetype-derived definition schemas
                        .andExpect(jsonPath("$.components.schemas.StructureDefinition").exists())
                        .andExpect(jsonPath("$.components.schemas.MechanismDefinition").exists())
                        .andExpect(jsonPath("$.components.schemas.EffectorDefinition").exists())
                        .andExpect(jsonPath("$.components.schemas.ReceptorDefinition").exists())
                        .andExpect(jsonPath("$.components.schemas.InteractionDefinition").exists())
                        .andExpect(jsonPath("$.components.schemas.InterfaceDefinition").exists())
                        .andExpect(jsonPath("$.components.schemas.DirectiveDefinition").exists())
                        .andExpect(jsonPath("$.components.schemas.NormDefinition").exists())
                        .andExpect(jsonPath("$.components.schemas.ArchetypeDefinition").exists())
                        // DefinitionPayload oneOf references
                        .andExpect(jsonPath("$.components.schemas.DefinitionPayload.oneOf").isArray())
                        .andExpect(jsonPath("$.components.schemas.DefinitionPayload.oneOf", hasSize(9)))
                        .andReturn();

        // Verify a concrete archetype schema has the expected properties
        JsonNode spec = mapper.readTree(result.getResponse().getContentAsString());
        JsonNode paths = spec.get("paths");
        Set<String> actualPaths = new HashSet<>();
        for (Iterator<String> it = paths.fieldNames(); it.hasNext(); ) {
            actualPaths.add(it.next());
        }
        Set<String> expectedPaths =
                Set.of(
                        "/ascriptions",
                        "/ascriptions/{revisionId}",
                        "/ascriptions/revisions",
                        "/ascriptions/{revisionId}/transitions");
        assert actualPaths.equals(expectedPaths) : "Unexpected core paths: " + actualPaths;

        JsonNode mechSchema = spec.at("/components/schemas/MechanismDefinition");
        assert mechSchema.has("properties") : "MechanismDefinition should have properties";
        assert mechSchema.get("properties").has("structureId")
                : "MechanismDefinition should have structureId";
        assert mechSchema.get("properties").has("rule") : "MechanismDefinition should have rule";
    }

    // ================================================================
    // HELPERS
    // ================================================================

    private org.springframework.test.web.servlet.ResultActions performTransition(
            UUID revisionId, String targetStatus) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("targetStatus", targetStatus);
        return mvc.perform(
                post("/api/v1/ascriptions/{revisionId}/transitions", revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)));
    }
}
