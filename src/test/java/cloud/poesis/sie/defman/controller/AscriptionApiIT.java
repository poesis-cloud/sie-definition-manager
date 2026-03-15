package cloud.poesis.sie.defman.controller;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cloud.poesis.sie.defman.client.SchemaRegistryClient;

/**
 * Integration tests for the Ascription REST API against a real PostgreSQL
 * instance
 * (Testcontainers). Flyway runs V1 migration, seeding 9 base archetypes.
 *
 * <p>
 * API shape: subjectType is derived from archetype's schema title; FK
 * references
 * live in the
 * statement payload; ascription history is queried via
 * {@code /history?definitionId=X&type=Y}.
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
        void listSeedArchetypes_returns9Active() throws Exception {
                MvcResult result = mvc.perform(
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
                statement.set(
                                "schema",
                                mapper
                                                .createObjectNode()
                                                .put("type", "object")
                                                .put("title", "TestArchetype")
                                                .put("$schema", "https://json-schema.org/draft/2020-12/schema"));

                ObjectNode request = mapper.createObjectNode();
                request.put("archetypeId", seedArchetypeId.toString());
                request.set("statement", statement);

                MvcResult result = mvc.perform(
                                post("/api/v1/ascriptions")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(mapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(header().exists("Location"))
                                .andExpect(jsonPath("$.subjectType", is("archetype")))
                                .andExpect(jsonPath("$.status", is("DRAFT")))
                                .andExpect(jsonPath("$.id").exists())
                                .andExpect(jsonPath("$.definitionId").exists())
                                .andExpect(jsonPath("$.version", is(0)))
                                .andExpect(jsonPath("$._links.self.href").exists())
                                .andExpect(jsonPath("$._links.history.href").exists())
                                .andExpect(jsonPath("$._links.transitions.href").exists())
                                .andReturn();

                JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
                createdArchetypeId = UUID.fromString(body.get("id").asText());
                createdArchetypeDefinitionId = UUID.fromString(body.get("definitionId").asText());
        }

        @Test
        @Order(11)
        void createSiblingAscription_sameDefinitionDifferentAscription() throws Exception {
                ObjectNode statement = mapper.createObjectNode();
                statement.set(
                                "schema",
                                mapper
                                                .createObjectNode()
                                                .put("type", "object")
                                                .put("title", "TestArchetype-v2")
                                                .put("$schema", "https://json-schema.org/draft/2020-12/schema"));

                ObjectNode request = mapper.createObjectNode();
                request.put("definitionId", createdArchetypeDefinitionId.toString());
                request.put("archetypeId", seedArchetypeId.toString());
                request.set("statement", statement);

                MvcResult result = mvc.perform(
                                post("/api/v1/ascriptions")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(mapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.definitionId", is(createdArchetypeDefinitionId.toString())))
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
                                .andExpect(jsonPath("$.subjectType", is("archetype")))
                                .andExpect(jsonPath("$.status", is("DRAFT")));
        }

        @Test
        @Order(21)
        void getById_notFound_returns400() throws Exception {
                UUID bogus = UUID.randomUUID();
                mvc.perform(get("/api/v1/ascriptions/{id}", bogus)).andExpect(status().isBadRequest());
        }

        @Test
        @Order(22)
        void getAscriptionHistory_returnsBothAscriptions() throws Exception {
                mvc.perform(
                                get("/api/v1/ascriptions/history")
                                                .param("definitionId", createdArchetypeDefinitionId.toString())
                                                .param("type", "archetype"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$", hasSize(2)));
        }

        @Test
        @Order(23)
        void getTransitions_showsInitialDraft() throws Exception {
                mvc.perform(get("/api/v1/ascriptions/{id}/transitions", createdArchetypeId))
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
                performTransition(createdArchetypeId, "PROPOSED")
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.preStatus", is("DRAFT")))
                                .andExpect(jsonPath("$.postStatus", is("PROPOSED")));
        }

        @Test
        @Order(31)
        void transition_proposedToApproved_assignsVersionAndTerminatesSibling() throws Exception {
                performTransition(createdArchetypeId, "APPROVED")
                                .andExpect(status().isOk())
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
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.postStatus", is("ACTIVE")));

                mvc.perform(get("/api/v1/ascriptions/{id}", createdArchetypeId))
                                .andExpect(jsonPath("$.status", is("ACTIVE")));
        }

        @Test
        @Order(33)
        void transition_activeToDeprecated() throws Exception {
                performTransition(createdArchetypeId, "DEPRECATED")
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.postStatus", is("DEPRECATED")));
        }

        @Test
        @Order(34)
        void transition_deprecatedToRetired() throws Exception {
                performTransition(createdArchetypeId, "RETIRED")
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.postStatus", is("RETIRED")));
        }

        // ================================================================
        // LIFECYCLE: INVALID TRANSITIONS
        // ================================================================

        @Test
        @Order(40)
        void transition_retiredIsTerminal_rejects() throws Exception {
                performTransition(createdArchetypeId, "ACTIVE").andExpect(status().isBadRequest());
        }

        @Test
        @Order(41)
        void transition_abandonedIsTerminal_rejects() throws Exception {
                performTransition(siblingArchetypeId, "PROPOSED").andExpect(status().isBadRequest());
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
                                .andExpect(jsonPath("$.subjectType", is("structure")))
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
                                .andExpect(jsonPath("$.subjectType", is("mechanism")))
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
                UUID defId = UUID.fromString(
                                mapper.readTree(r1.getResponse().getContentAsString()).get("definitionId").asText());

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
                stmt.set("schema", mapper.createObjectNode().put("type", "object").put("title", "AuditTest"));
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
                MvcResult result = mvc.perform(get("/api/v1/openapi"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.openapi", is("3.1.0")))
                                .andExpect(jsonPath("$.info.title", is("SIE Definition Manager API")))
                                .andExpect(jsonPath("$.paths./ascriptions.post").exists())
                                .andExpect(jsonPath("$.paths./ascriptions.get").exists())
                                .andExpect(jsonPath("$.paths./ascriptions/{id}.get").exists())
                                .andExpect(jsonPath("$.paths./ascriptions/history.get").exists())
                                .andExpect(jsonPath("$.components.schemas.AscriptionRequest").exists())
                                .andExpect(jsonPath("$.components.schemas.AscriptionResponse").exists())
                                .andExpect(jsonPath("$.components.schemas.TransitionRequest").exists())
                                .andExpect(jsonPath("$.components.schemas.TransitionResponse").exists())
                                // Dynamic archetype-derived statement schemas
                                .andExpect(jsonPath("$.components.schemas.StructureArchetypeStatement").exists())
                                .andExpect(jsonPath("$.components.schemas.MechanismArchetypeStatement").exists())
                                .andExpect(jsonPath("$.components.schemas.EffectorArchetypeStatement").exists())
                                .andExpect(jsonPath("$.components.schemas.ReceptorArchetypeStatement").exists())
                                .andExpect(jsonPath("$.components.schemas.InteractionArchetypeStatement").exists())
                                .andExpect(jsonPath("$.components.schemas.InterfaceArchetypeStatement").exists())
                                .andExpect(jsonPath("$.components.schemas.DirectiveArchetypeStatement").exists())
                                .andExpect(jsonPath("$.components.schemas.NormArchetypeStatement").exists())
                                .andExpect(jsonPath("$.components.schemas.ArchetypeStatement").exists())
                                // StatementPayload oneOf references
                                .andExpect(jsonPath("$.components.schemas.StatementPayload.oneOf").isArray())
                                .andExpect(jsonPath("$.components.schemas.StatementPayload.oneOf", hasSize(10)))
                                .andReturn();

                // Verify a concrete archetype schema has the expected properties
                JsonNode spec = mapper.readTree(result.getResponse().getContentAsString());
                JsonNode paths = spec.get("paths");
                Set<String> actualPaths = new HashSet<>();
                for (Iterator<String> it = paths.fieldNames(); it.hasNext();) {
                        actualPaths.add(it.next());
                }
                Set<String> expectedPaths = Set.of(
                                "/ascriptions",
                                "/ascriptions/{id}",
                                "/ascriptions/history",
                                "/ascriptions/{id}/transitions");
                assert actualPaths.equals(expectedPaths) : "Unexpected core paths: " + actualPaths;

                JsonNode mechSchema = spec.at("/components/schemas/MechanismArchetypeStatement");
                assert mechSchema.has("properties") : "MechanismArchetypeStatement should have properties";
                assert mechSchema.get("properties").has("structure")
                                : "MechanismArchetypeStatement should have structure";
                assert mechSchema.get("properties").has("rule") : "MechanismArchetypeStatement should have rule";
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
