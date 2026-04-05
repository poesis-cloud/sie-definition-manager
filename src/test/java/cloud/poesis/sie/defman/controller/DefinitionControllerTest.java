package cloud.poesis.sie.defman.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.service.AscriptionProtectionService;
import cloud.poesis.sie.defman.service.DefinitionService;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.hateoas.MediaTypes;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DefinitionController.class)
@AutoConfigureMockMvc(addFilters = false)
class DefinitionControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private DefinitionService definitionService;

  @MockitoBean private AscriptionProtectionService statementProtection;

  private UUID defId;
  private DefinitionEntity definitionEntity;
  private ArchetypeEntity archetypeEntity;

  @BeforeEach
  void setUp() {
    defId = UUID.randomUUID();

    lenient()
        .when(statementProtection.applyInTransitProtection(any(), any()))
        .thenAnswer(inv -> inv.getArgument(0));

    // Archetype mock
    DefinitionEntity archetypeDefEntity = mock(DefinitionEntity.class);
    when(archetypeDefEntity.getId()).thenReturn(UUID.randomUUID());

    ObjectNode archetypeStatement = objectMapper.createObjectNode();
    archetypeStatement.put("title", "TestArchetype");

    archetypeEntity = mock(ArchetypeEntity.class);
    when(archetypeEntity.getId()).thenReturn(UUID.randomUUID());
    when(archetypeEntity.getStatement()).thenReturn(archetypeStatement);
    when(archetypeEntity.getDefinition()).thenReturn(archetypeDefEntity);

    definitionEntity = mock(DefinitionEntity.class);
    when(definitionEntity.getId()).thenReturn(defId);
    when(definitionEntity.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
  }

  private AscriptionEntity mockAscription(UUID id, AscriptionStatusType status) {
    AscriptionEntity asc = mock(AscriptionEntity.class);
    DefinitionEntity ascDef = mock(DefinitionEntity.class);
    when(ascDef.getId()).thenReturn(defId);
    when(asc.getId()).thenReturn(id);
    when(asc.getDefinition()).thenReturn(ascDef);
    when(asc.getArchetype()).thenReturn(archetypeEntity);
    when(asc.getStatement()).thenReturn(objectMapper.createObjectNode().put("purpose", "test"));
    when(asc.getTimestamp()).thenReturn(Instant.parse("2025-01-01T00:00:00Z"));
    when(asc.getStatus()).thenReturn(status);
    return asc;
  }

  // ========================================================================
  // GET BY ID
  // ========================================================================

  @Nested
  class GetByIdTests {

    @Test
    void getById_returnsDefinitionWithSelfLink() throws Exception {
      when(definitionEntity.getAscriptions()).thenReturn(List.of());
      when(definitionService.getByIdWithArchetypes(defId)).thenReturn(definitionEntity);

      mockMvc
          .perform(get("/api/v1/definitions/{id}", defId).accept(MediaTypes.HAL_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(defId.toString()))
          .andExpect(jsonPath("$.subjectType").value("STRUCTURE"))
          .andExpect(jsonPath("$._links.self.href").exists());
    }

    @Test
    void getById_withAscriptions_hasFirstLastLinks() throws Exception {
      UUID oldestId = UUID.randomUUID();
      UUID newestId = UUID.randomUUID();
      // desc timestamp order: newest first
      AscriptionEntity newest = mockAscription(newestId, AscriptionStatusType.ACTIVE);
      AscriptionEntity oldest = mockAscription(oldestId, AscriptionStatusType.RETIRED);

      when(definitionEntity.getAscriptions()).thenReturn(List.of(newest, oldest));
      when(definitionService.getByIdWithArchetypes(defId)).thenReturn(definitionEntity);

      mockMvc
          .perform(get("/api/v1/definitions/{id}", defId).accept(MediaTypes.HAL_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$._links.first.href").exists())
          .andExpect(jsonPath("$._links.last.href").exists());
    }

    @Test
    void getById_withSingleAscription_hasFirstLastLinks() throws Exception {
      UUID ascId = UUID.randomUUID();
      AscriptionEntity draft = mockAscription(ascId, AscriptionStatusType.DRAFT);

      when(definitionEntity.getAscriptions()).thenReturn(List.of(draft));
      when(definitionService.getByIdWithArchetypes(defId)).thenReturn(definitionEntity);

      mockMvc
          .perform(get("/api/v1/definitions/{id}", defId).accept(MediaTypes.HAL_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$._links.first.href").exists())
          .andExpect(jsonPath("$._links.last.href").exists());
    }
  }

  // ========================================================================
  // LIST ASCRIPTIONS
  // ========================================================================

  @Nested
  class ListAscriptionsTests {

    @Test
    void listAscriptions_returnsAllWithSelfLink() throws Exception {
      AscriptionEntity asc = mockAscription(UUID.randomUUID(), AscriptionStatusType.ACTIVE);
      when(definitionEntity.getAscriptions()).thenReturn(List.of(asc));
      when(definitionService.getByIdWithArchetypes(defId)).thenReturn(definitionEntity);

      mockMvc
          .perform(get("/api/v1/definitions/{id}/ascriptions", defId).accept(MediaTypes.HAL_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$._embedded.ascriptions", hasSize(1)))
          .andExpect(jsonPath("$._embedded.ascriptions[0]._links.self.href").exists())
          .andExpect(jsonPath("$._links.self.href").exists());
    }

    @Test
    void listAscriptions_emptyList_returnsEmptyCollection() throws Exception {
      when(definitionEntity.getAscriptions()).thenReturn(List.of());
      when(definitionService.getByIdWithArchetypes(defId)).thenReturn(definitionEntity);

      mockMvc
          .perform(get("/api/v1/definitions/{id}/ascriptions", defId).accept(MediaTypes.HAL_JSON))
          .andExpect(status().isOk());
    }
  }

  // ========================================================================
  // GET LATEST ASCRIPTION
  // ========================================================================

  @Nested
  class GetLatestAscriptionTests {

    @Test
    void getLatestAscription_returnsActiveAscription() throws Exception {
      UUID ascId = UUID.randomUUID();
      AscriptionEntity active = mockAscription(ascId, AscriptionStatusType.ACTIVE);
      when(definitionEntity.getAscriptions()).thenReturn(List.of(active));
      when(definitionService.getByIdWithArchetypes(defId)).thenReturn(definitionEntity);

      mockMvc
          .perform(
              get("/api/v1/definitions/{id}/ascriptions/latest", defId).accept(MediaTypes.HAL_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(ascId.toString()))
          .andExpect(jsonPath("$.status").value("ACTIVE"))
          .andExpect(jsonPath("$._links.self.href").exists())
          .andExpect(jsonPath("$._links.describedby.href").exists())
          .andExpect(jsonPath("$._links.type.href").exists())
          .andExpect(jsonPath("$._links.collection.href").exists())
          .andExpect(jsonPath("$._links.create-form.href").exists());
    }

    @Test
    void getLatestAscription_prefersDeprecatedWhenNoActive() throws Exception {
      UUID ascId = UUID.randomUUID();
      AscriptionEntity depr = mockAscription(ascId, AscriptionStatusType.DEPRECATED);
      when(definitionEntity.getAscriptions()).thenReturn(List.of(depr));
      when(definitionService.getByIdWithArchetypes(defId)).thenReturn(definitionEntity);

      mockMvc
          .perform(
              get("/api/v1/definitions/{id}/ascriptions/latest", defId).accept(MediaTypes.HAL_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("DEPRECATED"));
    }

    @Test
    void getLatestAscription_noInEffect_returns404() throws Exception {
      AscriptionEntity draft = mockAscription(UUID.randomUUID(), AscriptionStatusType.DRAFT);
      when(definitionEntity.getAscriptions()).thenReturn(List.of(draft));
      when(definitionService.getByIdWithArchetypes(defId)).thenReturn(definitionEntity);

      mockMvc
          .perform(
              get("/api/v1/definitions/{id}/ascriptions/latest", defId).accept(MediaTypes.HAL_JSON))
          .andExpect(status().isNotFound());
    }
  }
}
