package cloud.poesis.sie.defman.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.service.AbstractAscriptionService;
import cloud.poesis.sie.defman.service.ArchetypeService;
import cloud.poesis.sie.defman.service.AscriptionService;
import cloud.poesis.sie.defman.service.AscriptionStatementProtectionService;
import cloud.poesis.sie.defman.service.DefinitionService;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AscriptionController.class)
@AutoConfigureMockMvc(addFilters = false)
class AscriptionControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean
  @SuppressWarnings("rawtypes")
  private Map<DefinitionSubjectType, AbstractAscriptionService> serviceRegistry;

  @MockitoBean private ArchetypeService archetypeService;

  @MockitoBean private AscriptionService ascriptionService;

  @MockitoBean private DefinitionService definitionService;

  @MockitoBean private AscriptionStatementProtectionService statementProtection;

  // Shared stubs
  private UUID ascriptionId;
  private UUID definitionId;
  private UUID archetypeDefId;
  private AscriptionEntity ascriptionEntity;
  private ArchetypeEntity archetypeEntity;
  private DefinitionEntity definitionEntity;

  @BeforeEach
  void setUp() {
    ascriptionId = UUID.randomUUID();
    definitionId = UUID.randomUUID();
    archetypeDefId = UUID.randomUUID();

    ObjectNode archetypeStatement = objectMapper.createObjectNode();
    archetypeStatement.put("title", "TestArchetype");
    archetypeStatement.putObject("properties");

    ObjectNode ascriptionStatement = objectMapper.createObjectNode().put("purpose", "test");

    // Mock entities using Mockito (IDs are DB-generated, null without mock)
    DefinitionEntity ascriptionDefEntity = mock(DefinitionEntity.class);
    when(ascriptionDefEntity.getId()).thenReturn(definitionId);

    DefinitionEntity archetypeDefEntity = mock(DefinitionEntity.class);
    when(archetypeDefEntity.getId()).thenReturn(archetypeDefId);

    archetypeEntity = mock(ArchetypeEntity.class);
    when(archetypeEntity.getId()).thenReturn(UUID.randomUUID());
    when(archetypeEntity.getStatement()).thenReturn(archetypeStatement);
    when(archetypeEntity.getDefinition()).thenReturn(archetypeDefEntity);

    ascriptionEntity = mock(AscriptionEntity.class);
    when(ascriptionEntity.getId()).thenReturn(ascriptionId);
    when(ascriptionEntity.getStatement()).thenReturn(ascriptionStatement);
    when(ascriptionEntity.getTimestamp()).thenReturn(Instant.parse("2025-01-01T00:00:00Z"));
    when(ascriptionEntity.getVersion()).thenReturn(1);
    when(ascriptionEntity.getStatus()).thenReturn(AscriptionStatusType.ACTIVE);
    when(ascriptionEntity.getDefinition()).thenReturn(ascriptionDefEntity);
    when(ascriptionEntity.getArchetype()).thenReturn(archetypeEntity);

    definitionEntity = mock(DefinitionEntity.class);
    when(definitionEntity.getId()).thenReturn(definitionId);
    when(definitionEntity.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);

    // AscriptionStatementProtectionService passthrough
    lenient()
        .when(statementProtection.applyInTransitProtection(any(), any()))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  private <T extends AscriptionEntity> T ascriptionEntity(Class<T> type) {
    DefinitionEntity def = ascriptionEntity.getDefinition();
    T entity = mock(type);
    when(entity.getId()).thenReturn(ascriptionId);
    when(entity.getStatement()).thenReturn(objectMapper.createObjectNode().put("purpose", "x"));
    when(entity.getTimestamp()).thenReturn(Instant.parse("2025-01-01T00:00:00Z"));
    when(entity.getVersion()).thenReturn(1);
    when(entity.getStatus()).thenReturn(AscriptionStatusType.ACTIVE);
    when(entity.getDefinition()).thenReturn(def);
    when(entity.getArchetype()).thenReturn(archetypeEntity);
    return entity;
  }

  // ========================================================================
  // CREATE
  // ========================================================================

  @Nested
  class CreateTests {

    @Test
    void create_returns201WithLocationAndBody() throws Exception {
      UUID archetypeId = UUID.randomUUID();
      ObjectNode stmt = objectMapper.createObjectNode().put("purpose", "order-processing");

      ArchetypeService.ArchetypeResolution resolution =
          new ArchetypeService.ArchetypeResolution(
              archetypeEntity, DefinitionSubjectType.STRUCTURE);
      when(archetypeService.resolveForCreation(archetypeId)).thenReturn(resolution);

      AbstractAscriptionService<?> structureService = mock(AbstractAscriptionService.class);
      when(serviceRegistry.get(DefinitionSubjectType.STRUCTURE)).thenReturn(structureService);
      doReturn(ascriptionEntity).when(structureService).create(eq(archetypeEntity), any(), any());
      when(definitionService.getById(definitionId)).thenReturn(definitionEntity);
      when(archetypeService.findEntityById(archetypeEntity.getId())).thenReturn(archetypeEntity);

      ObjectNode body = objectMapper.createObjectNode();
      body.put("archetypeId", archetypeId.toString());
      body.set("statement", stmt);

      mockMvc
          .perform(
              post("/api/v1/ascriptions")
                  .contentType(MediaType.APPLICATION_JSON)
                  .accept(MediaTypes.HAL_JSON)
                  .content(objectMapper.writeValueAsString(body)))
          .andExpect(status().isCreated())
          .andExpect(header().exists("Location"))
          .andExpect(jsonPath("$.id").value(ascriptionId.toString()))
          .andExpect(jsonPath("$.version").value(1))
          .andExpect(jsonPath("$._links.self.href").exists())
          .andExpect(jsonPath("$._links.describedby.href").exists())
          .andExpect(jsonPath("$._links.type.href").exists())
          .andExpect(jsonPath("$._links.collection.href").exists())
          .andExpect(jsonPath("$._links.create-form.href").exists());
    }

    @Test
    void create_withDefinitionId_passesItToService() throws Exception {
      UUID archetypeId = UUID.randomUUID();
      UUID existingDefId = UUID.randomUUID();
      ObjectNode stmt = objectMapper.createObjectNode().put("purpose", "test");

      ArchetypeService.ArchetypeResolution resolution =
          new ArchetypeService.ArchetypeResolution(
              archetypeEntity, DefinitionSubjectType.STRUCTURE);
      when(archetypeService.resolveForCreation(archetypeId)).thenReturn(resolution);

      AbstractAscriptionService<?> svc = mock(AbstractAscriptionService.class);
      when(serviceRegistry.get(DefinitionSubjectType.STRUCTURE)).thenReturn(svc);
      doReturn(ascriptionEntity).when(svc).create(eq(archetypeEntity), any(), eq(existingDefId));
      when(definitionService.getById(definitionId)).thenReturn(definitionEntity);
      when(archetypeService.findEntityById(archetypeEntity.getId())).thenReturn(archetypeEntity);

      ObjectNode body = objectMapper.createObjectNode();
      body.put("archetypeId", archetypeId.toString());
      body.put("definitionId", existingDefId.toString());
      body.set("statement", stmt);

      mockMvc
          .perform(
              post("/api/v1/ascriptions")
                  .contentType(MediaType.APPLICATION_JSON)
                  .accept(MediaTypes.HAL_JSON)
                  .content(objectMapper.writeValueAsString(body)))
          .andExpect(status().isCreated());
    }

    @Test
    void create_missingArchetypeId_returns400() throws Exception {
      ObjectNode body = objectMapper.createObjectNode();
      body.set("statement", objectMapper.createObjectNode());
      // archetypeId is @NotNull

      mockMvc
          .perform(
              post("/api/v1/ascriptions")
                  .contentType(MediaType.APPLICATION_JSON)
                  .accept(MediaTypes.HAL_JSON)
                  .content(objectMapper.writeValueAsString(body)))
          .andExpect(status().isBadRequest());
    }

    @Test
    void create_missingStatement_returns400() throws Exception {
      ObjectNode body = objectMapper.createObjectNode();
      body.put("archetypeId", UUID.randomUUID().toString());
      // statement is @NotNull

      mockMvc
          .perform(
              post("/api/v1/ascriptions")
                  .contentType(MediaType.APPLICATION_JSON)
                  .accept(MediaTypes.HAL_JSON)
                  .content(objectMapper.writeValueAsString(body)))
          .andExpect(status().isBadRequest());
    }
  }

  // ========================================================================
  // GET BY ID
  // ========================================================================

  @Nested
  class GetByIdTests {

    @Test
    void getById_returns200WithLinks() throws Exception {
      when(ascriptionService.getById(ascriptionId)).thenReturn(ascriptionEntity);
      when(definitionService.getById(definitionId)).thenReturn(definitionEntity);
      when(archetypeService.findEntityById(archetypeEntity.getId())).thenReturn(archetypeEntity);

      mockMvc
          .perform(get("/api/v1/ascriptions/{id}", ascriptionId).accept(MediaTypes.HAL_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(ascriptionId.toString()))
          .andExpect(jsonPath("$.status").value("ACTIVE"))
          .andExpect(jsonPath("$._links.self.href").exists())
          .andExpect(jsonPath("$._links.describedby.href").exists())
          .andExpect(jsonPath("$._links.type.href").exists())
          .andExpect(jsonPath("$._links.collection.href").exists())
          .andExpect(jsonPath("$._links.create-form.href").exists());
    }
  }

  // ========================================================================
  // LIST
  // ========================================================================

  @Nested
  class ListTests {

    @Test
    void list_byType_returns200WithPageMetadata() throws Exception {
      AbstractAscriptionService<?> svc = mock(AbstractAscriptionService.class);
      when(serviceRegistry.get(DefinitionSubjectType.STRUCTURE)).thenReturn(svc);

      Page<StructureEntity> page =
          new PageImpl<>(
              List.of(ascriptionEntity(StructureEntity.class)), PageRequest.of(0, 20), 1);
      doReturn(page).when(svc).findAll(any(Pageable.class));
      Map<UUID, ArchetypeEntity> archetypeMap = Map.of(archetypeEntity.getId(), archetypeEntity);
      when(archetypeService.getByIds(any())).thenReturn(archetypeMap);

      mockMvc
          .perform(
              get("/api/v1/ascriptions").param("type", "STRUCTURE").accept(MediaTypes.HAL_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.page.totalElements").value(1))
          .andExpect(jsonPath("$._embedded.ascriptions[0]._links.self.href").exists());
    }

    @Test
    void list_withStatusFilter_delegatesToFindAllByStatus() throws Exception {
      AbstractAscriptionService<?> svc = mock(AbstractAscriptionService.class);
      when(serviceRegistry.get(DefinitionSubjectType.STRUCTURE)).thenReturn(svc);

      Page<AscriptionEntity> emptyPage = Page.empty(PageRequest.of(0, 20));
      doReturn(emptyPage)
          .when(svc)
          .findAllByStatus(eq(AscriptionStatusType.ACTIVE), any(Pageable.class));

      mockMvc
          .perform(
              get("/api/v1/ascriptions")
                  .param("type", "STRUCTURE")
                  .param("status", "ACTIVE")
                  .accept(MediaTypes.HAL_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void list_emptyPage_returns200WithEmptyContent() throws Exception {
      AbstractAscriptionService<?> svc = mock(AbstractAscriptionService.class);
      when(serviceRegistry.get(DefinitionSubjectType.ARCHETYPE)).thenReturn(svc);
      doReturn(Page.empty(PageRequest.of(0, 20))).when(svc).findAll(any(Pageable.class));

      mockMvc
          .perform(
              get("/api/v1/ascriptions").param("type", "ARCHETYPE").accept(MediaTypes.HAL_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void list_withArchetypeFilter_resolvesById() throws Exception {
      AbstractAscriptionService<?> svc = mock(AbstractAscriptionService.class);
      when(serviceRegistry.get(DefinitionSubjectType.STRUCTURE)).thenReturn(svc);
      when(archetypeService.findEntityById(archetypeDefId)).thenReturn(archetypeEntity);
      doReturn(Page.empty(PageRequest.of(0, 20)))
          .when(svc)
          .findAllFiltered(any(), any(), any(), any(Pageable.class));

      mockMvc
          .perform(
              get("/api/v1/ascriptions")
                  .param("type", "STRUCTURE")
                  .param("archetype", archetypeDefId.toString())
                  .accept(MediaTypes.HAL_JSON))
          .andExpect(status().isOk());
    }

    @Test
    void list_withArchetypeFilterByTitle_resolvesByTitle() throws Exception {
      AbstractAscriptionService<?> svc = mock(AbstractAscriptionService.class);
      when(serviceRegistry.get(DefinitionSubjectType.STRUCTURE)).thenReturn(svc);
      when(archetypeService.findInEffectByTitle("TestArchetype"))
          .thenReturn(Optional.of(archetypeEntity));
      doReturn(Page.empty(PageRequest.of(0, 20)))
          .when(svc)
          .findAllFiltered(any(), any(), any(), any(Pageable.class));

      mockMvc
          .perform(
              get("/api/v1/ascriptions")
                  .param("type", "STRUCTURE")
                  .param("archetype", "TestArchetype")
                  .accept(MediaTypes.HAL_JSON))
          .andExpect(status().isOk());
    }

    @Test
    void list_noServiceForType_returns500() throws Exception {
      when(serviceRegistry.get(DefinitionSubjectType.DIRECTIVE)).thenReturn(null);

      mockMvc
          .perform(
              get("/api/v1/ascriptions").param("type", "DIRECTIVE").accept(MediaTypes.HAL_JSON))
          .andExpect(status().isInternalServerError());
    }
  }

  // ========================================================================
  // GET SCHEMA
  // ========================================================================

  @Nested
  class GetSchemaTests {

    @Test
    void getSchema_returnsJsonSchemaWithInlinedStatement() throws Exception {
      when(ascriptionService.getById(ascriptionId)).thenReturn(ascriptionEntity);
      when(archetypeService.findEntityById(archetypeEntity.getId())).thenReturn(archetypeEntity);

      mockMvc
          .perform(
              get("/api/v1/ascriptions/{id}/schema", ascriptionId)
                  .accept("application/schema+json"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.$schema").value("https://json-schema.org/draft/2020-12/schema"))
          .andExpect(jsonPath("$.title").value("Ascription"))
          .andExpect(jsonPath("$.properties.id.type").value("string"))
          .andExpect(jsonPath("$.properties.statement").exists())
          .andExpect(jsonPath("$.properties.status.enum").isArray());
    }
  }

  // ========================================================================
  // QUERY FILTER HELPERS (validated through list endpoint indirectly)
  // ========================================================================

  @Nested
  class QueryFilterTests {

    @Test
    void list_withStatementFiltersButNoArchetype_returns400() throws Exception {
      AbstractAscriptionService<?> svc = mock(AbstractAscriptionService.class);
      when(serviceRegistry.get(DefinitionSubjectType.STRUCTURE)).thenReturn(svc);

      mockMvc
          .perform(
              get("/api/v1/ascriptions")
                  .param("type", "STRUCTURE")
                  .param("statement.purpose", "order-processing")
                  .accept(MediaTypes.HAL_JSON))
          .andExpect(status().isBadRequest());
    }

    @Test
    void list_withNonQueryableProperty_returns400() throws Exception {
      AbstractAscriptionService<?> svc = mock(AbstractAscriptionService.class);
      when(serviceRegistry.get(DefinitionSubjectType.STRUCTURE)).thenReturn(svc);

      // purpose is not $gsm:queryable in our mock archetype statement
      ObjectNode archStmt = objectMapper.createObjectNode();
      archStmt.put("title", "TestArchetype");
      ObjectNode props = archStmt.putObject("properties");
      props.putObject("purpose"); // no $gsm:queryable

      ArchetypeEntity localArch = mock(ArchetypeEntity.class);
      DefinitionEntity localArchDef = mock(DefinitionEntity.class);
      when(localArchDef.getId()).thenReturn(archetypeDefId);
      when(localArch.getStatement()).thenReturn(archStmt);
      when(localArch.getDefinition()).thenReturn(localArchDef);
      when(archetypeService.findInEffectByTitle("TestArchetype"))
          .thenReturn(Optional.of(localArch));

      mockMvc
          .perform(
              get("/api/v1/ascriptions")
                  .param("type", "STRUCTURE")
                  .param("archetype", "TestArchetype")
                  .param("statement.purpose", "test")
                  .accept(MediaTypes.HAL_JSON))
          .andExpect(status().isBadRequest());
    }

    @Test
    void list_withQueryableProperty_passes() throws Exception {
      AbstractAscriptionService<?> svc = mock(AbstractAscriptionService.class);
      when(serviceRegistry.get(DefinitionSubjectType.STRUCTURE)).thenReturn(svc);

      ObjectNode archStmt = objectMapper.createObjectNode();
      archStmt.put("title", "TestArchetype");
      ObjectNode props = archStmt.putObject("properties");
      props.putObject("purpose").put("$gsm:queryable", true);

      ArchetypeEntity localArch = mock(ArchetypeEntity.class);
      DefinitionEntity localArchDef = mock(DefinitionEntity.class);
      when(localArchDef.getId()).thenReturn(archetypeDefId);
      when(localArch.getStatement()).thenReturn(archStmt);
      when(localArch.getDefinition()).thenReturn(localArchDef);
      when(archetypeService.findInEffectByTitle("TestArchetype"))
          .thenReturn(Optional.of(localArch));
      doReturn(Page.empty(PageRequest.of(0, 20)))
          .when(svc)
          .findAllFiltered(any(), any(), any(), any(Pageable.class));

      mockMvc
          .perform(
              get("/api/v1/ascriptions")
                  .param("type", "STRUCTURE")
                  .param("archetype", "TestArchetype")
                  .param("statement.purpose", "test")
                  .accept(MediaTypes.HAL_JSON))
          .andExpect(status().isOk());
    }
  }
}
