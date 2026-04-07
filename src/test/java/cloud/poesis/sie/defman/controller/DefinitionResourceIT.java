package cloud.poesis.sie.defman.controller;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
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
 * Integration tests for Definition endpoints against a real PostgreSQL instance (Testcontainers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("tc")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DefinitionResourceIT {

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

  static UUID createdArchetypeDefinitionId;
  static UUID createdStructureDefinitionId;

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
    ObjectNode statement = mapper.createObjectNode();
    statement.put("type", "object");
    statement.put("title", "DefTestArchetype");
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
    String collectionHref = body.at("/_links/collection/href").asText();
    String[] segments = collectionHref.split("/");
    createdArchetypeDefinitionId = UUID.fromString(segments[segments.length - 2]);
  }

  @Test
  @Order(3)
  void setup_createStructure() throws Exception {
    ObjectNode statement = mapper.createObjectNode();
    statement.put("purpose", "def-test-structure");

    ObjectNode request = mapper.createObjectNode();
    request.put("archetypeId", structureArchetypeId.toString());
    request.set("statement", statement);

    MvcResult result =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn();

    JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
    String collHref = body.at("/_links/collection/href").asText();
    String[] segs = collHref.split("/");
    createdStructureDefinitionId = UUID.fromString(segs[segs.length - 2]);
  }

  // ================================================================
  // DEFINITION ENDPOINT
  // ================================================================

  @Test
  @Order(10)
  void getDefinitionById_returnsSubjectType() throws Exception {
    mvc.perform(get("/api/v1/definitions/{id}", createdArchetypeDefinitionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", is(createdArchetypeDefinitionId.toString())))
        .andExpect(jsonPath("$.subjectType", is("ARCHETYPE")));
  }

  @Test
  @Order(11)
  void getDefinitionById_structure_returnsStructureType() throws Exception {
    mvc.perform(get("/api/v1/definitions/{id}", createdStructureDefinitionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subjectType", is("STRUCTURE")));
  }

  @Test
  @Order(12)
  void getDefinitionById_notFound_returns404() throws Exception {
    mvc.perform(get("/api/v1/definitions/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  @Order(13)
  void getDefinitionAscriptions_returnsCollectionForDefinition() throws Exception {
    mvc.perform(get("/api/v1/definitions/{id}/ascriptions", createdArchetypeDefinitionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$._embedded.ascriptions", hasSize(greaterThanOrEqualTo(1))));
  }
}
