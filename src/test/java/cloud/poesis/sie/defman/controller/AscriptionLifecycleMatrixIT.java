package cloud.poesis.sie.defman.controller;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloud.poesis.sie.defman.AbstractPostgresIT;
import cloud.poesis.sie.defman.type.AscriptionLifecycleSpec;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Exhaustive end-to-end lifecycle matrix over the public transition API.
 *
 * <p>For three ascription shapes, each of the 9 source statuses is reached through real REST calls
 * against a real database, then each of the 9 target statuses is attempted — 3 x 81 verdicts. Legal
 * edges must be accepted and persisted; illegal edges must be refused with the specified problem
 * type and must leave the ascription untouched.
 *
 * <p>The shapes are chosen to exercise the seams that plain transition unit tests cannot reach: a
 * rootless Archetype carrying queryable properties (activation must not require a subject type), a
 * based Archetype carrying queryable properties (activation provisions indexes and resolves a
 * referee), and a plain Structure (activation enforces cross-definition uniqueness).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("tc")
class AscriptionLifecycleMatrixIT extends AbstractPostgresIT {

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registerIsolatedDatabase(registry);
  }

  private static final String ARCHETYPE_BASE_URI = "gsmarc://gsm/Archetype/v1";
  private static final String STRUCTURE_BASE_URI = "gsmarc://gsm/Structure/v1";

  /** Ascription shapes driven through the full matrix. */
  enum Shape {
    STRUCTURE(STRUCTURE_BASE_URI),
    ROOTLESS_ARCHETYPE(ARCHETYPE_BASE_URI),
    BASED_ARCHETYPE(ARCHETYPE_BASE_URI);

    private final String typingArchetypeUri;

    Shape(String typingArchetypeUri) {
      this.typingArchetypeUri = typingArchetypeUri;
    }
  }

  @Autowired MockMvc mvc;

  @Autowired ObjectMapper mapper;

  static Stream<Arguments> lifecycleMatrix() {
    return Stream.of(Shape.values())
        .flatMap(
            shape ->
                AscriptionLifecycleSpec.allStatusPairs().stream()
                    .map(pair -> Arguments.of(shape, pair[0], pair[1])));
  }

  static Stream<Arguments> shapesAndStatuses() {
    return Stream.of(Shape.values())
        .flatMap(
            shape ->
                Stream.of(AscriptionStatusType.values())
                    .map(status -> Arguments.of(shape, status)));
  }

  // ========================================================================
  // Status reachability
  // ========================================================================

  @ParameterizedTest(name = "{0} reaches {1}")
  @MethodSource("shapesAndStatuses")
  void everyStatusIsReachableThroughTheApi(Shape shape, AscriptionStatusType status)
      throws Exception {
    UUID id = createDraft(shape, "Reach" + camel(shape.name()) + camel(status.name()));
    driveTo(id, status);

    mvc.perform(get("/api/v1/ascriptions/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is(status.name())));
  }

  // ========================================================================
  // Full transition matrix
  // ========================================================================

  @ParameterizedTest(name = "{0}: {1} -> {2}")
  @MethodSource("lifecycleMatrix")
  void everyStatusPairIsAdjudicatedAsSpecified(
      Shape shape, AscriptionStatusType from, AscriptionStatusType to) throws Exception {
    UUID id =
        createDraft(shape, "Mx" + camel(shape.name()) + camel(from.name()) + camel(to.name()));
    driveTo(id, from);

    if (AscriptionLifecycleSpec.isLegal(from, to)) {
      transition(id, to)
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.preStatus", is(from.name())))
          .andExpect(jsonPath("$.postStatus", is(to.name())));
      mvc.perform(get("/api/v1/ascriptions/{id}", id))
          .andExpect(jsonPath("$.status", is(to.name())));
      return;
    }

    MvcResult refusal = transition(id, to).andExpect(status().isConflict()).andReturn();
    assertEquals(
        expectedRefusalType(from),
        mapper.readTree(refusal.getResponse().getContentAsString()).get("type").asText(),
        () -> "Unexpected refusal type for " + shape + " " + from + " -> " + to);
    mvc.perform(get("/api/v1/ascriptions/{id}", id))
        .andExpect(jsonPath("$.status", is(from.name())));
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private static String expectedRefusalType(AscriptionStatusType from) {
    return AscriptionLifecycleSpec.TERMINAL_STATUSES.contains(from)
        ? AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_TERMINAL_IMMUTABILITY
            .getType()
        : AscriptionStatusTransitionRuleType.ASCRIPTION_STATUS_TRANSITION_PATH.getType();
  }

  private void driveTo(UUID id, AscriptionStatusType target) throws Exception {
    for (AscriptionStatusType step : AscriptionLifecycleSpec.ROUTES_FROM_DRAFT.get(target)) {
      transition(id, step).andExpect(status().isCreated());
    }
  }

  private ResultActions transition(UUID id, AscriptionStatusType target) throws Exception {
    ObjectNode body = mapper.createObjectNode().put("targetStatus", target.name());
    return mvc.perform(
        post("/api/v1/ascriptions/{id}/transitions", id)
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(body)));
  }

  private UUID createDraft(Shape shape, String name) throws Exception {
    ObjectNode request = mapper.createObjectNode();
    request.put("archetypeUri", shape.typingArchetypeUri);
    request.set("statement", statement(shape, name));

    MvcResult created =
        mvc.perform(
                post("/api/v1/ascriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn();
    return UUID.fromString(
        mapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
  }

  private ObjectNode statement(Shape shape, String name) {
    if (shape == Shape.STRUCTURE) {
      // Structure.purpose is constrained to ^[a-z][a-z0-9]*(-[a-z0-9]+)*$; the identity segment is
      // alphabetic CamelCase, so lower-casing it is both conformant and injective.
      return mapper.createObjectNode().put("purpose", name.toLowerCase(Locale.ROOT));
    }

    ObjectNode statement = mapper.createObjectNode();
    statement.put("$id", "gsmarc://test/" + name + "/v1");
    statement.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    statement.put("type", "object");
    statement.put("title", name);
    if (shape == Shape.BASED_ARCHETYPE) {
      statement.put("$ref", STRUCTURE_BASE_URI);
    }
    statement
        .putObject("properties")
        .putObject("matrixLabel")
        .put("type", "string")
        .put("$gsm:queryable", true);
    return statement;
  }

  /** Converts an enum constant name into a {@code [A-Z][A-Za-z0-9]*} identity segment. */
  private static String camel(String enumName) {
    StringBuilder camel = new StringBuilder();
    for (String word : enumName.split("_")) {
      camel.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
    }
    return camel.toString();
  }
}
