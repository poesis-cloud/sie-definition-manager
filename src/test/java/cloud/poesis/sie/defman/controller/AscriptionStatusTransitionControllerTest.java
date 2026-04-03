package cloud.poesis.sie.defman.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.AscriptionStatusTransitionEntity;
import cloud.poesis.sie.defman.service.AscriptionLifecycleOrchestratorService;
import cloud.poesis.sie.defman.service.AscriptionStateMachineService;
import cloud.poesis.sie.defman.service.DataProtectionService;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AscriptionStatusTransitionController.class)
@AutoConfigureMockMvc(addFilters = false)
class AscriptionStatusTransitionControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private AscriptionStateMachineService stateMachine;

  @MockitoBean private AscriptionLifecycleOrchestratorService orchestrator;

  @MockitoBean private DataProtectionService dataProtectionService;

  private UUID ascriptionId;
  private UUID transitionId;
  private AscriptionStatusTransitionEntity transitionEntity;

  @BeforeEach
  void setUp() {
    ascriptionId = UUID.randomUUID();
    transitionId = UUID.randomUUID();

    lenient()
        .when(dataProtectionService.applyInTransitProtection(any(), any()))
        .thenAnswer(inv -> inv.getArgument(0));

    AscriptionEntity parentAscription = mock(AscriptionEntity.class);
    when(parentAscription.getId()).thenReturn(ascriptionId);

    transitionEntity = mock(AscriptionStatusTransitionEntity.class);
    when(transitionEntity.getId()).thenReturn(transitionId);
    when(transitionEntity.getAscription()).thenReturn(parentAscription);
    when(transitionEntity.getPreStatus()).thenReturn(null);
    when(transitionEntity.getPostStatus()).thenReturn(AscriptionStatusType.DRAFT);
    when(transitionEntity.getTimestamp()).thenReturn(Instant.parse("2025-01-01T00:00:00Z"));
  }

  // ========================================================================
  // GET TRANSITIONS
  // ========================================================================

  @Nested
  class GetTransitionsTests {

    @Test
    void getTransitions_returnsCollectionWithLinks() throws Exception {
      when(stateMachine.getTransitions(ascriptionId)).thenReturn(List.of(transitionEntity));

      mockMvc
          .perform(
              get("/api/v1/ascriptions/{id}/transitions", ascriptionId).accept(MediaTypes.HAL_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions", hasSize(1)))
          .andExpect(
              jsonPath("$._embedded.ascriptionStatusTransitions[0]._links.self.href").exists())
          .andExpect(
              jsonPath("$._embedded.ascriptionStatusTransitions[0]._links.collection.href")
                  .exists())
          .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions[0]._links.up.href").exists())
          .andExpect(
              jsonPath("$._embedded.ascriptionStatusTransitions[0]._links.first.href").exists())
          .andExpect(
              jsonPath("$._embedded.ascriptionStatusTransitions[0]._links.last.href").exists())
          .andExpect(
              jsonPath("$._embedded.ascriptionStatusTransitions[0]._links.create-form.href")
                  .exists());
    }

    @Test
    void getTransitions_multipleTransitions_hasPrevNextLinks() throws Exception {
      UUID t2Id = UUID.randomUUID();
      AscriptionStatusTransitionEntity t2 = mock(AscriptionStatusTransitionEntity.class);
      AscriptionEntity parent2 = mock(AscriptionEntity.class);
      when(parent2.getId()).thenReturn(ascriptionId);
      when(t2.getId()).thenReturn(t2Id);
      when(t2.getAscription()).thenReturn(parent2);
      when(t2.getPreStatus()).thenReturn(AscriptionStatusType.DRAFT);
      when(t2.getPostStatus()).thenReturn(AscriptionStatusType.PROPOSED);
      when(t2.getTimestamp()).thenReturn(Instant.parse("2025-01-02T00:00:00Z"));

      when(stateMachine.getTransitions(ascriptionId)).thenReturn(List.of(transitionEntity, t2));

      mockMvc
          .perform(
              get("/api/v1/ascriptions/{id}/transitions", ascriptionId).accept(MediaTypes.HAL_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$._embedded.ascriptionStatusTransitions", hasSize(2)))
          .andExpect(
              jsonPath("$._embedded.ascriptionStatusTransitions[0]._links.next.href").exists())
          .andExpect(
              jsonPath("$._embedded.ascriptionStatusTransitions[1]._links.previous.href").exists());
    }
  }

  // ========================================================================
  // GET SINGLE TRANSITION
  // ========================================================================

  @Nested
  class GetTransitionTests {

    @Test
    void getTransition_returnsWithNavigationLinks() throws Exception {
      when(stateMachine.getTransition(transitionId, ascriptionId))
          .thenReturn(Optional.of(transitionEntity));
      when(stateMachine.getTransitions(ascriptionId)).thenReturn(List.of(transitionEntity));

      mockMvc
          .perform(
              get("/api/v1/ascriptions/{id}/transitions/{transitionId}", ascriptionId, transitionId)
                  .accept(MediaTypes.HAL_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.postStatus").value("DRAFT"))
          .andExpect(jsonPath("$._links.self.href").exists())
          .andExpect(jsonPath("$._links.up.href").exists());
    }

    @Test
    void getTransition_notFound_returns404() throws Exception {
      UUID badId = UUID.randomUUID();
      when(stateMachine.getTransition(badId, ascriptionId)).thenReturn(Optional.empty());

      mockMvc
          .perform(
              get("/api/v1/ascriptions/{id}/transitions/{transitionId}", ascriptionId, badId)
                  .accept(MediaTypes.HAL_JSON))
          .andExpect(status().isNotFound());
    }
  }

  // ========================================================================
  // TRANSITION (POST)
  // ========================================================================

  @Nested
  class TransitionTests {

    @Test
    void transition_returns201WithLocationAndBody() throws Exception {
      when(orchestrator.transition(ascriptionId, "PROPOSED")).thenReturn(transitionEntity);
      when(transitionEntity.getPostStatus()).thenReturn(AscriptionStatusType.PROPOSED);
      when(stateMachine.getTransitions(ascriptionId)).thenReturn(List.of(transitionEntity));

      ObjectNode body = objectMapper.createObjectNode();
      body.put("targetStatus", "PROPOSED");

      mockMvc
          .perform(
              post("/api/v1/ascriptions/{id}/transitions", ascriptionId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .accept(MediaTypes.HAL_JSON)
                  .content(objectMapper.writeValueAsString(body)))
          .andExpect(status().isCreated())
          .andExpect(header().exists("Location"))
          .andExpect(jsonPath("$.postStatus").value("PROPOSED"))
          .andExpect(jsonPath("$._links.self.href").exists());
    }

    @Test
    void transition_missingTargetStatus_returns400() throws Exception {
      ObjectNode body = objectMapper.createObjectNode();

      mockMvc
          .perform(
              post("/api/v1/ascriptions/{id}/transitions", ascriptionId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .accept(MediaTypes.HAL_JSON)
                  .content(objectMapper.writeValueAsString(body)))
          .andExpect(status().isBadRequest());
    }
  }
}
