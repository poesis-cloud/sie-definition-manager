package cloud.poesis.sie.defman.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/**
 * Integration tests for Ascription lifecycle transitions against a real PostgreSQL instance
 * (Testcontainers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("tc")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AscriptionStatusTransitionResourceIT {

  @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16.3-alpine");

  @DynamicPropertySource
  static void pgProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", pg::getJdbcUrl);
    registry.add("spring.datasource.username", pg::getUsername);
    registry.add("spring.datasource.password", pg::getPassword);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;

  // ================================================================
  // Shared state across ordered tests
  // ================================================================
  static UUID seedArchetypeId;
  static UUID structureArchetypeId;

  static UUID createdArchetypeId;
  static UUID createdArchetypeDefinitionId;
  static UUID siblingArchetypeId;

  // ================================================================
  // SETUP: SEED DATA + ENTITY CREATION
  // ================================================================

  @Test
  @Order(1)
  void setup_listSeedArchetypes() throws Exception {
    MvcResult result =
        mvc.perform(
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
    }
  }

  @Test
  @Order(2)
  void setup_createArchetype() throws Exception {
    ObjectNode statement = mapper.createObjectNode();
    statement.put("type", "object");
    statement.put("title", "TransitionTestArchetype");
    statement.put("$schema", "https://json-schema.org/draft/2020-12/schema");

    ObjectNode request = mapper.createObjectNode();
    request.put("archetypeId", seedArchetypeId.toString());
    request.set("statement", statement);

    MvcResult result =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn();

    JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
    createdArchetypeId = UUID.fromString(body.get("id").asText());
    String collectionHref = body.at("/_links/collection/href").asText();
    String[] segments = collectionHref.split("/");
    createdArchetypeDefinitionId = UUID.fromString(segments[segments.length - 2]);
  }

  @Test
  @Order(3)
  void setup_createSibling() throws Exception {
    ObjectNode statement = mapper.createObjectNode();
    statement.put("type", "object");
    statement.put("title", "TransitionTestArchetype");
    statement.put("description", "Sibling version");
    statement.put("$schema", "https://json-schema.org/draft/2020-12/schema");

    ObjectNode request = mapper.createObjectNode();
    request.put("definitionId", createdArchetypeDefinitionId.toString());
    request.put("archetypeId", seedArchetypeId.toString());
    request.set("statement", statement);

    MvcResult result =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id", not(is(createdArchetypeId.toString()))))
            .andReturn();

    JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
    siblingArchetypeId = UUID.fromString(body.get("id").asText());
  }

  // ================================================================
  // READ: TRANSITIONS
  // ================================================================

  @Test
  @Order(10)
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
  @Order(20)
  void transition_draftToProposed() throws Exception {
    performTransition(createdArchetypeId, "PROPOSED")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.preStatus", is("DRAFT")))
        .andExpect(jsonPath("$.postStatus", is("PROPOSED")));
  }

  @Test
  @Order(21)
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
  @Order(22)
  void transition_approvedToActive() throws Exception {
    performTransition(createdArchetypeId, "ACTIVE")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.postStatus", is("ACTIVE")));

    mvc.perform(get("/api/v1/ascriptions/{id}", createdArchetypeId))
        .andExpect(jsonPath("$.status", is("ACTIVE")));
  }

  @Test
  @Order(23)
  void transition_activeToDeprecated() throws Exception {
    performTransition(createdArchetypeId, "DEPRECATED")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.postStatus", is("DEPRECATED")));
  }

  @Test
  @Order(24)
  void transition_deprecatedToRetired() throws Exception {
    performTransition(createdArchetypeId, "RETIRED")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.postStatus", is("RETIRED")));
  }

  // ================================================================
  // LIFECYCLE: INVALID TRANSITIONS (TERMINAL STATES)
  // ================================================================

  @Test
  @Order(30)
  void transition_retiredIsTerminal_rejects() throws Exception {
    performTransition(createdArchetypeId, "ACTIVE").andExpect(status().isConflict());
  }

  @Test
  @Order(31)
  void transition_abandonedIsTerminal_rejects() throws Exception {
    performTransition(siblingArchetypeId, "PROPOSED").andExpect(status().isConflict());
  }

  // ================================================================
  // ACTIVATION CASCADE
  // ================================================================

  @Test
  @Order(40)
  void activationCascade_previousActiveBecomes_deprecated() throws Exception {
    ObjectNode stmt = mapper.createObjectNode().put("purpose", "cascade-test");
    ObjectNode req = mapper.createObjectNode();
    req.put("archetypeId", structureArchetypeId.toString());
    req.set("statement", stmt);

    MvcResult r1 =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();
    UUID asc1 =
        UUID.fromString(mapper.readTree(r1.getResponse().getContentAsString()).get("id").asText());
    JsonNode body1 = mapper.readTree(r1.getResponse().getContentAsString());
    String collectionHref1 = body1.at("/_links/collection/href").asText();
    String[] segs = collectionHref1.split("/");
    UUID defId = UUID.fromString(segs[segs.length - 2]);

    performTransition(asc1, "PROPOSED");
    performTransition(asc1, "APPROVED");
    performTransition(asc1, "ACTIVE");

    ObjectNode stmt2 = mapper.createObjectNode().put("purpose", "cascade-test");
    ObjectNode req2 = mapper.createObjectNode();
    req2.put("definitionId", defId.toString());
    req2.put("archetypeId", structureArchetypeId.toString());
    req2.set("statement", stmt2);

    MvcResult r2 =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(req2)))
            .andExpect(status().isCreated())
            .andReturn();
    UUID asc2 =
        UUID.fromString(mapper.readTree(r2.getResponse().getContentAsString()).get("id").asText());

    performTransition(asc2, "PROPOSED").andExpect(status().isCreated());
    performTransition(asc2, "APPROVED").andExpect(status().isCreated());
    performTransition(asc2, "ACTIVE").andExpect(status().isCreated());

    mvc.perform(get("/api/v1/ascriptions/{id}", asc2))
        .andExpect(jsonPath("$.status", is("ACTIVE")));

    mvc.perform(get("/api/v1/ascriptions/{id}", asc1))
        .andExpect(jsonPath("$.status", is("DEPRECATED")));
  }

  // ================================================================
  // AUDIT TRAIL
  // ================================================================

  @Test
  @Order(50)
  void transitionAuditTrail_recordsAllChanges() throws Exception {
    ObjectNode stmt = mapper.createObjectNode();
    stmt.put("type", "object");
    stmt.put("title", "AuditTest");
    ObjectNode req = mapper.createObjectNode();
    req.put("archetypeId", seedArchetypeId.toString());
    req.set("statement", stmt);

    MvcResult r =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();
    UUID ascId =
        UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());

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
        .andExpect(
            jsonPath("$._embedded.ascriptionStatusTransitions[1].postStatus", is("PROPOSED")))
        .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions[2].preStatus", is("PROPOSED")))
        .andExpect(
            jsonPath("$._embedded.ascriptionStatusTransitions[2].postStatus", is("APPROVED")))
        .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions[3].preStatus", is("APPROVED")))
        .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions[3].postStatus", is("ACTIVE")));
  }

  // ================================================================
  // LIFECYCLE: SUSPEND / REACTIVATE
  // ================================================================

  @Test
  @Order(60)
  void lifecycle_activeToSuspendedToActiveToDeprecatedToRetired() throws Exception {
    ObjectNode stmt = mapper.createObjectNode().put("purpose", "suspend-test");
    ObjectNode req = mapper.createObjectNode();
    req.put("archetypeId", structureArchetypeId.toString());
    req.set("statement", stmt);

    MvcResult r =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();
    UUID ascId =
        UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());

    performTransition(ascId, "PROPOSED").andExpect(status().isCreated());
    performTransition(ascId, "APPROVED").andExpect(status().isCreated());
    performTransition(ascId, "ACTIVE").andExpect(status().isCreated());

    // ACTIVE → SUSPENDED
    performTransition(ascId, "SUSPENDED").andExpect(status().isCreated());
    mvc.perform(get("/api/v1/ascriptions/{id}", ascId))
        .andExpect(jsonPath("$.status", is("SUSPENDED")));

    // SUSPENDED → ACTIVE (reactivation)
    performTransition(ascId, "ACTIVE").andExpect(status().isCreated());
    mvc.perform(get("/api/v1/ascriptions/{id}", ascId))
        .andExpect(jsonPath("$.status", is("ACTIVE")));

    // ACTIVE → DEPRECATED → RETIRED
    performTransition(ascId, "DEPRECATED").andExpect(status().isCreated());
    performTransition(ascId, "RETIRED").andExpect(status().isCreated());
    mvc.perform(get("/api/v1/ascriptions/{id}", ascId))
        .andExpect(jsonPath("$.status", is("RETIRED")));
  }

  @Test
  @Order(61)
  void lifecycle_deprecatedToSuspended() throws Exception {
    ObjectNode stmt = mapper.createObjectNode().put("purpose", "depr-suspend-test");
    ObjectNode req = mapper.createObjectNode();
    req.put("archetypeId", structureArchetypeId.toString());
    req.set("statement", stmt);

    MvcResult r =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();
    UUID ascId =
        UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());

    performTransition(ascId, "PROPOSED").andExpect(status().isCreated());
    performTransition(ascId, "APPROVED").andExpect(status().isCreated());
    performTransition(ascId, "ACTIVE").andExpect(status().isCreated());
    performTransition(ascId, "DEPRECATED").andExpect(status().isCreated());

    // DEPRECATED → SUSPENDED
    performTransition(ascId, "SUSPENDED").andExpect(status().isCreated());
    mvc.perform(get("/api/v1/ascriptions/{id}", ascId))
        .andExpect(jsonPath("$.status", is("SUSPENDED")));
  }

  @Test
  @Order(70)
  void lifecycle_draftToAbandoned() throws Exception {
    ObjectNode stmt = mapper.createObjectNode();
    stmt.put("type", "object");
    stmt.put("title", "AbandonTest");
    ObjectNode req = mapper.createObjectNode();
    req.put("archetypeId", seedArchetypeId.toString());
    req.set("statement", stmt);

    MvcResult r =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();
    UUID ascId =
        UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());

    performTransition(ascId, "ABANDONED")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.postStatus", is("ABANDONED")));

    mvc.perform(get("/api/v1/ascriptions/{id}", ascId))
        .andExpect(jsonPath("$.status", is("ABANDONED")));

    // ABANDONED is terminal
    performTransition(ascId, "DRAFT").andExpect(status().isConflict());
  }

  @Test
  @Order(71)
  void lifecycle_proposedToRejected() throws Exception {
    ObjectNode stmt = mapper.createObjectNode();
    stmt.put("type", "object");
    stmt.put("title", "RejectTest");
    ObjectNode req = mapper.createObjectNode();
    req.put("archetypeId", seedArchetypeId.toString());
    req.set("statement", stmt);

    MvcResult r =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();
    UUID ascId =
        UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());

    performTransition(ascId, "PROPOSED").andExpect(status().isCreated());
    performTransition(ascId, "REJECTED")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.postStatus", is("REJECTED")));

    mvc.perform(get("/api/v1/ascriptions/{id}", ascId))
        .andExpect(jsonPath("$.status", is("REJECTED")));

    // REJECTED is terminal
    performTransition(ascId, "PROPOSED").andExpect(status().isConflict());
  }

  // ================================================================
  // LIFECYCLE: INVALID TRANSITION PATHS
  // ================================================================

  @Test
  @Order(80)
  void transition_draftToActive_rejected() throws Exception {
    ObjectNode stmt = mapper.createObjectNode();
    stmt.put("type", "object");
    stmt.put("title", "SkipTest");
    ObjectNode req = mapper.createObjectNode();
    req.put("archetypeId", seedArchetypeId.toString());
    req.set("statement", stmt);

    MvcResult r =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();
    UUID ascId =
        UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());

    // Cannot skip PROPOSED → APPROVED → ACTIVE
    performTransition(ascId, "ACTIVE").andExpect(status().isConflict());
  }

  @Test
  @Order(81)
  void transition_draftToApproved_rejected() throws Exception {
    ObjectNode stmt = mapper.createObjectNode();
    stmt.put("type", "object");
    stmt.put("title", "SkipTest2");
    ObjectNode req = mapper.createObjectNode();
    req.put("archetypeId", seedArchetypeId.toString());
    req.set("statement", stmt);

    MvcResult r =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();
    UUID ascId =
        UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());

    // Cannot skip PROPOSED step
    performTransition(ascId, "APPROVED").andExpect(status().isConflict());
  }

  @Test
  @Order(82)
  void transition_nonExistentAscription_returns404() throws Exception {
    performTransition(UUID.randomUUID(), "PROPOSED").andExpect(status().isNotFound());
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
