package cloud.poesis.sie.defman.controller;

import static org.hamcrest.Matchers.containsString;
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
 * Integration tests for the Ascription REST API (CRUD, pagination, query filters, schema, OpenAPI)
 * against a real PostgreSQL instance (Testcontainers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("tc")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AscriptionResourceIT {

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
  static UUID seedArchetypeDefinitionId;
  static UUID structureArchetypeId;
  static UUID structureArchetypeDefinitionId;
  static UUID mechanismArchetypeId;
  static UUID mechanismArchetypeDefinitionId;

  static UUID createdArchetypeId;
  static UUID createdArchetypeDefinitionId;
  static UUID siblingArchetypeId;

  static UUID createdStructureId;
  static UUID createdStructureDefinitionId;
  static UUID createdMechanismId;
  static UUID createdMechanismDefinitionId;

  // ================================================================
  // SEED DATA TESTS
  // ================================================================

  @Test
  @Order(1)
  void listSeedArchetypes_returns8Active() throws Exception {
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
      String collHref = item.at("/_links/collection/href").asText();
      String[] segs = collHref.split("/");
      UUID defId = UUID.fromString(segs[segs.length - 2]);
      if (stmtStr.contains("\"title\":\"Archetype\"")) {
        seedArchetypeId = UUID.fromString(item.get("id").asText());
        seedArchetypeDefinitionId = defId;
      }
      if (stmtStr.contains("\"title\":\"StructureArchetype\"")) {
        structureArchetypeId = UUID.fromString(item.get("id").asText());
        structureArchetypeDefinitionId = defId;
      }
      if (stmtStr.contains("\"title\":\"MechanismArchetype\"")) {
        mechanismArchetypeId = UUID.fromString(item.get("id").asText());
        mechanismArchetypeDefinitionId = defId;
      }
    }

    if (seedArchetypeId == null) {
      seedArchetypeId = UUID.fromString(items.get(0).get("id").asText());
      String collHref = items.get(0).at("/_links/collection/href").asText();
      String[] segs = collHref.split("/");
      seedArchetypeDefinitionId = UUID.fromString(segs[segs.length - 2]);
    }
    if (structureArchetypeId == null) {
      structureArchetypeId = UUID.fromString(items.get(1).get("id").asText());
      String collHref = items.get(1).at("/_links/collection/href").asText();
      String[] segs = collHref.split("/");
      structureArchetypeDefinitionId = UUID.fromString(segs[segs.length - 2]);
    }
    if (mechanismArchetypeId == null) {
      mechanismArchetypeId = UUID.fromString(items.get(2).get("id").asText());
      String collHref = items.get(2).at("/_links/collection/href").asText();
      String[] segs = collHref.split("/");
      mechanismArchetypeDefinitionId = UUID.fromString(segs[segs.length - 2]);
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

    MvcResult result =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.status", is("DRAFT")))
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.statement.title", is("TestArchetype")))
            .andExpect(jsonPath("$._links.self.href").exists())
            .andExpect(jsonPath("$._links.collection.href").exists())
            .andExpect(jsonPath("$._links.type.href").exists())
            .andExpect(jsonPath("$._links.describedby.href").exists())
            .andExpect(jsonPath("$._links.create-form.href").exists())
            .andReturn();

    JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
    createdArchetypeId = UUID.fromString(body.get("id").asText());
    String collectionHref = body.at("/_links/collection/href").asText();
    String[] segments = collectionHref.split("/");
    createdArchetypeDefinitionId = UUID.fromString(segments[segments.length - 2]);

    // Verify HAL link hrefs point to the correct resources
    mvc.perform(get("/api/v1/ascriptions/{id}", createdArchetypeId))
        .andExpect(jsonPath("$._links.self.href", endsWith("/ascriptions/" + createdArchetypeId)))
        .andExpect(
            jsonPath(
                "$._links.describedby.href",
                endsWith("/ascriptions/" + createdArchetypeId + "/schema")))
        .andExpect(
            jsonPath("$._links.type.href", endsWith("/definitions/" + seedArchetypeDefinitionId)))
        .andExpect(
            jsonPath(
                "$._links.collection.href",
                endsWith("/definitions/" + createdArchetypeDefinitionId + "/ascriptions")));
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

    MvcResult result =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(
                jsonPath(
                    "$._links.collection.href",
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

    MvcResult result =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$._links.collection.href").exists())
            .andExpect(jsonPath("$._links.type.href").exists())
            .andExpect(jsonPath("$._links.describedby.href").exists())
            .andExpect(jsonPath("$.status", is("DRAFT")))
            .andExpect(jsonPath("$.statement.purpose", is("test-payment-processing")))
            .andReturn();

    JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
    createdStructureId = UUID.fromString(body.get("id").asText());
    String collHref = body.at("/_links/collection/href").asText();
    String[] segs2 = collHref.split("/");
    createdStructureDefinitionId = UUID.fromString(segs2[segs2.length - 2]);

    // Verify HAL link hrefs via re-fetch
    mvc.perform(get("/api/v1/ascriptions/{id}", createdStructureId))
        .andExpect(
            jsonPath(
                "$._links.type.href", endsWith("/definitions/" + structureArchetypeDefinitionId)))
        .andExpect(
            jsonPath(
                "$._links.describedby.href",
                endsWith("/ascriptions/" + createdStructureId + "/schema")));
  }

  @Test
  @Order(51)
  void createMechanism_withStructureFkInStatement() throws Exception {
    ObjectNode statement = mapper.createObjectNode();
    statement.put("function", "PaymentValidation");
    statement.put(
        "rule", "sys.receive(\"PaymentRequest\")\nsys.effect(\"PaymentResult\", {\"ok\": True})");
    statement.put("structure", createdStructureId.toString());

    ObjectNode request = mapper.createObjectNode();
    request.put("archetypeId", mechanismArchetypeId.toString());
    request.set("statement", statement);

    MvcResult result =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$._links.collection.href").exists())
            .andExpect(jsonPath("$._links.type.href").exists())
            .andExpect(jsonPath("$._links.describedby.href").exists())
            .andExpect(jsonPath("$.statement.function", is("PaymentValidation")))
            .andReturn();

    JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
    createdMechanismId = UUID.fromString(body.get("id").asText());
    String collHref = body.at("/_links/collection/href").asText();
    String[] segs3 = collHref.split("/");
    createdMechanismDefinitionId = UUID.fromString(segs3[segs3.length - 2]);

    // Verify type link points to MechanismArchetype's Definition
    mvc.perform(get("/api/v1/ascriptions/{id}", createdMechanismId))
        .andExpect(
            jsonPath(
                "$._links.type.href", endsWith("/definitions/" + mechanismArchetypeDefinitionId)));
  }

  @Test
  @Order(52)
  void createMechanism_missingStructureInStatement_returns400() throws Exception {
    ObjectNode statement = mapper.createObjectNode();
    statement.put("function", "Orphan");
    statement.put("rule", "sys.receive(\"X\")\nsys.effect(\"Y\", {})");
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
    statement.put("rule", "sys.receive(\"X\")\nsys.effect(\"Y\", {})");
    statement.put("structure", UUID.randomUUID().toString());

    ObjectNode request = mapper.createObjectNode();
    request.put("archetypeId", mechanismArchetypeId.toString());
    request.set("statement", statement);

    mvc.perform(
            post("/api/v1/ascriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
        .andExpect(status().isNotFound());
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
            get("/api/v1/ascriptions").param("type", "structure").param("statement.purpose", "foo"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("archetype")));
  }

  @Test
  @Order(101)
  void queryFilter_unknownArchetypeTitle_returns400() throws Exception {
    mvc.perform(
            get("/api/v1/ascriptions")
                .param("type", "structure")
                .param("archetype", "NonExistentArchetype"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("NonExistentArchetype")));
  }

  @Test
  @Order(102)
  void queryFilter_nonQueryableProperty_returns400() throws Exception {
    mvc.perform(
            get("/api/v1/ascriptions")
                .param("type", "mechanism")
                .param("archetype", "MechanismArchetype")
                .param("statement.rule", "foo"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("$gsm:queryable")));
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
        .andExpect(jsonPath("$._embedded.ascriptions", hasSize(greaterThanOrEqualTo(8))));
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
        .andExpect(jsonPath("$._embedded.ascriptions", hasSize(greaterThanOrEqualTo(8))));
  }

  // ================================================================
  // SCHEMA ENDPOINT (describedby)
  // ================================================================

  @Test
  @Order(110)
  void getSchema_returnsComposedJsonSchema() throws Exception {
    // Create a fresh structure to test with — its typing archetype is
    // StructureArchetype
    ObjectNode stmt = mapper.createObjectNode().put("purpose", "schema-endpoint-test");
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

    // The composed schema inlines the typing archetype's schema as the statement
    // property.
    // For a Structure ascription, the typing archetype is StructureArchetype.
    mvc.perform(get("/api/v1/ascriptions/{id}/schema", ascId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title", is("Ascription")))
        .andExpect(jsonPath("$.type", is("object")))
        .andExpect(jsonPath("$.properties.id.type", is("string")))
        .andExpect(jsonPath("$.properties.id.format", is("uuid")))
        .andExpect(jsonPath("$.properties.statement.title", is("StructureArchetype")))
        .andExpect(jsonPath("$.properties.status.type", is("string")))
        .andExpect(jsonPath("$.properties.timestamp.format", is("date-time")));
  }

  @Test
  @Order(111)
  void getSchema_notFound_returns404() throws Exception {
    mvc.perform(get("/api/v1/ascriptions/{id}/schema", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }
}
