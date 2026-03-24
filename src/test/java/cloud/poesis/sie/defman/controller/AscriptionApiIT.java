package cloud.poesis.sie.defman.controller;

import static org.hamcrest.Matchers.endsWith;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
 * instance
 * (Testcontainers). Flyway runs V1 migration, seeding 9 base archetypes.
 *
 * <p>
 * API shape: Definition and Archetype references are conveyed via HAL
 * {@code _links} ({@code collection} for the definition-scoped ascription set,
 * {@code type} for archetype); FK references live in the statement payload.
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

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    // ================================================================
    // Shared state across ordered tests
    // ================================================================
    static UUID seedArchetypeId;
    static UUID structureArchetypeId;
    static UUID mechanismArchetypeId;

    static UUID createdArchetypeId;
    static UUID createdArchetypeDefinitionId;
    static UUID siblingArchetypeId;

    static UUID createdStructureId;
    static UUID createdMechanismId;

    // ================================================================
    // SEED DATA TESTS
    // ================================================================

    @Test
    @Order(1)
    void listSeedArchetypes_returns8Active() throws Exception {
        MvcResult result = mvc.perform(
                get("/api/v1/ascriptions")
                        .param("type", "archetype")
                        .param("status", "ACTIVE")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.ascriptions", hasSize(8)))
                .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        JsonNode items = body.at("/_embedded/ascriptions");
        for (JsonNode item : items) {
            String stmtStr = item.get("statement").toString();
            if (stmtStr.contains("\"title\":\"Archetype\"")) {
                seedArchetypeId = UUID.fromString(item.get("id").asText());
            }
            if (stmtStr.contains("\"title\":\"StructureArchetype\"")) {
                structureArchetypeId = UUID.fromString(item.get("id").asText());
            }
            if (stmtStr.contains("\"title\":\"MechanismArchetype\"")) {
                mechanismArchetypeId = UUID.fromString(item.get("id").asText());
            }
        }

        if (seedArchetypeId == null) {
            seedArchetypeId = UUID.fromString(items.get(0).get("id").asText());
        }
        if (structureArchetypeId == null) {
            structureArchetypeId = UUID.fromString(items.get(1).get("id").asText());
        }
        if (mechanismArchetypeId == null) {
            mechanismArchetypeId = UUID.fromString(items.get(2).get("id").asText());
        }
    }

    // ================================================================
    // CREATE TESTS
    // ================================================================

    @Test
    @Order(10)
    void createArchetype_returnsDraftWithHalLinks() throws Exception {
        ObjectNode statement = mapper.createObjectNode();
        statement.put("type", "object");
        statement.put("title", "TestArchetype");
        statement.put("$schema", "https://json-schema.org/draft/2020-12/schema");

        ObjectNode request = mapper.createObjectNode();
        request.put("archetypeId", seedArchetypeId.toString());
        request.set("statement", statement);

        MvcResult result = mvc.perform(
                post("/api/v1/ascriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status", is("DRAFT")))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.version", is(0)))
                .andExpect(jsonPath("$._links.self.href").exists())
                .andExpect(jsonPath("$._links.collection.href").exists())
                .andExpect(jsonPath("$._links.type.href").exists())
                .andExpect(jsonPath("$._links.describedby.href").exists())
                .andExpect(jsonPath("$._links.create-form.href").exists())
                .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        createdArchetypeId = UUID.fromString(body.get("id").asText());
        // Extract definition ID from collection href: .../definitions/{defId}/ascriptions
        String collectionHref = body.at("/_links/collection/href").asText();
        String[] segments = collectionHref.split("/");
        createdArchetypeDefinitionId = UUID.fromString(segments[segments.length - 2]);
    }

    @Test
    @Order(11)
    void createSiblingAscription_sameDefinitionDifferentAscription() throws Exception {
        ObjectNode statement = mapper.createObjectNode();
        statement.put("type", "object");
        statement.put("title", "TestArchetype"); // same title — identity-bound
        statement.put("description", "Revised schema"); // differ on non-identity field
        statement.put("$schema", "https://json-schema.org/draft/2020-12/schema");

        ObjectNode request = mapper.createObjectNode();
        request.put("definitionId", createdArchetypeDefinitionId.toString());
        request.put("archetypeId", seedArchetypeId.toString());
        request.set("statement", statement);

        MvcResult result = mvc.perform(
                post("/api/v1/ascriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$._links.collection.href",
                        endsWith("/definitions/" + createdArchetypeDefinitionId + "/ascriptions")))
                .andExpect(jsonPath("$.id", not(is(createdArchetypeId.toString()))))
                .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        siblingArchetypeId = UUID.fromString(body.get("id").asText());
    }

    // ================================================================
    // READ TESTS
    // ================================================================

    @Test
    @Order(20)
    void getById_returnsCorrectEntity() throws Exception {
        mvc.perform(get("/api/v1/ascriptions/{id}", createdArchetypeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(createdArchetypeId.toString())))
                .andExpect(jsonPath("$._links.collection.href").exists())
                .andExpect(jsonPath("$._links.type.href").exists())
                .andExpect(jsonPath("$._links.describedby.href").exists())
                .andExpect(jsonPath("$.status", is("DRAFT")));
    }

    @Test
    @Order(21)
    void getById_notFound_returns400() throws Exception {
        UUID bogus = UUID.randomUUID();
        mvc.perform(get("/api/v1/ascriptions/{id}", bogus)).andExpect(status().isNotFound());
    }

    // ================================================================
    // READ: TRANSITIONS
    // ================================================================

    @Test
    @Order(23)
    void getTransitions_showsInitialDraft() throws Exception {
        mvc.perform(get("/api/v1/ascriptions/{id}/transitions", createdArchetypeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions", hasSize(1)))
                .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions[0].postStatus", is("DRAFT")))
                .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions[0].preStatus").doesNotExist());
    }

    // ================================================================
    // LIFECYCLE: HAPPY PATH
    // ================================================================

    @Test
    @Order(30)
    void transition_draftToProposed() throws Exception {
        performTransition(createdArchetypeId, "PROPOSED")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preStatus", is("DRAFT")))
                .andExpect(jsonPath("$.postStatus", is("PROPOSED")));
    }

    @Test
    @Order(31)
    void transition_proposedToApproved_assignsVersionAndTerminatesSibling() throws Exception {
        performTransition(createdArchetypeId, "APPROVED")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postStatus", is("APPROVED")));

        mvc.perform(get("/api/v1/ascriptions/{id}", createdArchetypeId))
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.status", is("APPROVED")));

        mvc.perform(get("/api/v1/ascriptions/{id}", siblingArchetypeId))
                .andExpect(jsonPath("$.status", is("ABANDONED")));
    }

    @Test
    @Order(32)
    void transition_approvedToActive() throws Exception {
        performTransition(createdArchetypeId, "ACTIVE")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postStatus", is("ACTIVE")));

        mvc.perform(get("/api/v1/ascriptions/{id}", createdArchetypeId))
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    @Order(33)
    void transition_activeToDeprecated() throws Exception {
        performTransition(createdArchetypeId, "DEPRECATED")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postStatus", is("DEPRECATED")));
    }

    @Test
    @Order(34)
    void transition_deprecatedToRetired() throws Exception {
        performTransition(createdArchetypeId, "RETIRED")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postStatus", is("RETIRED")));
    }

    // ================================================================
    // LIFECYCLE: INVALID TRANSITIONS
    // ================================================================

    @Test
    @Order(40)
    void transition_retiredIsTerminal_rejects() throws Exception {
        performTransition(createdArchetypeId, "ACTIVE").andExpect(status().isConflict());
    }

    @Test
    @Order(41)
    void transition_abandonedIsTerminal_rejects() throws Exception {
        performTransition(siblingArchetypeId, "PROPOSED").andExpect(status().isConflict());
    }

    // ================================================================
    // STRUCTURE + MECHANISM (FK CHAIN VIA DEFINITION)
    // ================================================================

    @Test
    @Order(50)
    void createStructure_withArchetypeFk() throws Exception {
        ObjectNode statement = mapper.createObjectNode();
        statement.put("purpose", "test-payment-processing");

        ObjectNode request = mapper.createObjectNode();
        request.put("archetypeId", structureArchetypeId.toString());
        request.set("statement", statement);

        MvcResult result = mvc.perform(
                post("/api/v1/ascriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$._links.collection.href").exists())
                .andExpect(jsonPath("$._links.type.href").exists())
                .andExpect(jsonPath("$.status", is("DRAFT")))
                .andReturn();

        createdStructureId = UUID.fromString(
                mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    @Order(51)
    void createMechanism_withStructureFkInStatement() throws Exception {
        ObjectNode statement = mapper.createObjectNode();
        statement.put("function", "PaymentValidation");
        statement.put(
                "rule", "on(\"PaymentRequest\")\nresult = sys.emit(\"PaymentResult\", {\"ok\": True})");
        statement.put("structure", createdStructureId.toString());

        ObjectNode request = mapper.createObjectNode();
        request.put("archetypeId", mechanismArchetypeId.toString());
        request.set("statement", statement);

        MvcResult result = mvc.perform(
                post("/api/v1/ascriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$._links.collection.href").exists())
                .andExpect(jsonPath("$._links.type.href").exists())
                .andReturn();

        createdMechanismId = UUID.fromString(
                mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    @Order(52)
    void createMechanism_missingStructureInStatement_returns400() throws Exception {
        ObjectNode statement = mapper.createObjectNode();
        statement.put("function", "Orphan");
        statement.put("rule", "on(\"X\")\nsys.emit(\"Y\", {})");
        // structure intentionally omitted from statement

        ObjectNode request = mapper.createObjectNode();
        request.put("archetypeId", mechanismArchetypeId.toString());
        request.set("statement", statement);

        mvc.perform(
                post("/api/v1/ascriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(53)
    void createMechanism_bogusStructureInStatement_returns400() throws Exception {
        ObjectNode statement = mapper.createObjectNode();
        statement.put("function", "Orphan");
        statement.put("rule", "on(\"X\")\nsys.emit(\"Y\", {})");
        statement.put("structure", UUID.randomUUID().toString());

        ObjectNode request = mapper.createObjectNode();
        request.put("archetypeId", mechanismArchetypeId.toString());
        request.set("statement", statement);

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
        ObjectNode stmt = mapper.createObjectNode().put("purpose", "cascade-test");
        ObjectNode req = mapper.createObjectNode();
        req.put("archetypeId", structureArchetypeId.toString());
        req.set("statement", stmt);

        MvcResult r1 = mvc.perform(
                post("/api/v1/ascriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID asc1 = UUID.fromString(
                mapper.readTree(r1.getResponse().getContentAsString()).get("id").asText());
        JsonNode body1 = mapper.readTree(r1.getResponse().getContentAsString());
        // Extract definition ID from collection href: .../definitions/{defId}/ascriptions
        String collectionHref1 = body1.at("/_links/collection/href").asText();
        String[] segs = collectionHref1.split("/");
        UUID defId = UUID.fromString(segs[segs.length - 2]);

        performTransition(asc1, "PROPOSED");
        performTransition(asc1, "APPROVED");
        performTransition(asc1, "ACTIVE");

        ObjectNode stmt2 = mapper.createObjectNode().put("purpose", "cascade-test-v2");
        ObjectNode req2 = mapper.createObjectNode();
        req2.put("definitionId", defId.toString());
        req2.put("archetypeId", structureArchetypeId.toString());
        req2.set("statement", stmt2);

        MvcResult r2 = mvc.perform(
                post("/api/v1/ascriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req2)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID asc2 = UUID.fromString(
                mapper.readTree(r2.getResponse().getContentAsString()).get("id").asText());

        performTransition(asc2, "PROPOSED");
        performTransition(asc2, "APPROVED");
        performTransition(asc2, "ACTIVE");

        mvc.perform(get("/api/v1/ascriptions/{id}", asc2))
                .andExpect(jsonPath("$.status", is("ACTIVE")));

        mvc.perform(get("/api/v1/ascriptions/{id}", asc1))
                .andExpect(jsonPath("$.status", is("DEPRECATED")));
    }

    // ================================================================
    // AUDIT TRAIL
    // ================================================================

    @Test
    @Order(80)
    void transitionAuditTrail_recordsAllChanges() throws Exception {
        ObjectNode stmt = mapper.createObjectNode();
        stmt.put("type", "object");
        stmt.put("title", "AuditTest");
        ObjectNode req = mapper.createObjectNode();
        req.put("archetypeId", seedArchetypeId.toString());
        req.set("statement", stmt);

        MvcResult r = mvc.perform(
                post("/api/v1/ascriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID ascId = UUID.fromString(
                mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());

        performTransition(ascId, "PROPOSED");
        performTransition(ascId, "APPROVED");
        performTransition(ascId, "ACTIVE");

        // Should have 4 transitions: [null→DRAFT, DRAFT→PROPOSED, PROPOSED→APPROVED,
        // APPROVED→ACTIVE]
        mvc.perform(get("/api/v1/ascriptions/{id}/transitions", ascId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions", hasSize(4)))
                .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions[0].postStatus", is("DRAFT")))
                .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions[1].preStatus", is("DRAFT")))
                .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions[1].postStatus", is("PROPOSED")))
                .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions[2].preStatus", is("PROPOSED")))
                .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions[2].postStatus", is("APPROVED")))
                .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions[3].preStatus", is("APPROVED")))
                .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions[3].postStatus", is("ACTIVE")));
    }

    // ================================================================
    // DYNAMIC OPENAPI
    // ================================================================

    @Test
    @Order(90)
    void openApiSpec_containsDynamicArchetypeSchemas() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title", is("SIE Definition Manager API")))
                .andExpect(jsonPath("$.paths").exists())
                .andExpect(jsonPath("$.components.schemas").exists());
    }

    // ================================================================
    // QUERY FILTERS
    // ================================================================

    @Test
    @Order(100)
    void queryFilter_statementFilterWithoutArchetype_returns400() throws Exception {
        mvc.perform(
                get("/api/v1/ascriptions")
                        .param("type", "structure")
                        .param("statement.purpose", "foo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type",
                        is("gsm:rules/ascription/query/archetype-required")));
    }

    @Test
    @Order(101)
    void queryFilter_unknownArchetypeTitle_returns400() throws Exception {
        mvc.perform(
                get("/api/v1/ascriptions")
                        .param("type", "structure")
                        .param("archetype", "NonExistentArchetype"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type",
                        is("gsm:rules/ascription/query/archetype-not-found")));
    }

    @Test
    @Order(102)
    void queryFilter_nonQueryableProperty_returns400() throws Exception {
        mvc.perform(
                get("/api/v1/ascriptions")
                        .param("type", "structure")
                        .param("archetype", "StructureArchetype")
                        .param("statement.purpose", "foo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type",
                        is("gsm:rules/ascription/query/property-not-queryable")));
    }

    @Test
    @Order(103)
    void queryFilter_archetypeByTitle_returnsFiltered() throws Exception {
        mvc.perform(
                get("/api/v1/ascriptions")
                        .param("type", "archetype")
                        .param("archetype", "Archetype")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.ascriptions",
                        hasSize(greaterThanOrEqualTo(8))));
    }

    @Test
    @Order(104)
    void queryFilter_archetypeByUuid_returnsFiltered() throws Exception {
        mvc.perform(
                get("/api/v1/ascriptions")
                        .param("type", "archetype")
                        .param("archetype", seedArchetypeId.toString())
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.ascriptions",
                        hasSize(greaterThanOrEqualTo(8))));
    }

    // ================================================================
    // HELPERS
    // ================================================================

    private org.springframework.test.web.servlet.ResultActions performTransition(
            UUID id, String targetStatus) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("targetStatus", targetStatus);
        return mvc.perform(
                post("/api/v1/ascriptions/{id}/transitions", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)));
    }
}
