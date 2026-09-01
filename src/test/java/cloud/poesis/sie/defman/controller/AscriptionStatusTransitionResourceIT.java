package cloud.poesis.sie.defman.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloud.poesis.sie.defman.AbstractPostgresIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for Ascription lifecycle transitions against a real PostgreSQL instance
 * (Testcontainers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("tc")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AscriptionStatusTransitionResourceIT extends AbstractPostgresIT {

  private static final String ARCHETYPE_ID = "gsmarc://gsm/Archetype/v1";
  private static final String STRUCTURE_ID = "gsmarc://gsm/Structure/v1";

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registerIsolatedDatabase(registry);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

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
      if (stmtStr.contains("\"title\":\"Structure\"")) {
        structureArchetypeId = UUID.fromString(item.get("id").asText());
      }
    }
  }

  @Test
  @Order(2)
  void setup_createArchetype() throws Exception {
    ObjectNode statement = newArchetypeStatement("TransitionTestArchetype");

    ObjectNode request = mapper.createObjectNode();
    request.put("archetypeUri", ARCHETYPE_ID);
    request.set("statement", statement);

    MvcResult result =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version", is(0)))
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
    ObjectNode statement = newArchetypeStatement("TransitionTestArchetype");
    statement.put("description", "Sibling version");

    ObjectNode request = mapper.createObjectNode();
    request.put("definitionId", createdArchetypeDefinitionId.toString());
    request.put("archetypeUri", ARCHETYPE_ID);
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
  void getTransitions_emptyForNewAscription() throws Exception {
    mvc.perform(get("/api/v1/ascriptions/{id}/transitions", createdArchetypeId))
        .andExpect(status().isOk());
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

    mvc.perform(get("/api/v1/ascriptions/{id}", createdArchetypeId))
        .andExpect(jsonPath("$.version", is(0)));

    performTransition(siblingArchetypeId, "PROPOSED")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.preStatus", is("DRAFT")))
        .andExpect(jsonPath("$.postStatus", is("PROPOSED")));

    mvc.perform(get("/api/v1/ascriptions/{id}", siblingArchetypeId))
        .andExpect(jsonPath("$.status", is("PROPOSED")))
        .andExpect(jsonPath("$.version", is(0)));
  }

  @Test
  @Order(21)
  void transition_proposedToApproved_terminatesSibling() throws Exception {
    performTransition(createdArchetypeId, "APPROVED")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.postStatus", is("APPROVED")));

    mvc.perform(get("/api/v1/ascriptions/{id}", createdArchetypeId))
        .andExpect(jsonPath("$.status", is("APPROVED")))
        .andExpect(jsonPath("$.version", is(1)));

    mvc.perform(get("/api/v1/ascriptions/{id}", siblingArchetypeId))
        .andExpect(jsonPath("$.status", is("REJECTED")))
        .andExpect(jsonPath("$.version", is(0)));
  }

  @Test
  @Order(22)
  void transition_approvedToActive() throws Exception {
    performTransition(createdArchetypeId, "ACTIVE")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.postStatus", is("ACTIVE")));

    mvc.perform(get("/api/v1/ascriptions/{id}", createdArchetypeId))
        .andExpect(jsonPath("$.status", is("ACTIVE")))
        .andExpect(jsonPath("$.version", is(1)));
  }

  @Test
  @Order(23)
  void transition_activeToDeprecated() throws Exception {
    performTransition(createdArchetypeId, "DEPRECATED")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.postStatus", is("DEPRECATED")));

    mvc.perform(get("/api/v1/ascriptions/{id}", createdArchetypeId))
        .andExpect(jsonPath("$.version", is(1)));
  }

  @Test
  @Order(24)
  void transition_deprecatedToRetired() throws Exception {
    performTransition(createdArchetypeId, "RETIRED")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.postStatus", is("RETIRED")));

    mvc.perform(get("/api/v1/ascriptions/{id}", createdArchetypeId))
        .andExpect(jsonPath("$.version", is(1)));
  }

  @Test
  @Order(25)
  void concurrentApproval_hasOneWinnerAndOneGovernedConflict() throws Exception {
    ObjectNode firstRequest = newArchetypeRequest("ConcurrentApproval", null);
    MvcResult firstCreate =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(firstRequest)))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode firstBody = mapper.readTree(firstCreate.getResponse().getContentAsString());
    UUID firstId = UUID.fromString(firstBody.get("id").asText());
    String[] collectionSegments = firstBody.at("/_links/collection/href").asText().split("/");
    UUID definitionId = UUID.fromString(collectionSegments[collectionSegments.length - 2]);

    ObjectNode secondRequest = newArchetypeRequest("ConcurrentApproval", definitionId);
    MvcResult secondCreate =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(secondRequest)))
            .andExpect(status().isCreated())
            .andReturn();
    UUID secondId =
        UUID.fromString(
            mapper.readTree(secondCreate.getResponse().getContentAsString()).get("id").asText());

    performTransition(firstId, "PROPOSED").andExpect(status().isCreated());
    performTransition(secondId, "PROPOSED").andExpect(status().isCreated());

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    List<MvcResult> results = new ArrayList<>();
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<MvcResult> firstApproval =
          executor.submit(() -> performConcurrentApproval(firstId, ready, start));
      Future<MvcResult> secondApproval =
          executor.submit(() -> performConcurrentApproval(secondId, ready, start));
      assertEquals(true, ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      results.add(firstApproval.get(30, TimeUnit.SECONDS));
      results.add(secondApproval.get(30, TimeUnit.SECONDS));
    }

    List<Integer> statuses =
        results.stream().map(result -> result.getResponse().getStatus()).sorted().toList();
    assertEquals(List.of(201, 409), statuses);
    MvcResult conflict =
        results.stream()
            .filter(result -> result.getResponse().getStatus() == 409)
            .findFirst()
            .orElseThrow();
    assertGovernedConcurrentConflict(conflict);

    List<Integer> versions = new ArrayList<>();
    List<String> lifecycleStatuses = new ArrayList<>();
    for (UUID id : List.of(firstId, secondId)) {
      MvcResult result = mvc.perform(get("/api/v1/ascriptions/{id}", id)).andReturn();
      JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
      versions.add(body.get("version").asInt());
      lifecycleStatuses.add(body.get("status").asText());
    }
    Collections.sort(versions);
    Collections.sort(lifecycleStatuses);
    assertEquals(List.of(0, 1), versions);
    assertEquals(List.of("APPROVED", "REJECTED"), lifecycleStatuses);
  }

  @Test
  @Order(25)
  void staleCandidateVersion_rejectedOnSubmitWithoutTransition() throws Exception {
    ObjectNode request =
        newArchetypeRequest("TransitionTestArchetype", createdArchetypeDefinitionId);
    MvcResult create =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn();
    UUID id =
        UUID.fromString(
            mapper.readTree(create.getResponse().getContentAsString()).get("id").asText());

    performTransition(id, "PROPOSED")
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.rule", is("ASCRIPTION_STATUS_TRANSITION_ARCHETYPE_CANDIDATE_VERSION")));

    mvc.perform(get("/api/v1/ascriptions/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("DRAFT")))
        .andExpect(jsonPath("$.version", is(0)));
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM ascription_status_transition WHERE ascription_id = ?::uuid",
            id.toString()));
  }

  @Test
  @Order(26)
  void laterApproval_materializesNextDefinitionVersion() throws Exception {
    ObjectNode request =
        newArchetypeRequest("TransitionTestArchetype", createdArchetypeDefinitionId);
    request.withObject("/statement").put("$id", "gsmarc://test/TransitionTestArchetype/v2");
    MvcResult create =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version", is(0)))
            .andReturn();
    UUID id =
        UUID.fromString(
            mapper.readTree(create.getResponse().getContentAsString()).get("id").asText());

    performTransition(id, "PROPOSED").andExpect(status().isCreated());
    performTransition(id, "APPROVED").andExpect(status().isCreated());

    mvc.perform(get("/api/v1/ascriptions/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("APPROVED")))
        .andExpect(jsonPath("$.version", is(2)));
  }

  @Test
  @Order(27)
  void concurrentApprovalAndRejection_doNotResurrectTerminalState() throws Exception {
    ObjectNode request = newArchetypeRequest("ApproveRejectRace", null);
    MvcResult create =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn();
    UUID id =
        UUID.fromString(
            mapper.readTree(create.getResponse().getContentAsString()).get("id").asText());
    performTransition(id, "PROPOSED").andExpect(status().isCreated());

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    List<MvcResult> results = new ArrayList<>();
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<MvcResult> approval =
          executor.submit(() -> performConcurrentTransition(id, "APPROVED", ready, start));
      Future<MvcResult> rejection =
          executor.submit(() -> performConcurrentTransition(id, "REJECTED", ready, start));
      assertEquals(true, ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      results.add(approval.get(30, TimeUnit.SECONDS));
      results.add(rejection.get(30, TimeUnit.SECONDS));
    }

    assertEquals(
        List.of(201, 409),
        results.stream().map(result -> result.getResponse().getStatus()).sorted().toList());
    MvcResult conflict =
        results.stream()
            .filter(result -> result.getResponse().getStatus() == 409)
            .findFirst()
            .orElseThrow();
    assertGovernedConcurrentConflict(conflict);

    JsonNode transitionHistory =
        mapper.readTree(
            mvc.perform(get("/api/v1/ascriptions/{id}/transitions", id))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    JsonNode transitions = transitionHistory.path("_embedded").path("ascriptionStatusTransitions");
    assertEquals(true, transitions.isArray());
    int terminalEdges = 0;
    for (JsonNode transition : transitions) {
      String preStatus = transition.path("preStatus").asText();
      String postStatus = transition.path("postStatus").asText();
      if ("PROPOSED".equals(preStatus)
          && ("APPROVED".equals(postStatus) || "REJECTED".equals(postStatus))) {
        terminalEdges++;
      }
    }
    assertEquals(1, terminalEdges);

    JsonNode finalState =
        mapper.readTree(
            mvc.perform(get("/api/v1/ascriptions/{id}", id))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    if ("APPROVED".equals(finalState.get("status").asText())) {
      assertEquals(1, finalState.get("version").asInt());
    } else {
      assertEquals("REJECTED", finalState.get("status").asText());
      assertEquals(0, finalState.get("version").asInt());
    }
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
    req.put("archetypeUri", STRUCTURE_ID);
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
    req2.put("archetypeUri", STRUCTURE_ID);
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
    ObjectNode stmt = newArchetypeStatement("AuditTest");
    ObjectNode req = mapper.createObjectNode();
    req.put("archetypeUri", ARCHETYPE_ID);
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

    // Should have 3 transitions: [DRAFT→PROPOSED, PROPOSED→APPROVED,
    // APPROVED→ACTIVE]
    mvc.perform(get("/api/v1/ascriptions/{id}/transitions", ascId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions", hasSize(3)))
        .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions[0].preStatus", is("DRAFT")))
        .andExpect(
            jsonPath("$._embedded.ascriptionStatusTransitions[0].postStatus", is("PROPOSED")))
        .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions[1].preStatus", is("PROPOSED")))
        .andExpect(
            jsonPath("$._embedded.ascriptionStatusTransitions[1].postStatus", is("APPROVED")))
        .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions[2].preStatus", is("APPROVED")))
        .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions[2].postStatus", is("ACTIVE")));
  }

  // ================================================================
  // LIFECYCLE: SUSPEND / REACTIVATE
  // ================================================================

  @Test
  @Order(60)
  void lifecycle_activeToSuspendedToActiveToDeprecatedToRetired() throws Exception {
    ObjectNode stmt = mapper.createObjectNode().put("purpose", "suspend-test");
    ObjectNode req = mapper.createObjectNode();
    req.put("archetypeUri", STRUCTURE_ID);
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
    req.put("archetypeUri", STRUCTURE_ID);
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
    ObjectNode stmt = newArchetypeStatement("AbandonTest");
    ObjectNode req = mapper.createObjectNode();
    req.put("archetypeUri", ARCHETYPE_ID);
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

    mvc.perform(
            post("/api/v1/ascriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.rule", is("ARCHETYPE_STEM_UNIQUENESS_ACROSS_DEFINITIONS")));

    // ABANDONED is terminal
    performTransition(ascId, "DRAFT").andExpect(status().isConflict());
  }

  @Test
  @Order(71)
  void lifecycle_proposedToRejected() throws Exception {
    ObjectNode stmt = newArchetypeStatement("RejectTest");
    ObjectNode req = mapper.createObjectNode();
    req.put("archetypeUri", ARCHETYPE_ID);
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

    mvc.perform(
            post("/api/v1/ascriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.rule", is("ARCHETYPE_STEM_UNIQUENESS_ACROSS_DEFINITIONS")));

    // REJECTED is terminal
    performTransition(ascId, "PROPOSED").andExpect(status().isConflict());
  }

  @Test
  @Order(72)
  void resolvableUriDatabaseGuard_translatesToConflictAndRollsBack() throws Exception {
    UUID firstDefinitionId = createArchetypeDefinition();
    UUID secondDefinitionId = createArchetypeDefinition();
    ObjectNode statement = newArchetypeStatement("ResolvableConflict");
    UUID firstId = createDraftArchetype(firstDefinitionId, statement);
    UUID secondId = createDraftArchetype(secondDefinitionId, statement);

    performTransition(firstId, "PROPOSED").andExpect(status().isCreated());
    performTransition(secondId, "PROPOSED").andExpect(status().isCreated());
    performTransition(firstId, "APPROVED").andExpect(status().isCreated());

    performTransition(secondId, "APPROVED")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.rule", is("ARCHETYPE_URI_RESOLUTION_UNIQUENESS")));

    mvc.perform(get("/api/v1/ascriptions/{id}", secondId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("PROPOSED")))
        .andExpect(jsonPath("$.version", is(0)));
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM ascription_status_transition"
                + " WHERE ascription_id = ?::uuid"
                + " AND pre_status = 'PROPOSED'::ascription_status"
                + " AND post_status = 'APPROVED'::ascription_status",
            secondId.toString()));
  }

  @Test
  @Order(73)
  void archetypeVersionReconciliationMismatch_rollsBackBeforeSiblingConvergence() throws Exception {
    UUID definitionId = createArchetypeDefinition();
    ObjectNode statement = newArchetypeStatement("ReconciliationMismatch");
    statement.put("$id", "gsmarc://test/ReconciliationMismatch/v2");
    UUID winnerId = createDraftArchetype(definitionId, statement);
    UUID siblingId = createDraftArchetype(definitionId, statement);
    jdbc.update(
        "INSERT INTO ascription_status_transition (ascription_id, pre_status, post_status)"
            + " VALUES (?::uuid, 'DRAFT'::ascription_status, 'PROPOSED'::ascription_status)",
        winnerId.toString());
    jdbc.update(
        "INSERT INTO ascription_status_transition (ascription_id, pre_status, post_status)"
            + " VALUES (?::uuid, 'DRAFT'::ascription_status, 'PROPOSED'::ascription_status)",
        siblingId.toString());

    performTransition(winnerId, "APPROVED")
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath(
                "$.rule", is("ASCRIPTION_STATUS_TRANSITION_ARCHETYPE_VERSION_RECONCILIATION")));

    mvc.perform(get("/api/v1/ascriptions/{id}", winnerId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("PROPOSED")))
        .andExpect(jsonPath("$.version", is(0)));
    mvc.perform(get("/api/v1/ascriptions/{id}", siblingId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("PROPOSED")))
        .andExpect(jsonPath("$.version", is(0)));
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM ascription_status_transition"
                + " WHERE ascription_id = ?::uuid"
                + " AND pre_status = 'PROPOSED'::ascription_status"
                + " AND post_status = 'APPROVED'::ascription_status",
            winnerId.toString()));
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM ascription_status_transition"
                + " WHERE ascription_id = ?::uuid"
                + " AND post_status IN"
                + " ('ABANDONED'::ascription_status, 'REJECTED'::ascription_status)",
            siblingId.toString()));
  }

  // ================================================================
  // LIFECYCLE: INVALID TRANSITION PATHS
  // ================================================================

  @Test
  @Order(80)
  void transition_draftToActive_rejected() throws Exception {
    ObjectNode stmt = newArchetypeStatement("SkipTest");
    ObjectNode req = mapper.createObjectNode();
    req.put("archetypeUri", ARCHETYPE_ID);
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
    ObjectNode stmt = newArchetypeStatement("SkipTest2");
    ObjectNode req = mapper.createObjectNode();
    req.put("archetypeUri", ARCHETYPE_ID);
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

  private MvcResult performConcurrentApproval(UUID id, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    return performConcurrentTransition(id, "APPROVED", ready, start);
  }

  private MvcResult performConcurrentTransition(
      UUID id, String targetStatus, CountDownLatch ready, CountDownLatch start) throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent transition start was not released");
    }
    return performTransition(id, targetStatus).andReturn();
  }

  private void assertGovernedConcurrentConflict(MvcResult conflict) throws Exception {
    String conflictType =
        mapper.readTree(conflict.getResponse().getContentAsString()).get("type").asText();
    assertTrue(
        List.of(
                "gsm:rules/ascription/status-transition/path",
                "gsm:rules/ascription/status-transition/terminal-immutability")
            .contains(conflictType),
        () -> "Unexpected concurrent transition conflict type: " + conflictType);
  }

  private ObjectNode newArchetypeRequest(String title, UUID definitionId) {
    ObjectNode statement = newArchetypeStatement(title);

    ObjectNode request = mapper.createObjectNode();
    if (definitionId != null) {
      request.put("definitionId", definitionId.toString());
    }
    request.put("archetypeUri", ARCHETYPE_ID);
    request.set("statement", statement);
    return request;
  }

  private UUID createArchetypeDefinition() {
    return jdbc.queryForObject(
        "INSERT INTO definition (subject_type)"
            + " VALUES ('ARCHETYPE'::definition_subject_type) RETURNING id",
        UUID.class);
  }

  private UUID createDraftArchetype(UUID definitionId, ObjectNode statement) {
    UUID typingArchetypeId =
        seedArchetypeId != null
            ? seedArchetypeId
            : Objects.requireNonNull(
                jdbc.queryForObject(
                    "SELECT id FROM archetype"
                        + " WHERE statement ->> '$id' = 'gsmarc://gsm/Archetype/v1'",
                    UUID.class));
    return jdbc.queryForObject(
        "INSERT INTO archetype"
            + " (definition_id, archetype_id, statement, status, version)"
            + " VALUES (?::uuid, ?::uuid, ?::jsonb, 'DRAFT'::ascription_status, 0)"
            + " RETURNING id",
        UUID.class,
        definitionId.toString(),
        typingArchetypeId.toString(),
        statement.toString());
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private ObjectNode newArchetypeStatement(String title) {
    ObjectNode statement = mapper.createObjectNode();
    statement.put("$id", "gsmarc://test/" + title + "/v1");
    statement.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    statement.put("type", "object");
    statement.put("title", title);
    return statement;
  }
}
