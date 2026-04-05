package cloud.poesis.sie.defman.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;

@SuppressWarnings({"rawtypes", "unchecked"})
class OpenApiCustomizerConfigTest {

  private GlobalOpenApiCustomizer customizer;

  @BeforeEach
  void setUp() {
    customizer = new OpenApiCustomizerConfig().linksSchemaCustomizer();
  }

  private OpenAPI openApiWithSchemas(Map<String, Schema> schemas) {
    Components components = new Components();
    components.setSchemas(new LinkedHashMap<>(schemas));
    OpenAPI api = new OpenAPI();
    api.setComponents(components);
    return api;
  }

  // ========================================================================
  // RENAME SCHEMAS
  // ========================================================================

  @Nested
  class RenameTests {

    @Test
    void renamesEntityModelAscriptionToAscription() {
      ObjectSchema original = new ObjectSchema();
      original.addProperty("id", new StringSchema());

      Map<String, Schema> schemas = new LinkedHashMap<>();
      schemas.put("EntityModelAscription", original);

      OpenAPI api = openApiWithSchemas(schemas);
      customizer.customise(api);

      assertThat(api.getComponents().getSchemas()).containsKey("Ascription");
      assertThat(api.getComponents().getSchemas()).doesNotContainKey("EntityModelAscription");
    }

    @Test
    void renamesAllKnownWrappers() {
      Map<String, Schema> schemas = new LinkedHashMap<>();
      schemas.put("EntityModelAscription", new ObjectSchema());
      schemas.put("EntityModelDefinition", new ObjectSchema());
      schemas.put("EntityModelAscriptionStatusTransition", new ObjectSchema());
      schemas.put("CollectionModelEntityModelAscription", new ObjectSchema());
      schemas.put("CollectionModelEntityModelAscriptionStatusTransition", new ObjectSchema());
      schemas.put("PagedModelEntityModelAscription", new ObjectSchema());

      OpenAPI api = openApiWithSchemas(schemas);
      customizer.customise(api);

      Map<String, Schema> result = api.getComponents().getSchemas();
      assertThat(result).containsKey("Ascription");
      assertThat(result).containsKey("Definition");
      assertThat(result).containsKey("AscriptionStatusTransition");
      assertThat(result).containsKey("AscriptionCollection");
      assertThat(result).containsKey("AscriptionStatusTransitionCollection");
      assertThat(result).containsKey("AscriptionPage");
    }

    @Test
    void rewritesRefPointersInOtherSchemas() {
      Schema<?> refSchema = new Schema<>();
      refSchema.set$ref("#/components/schemas/EntityModelAscription");

      ObjectSchema container = new ObjectSchema();
      container.addProperty("item", refSchema);

      Map<String, Schema> schemas = new LinkedHashMap<>();
      schemas.put("EntityModelAscription", new ObjectSchema());
      schemas.put("Container", container);

      OpenAPI api = openApiWithSchemas(schemas);
      customizer.customise(api);

      Schema itemProp =
          (Schema) api.getComponents().getSchemas().get("Container").getProperties().get("item");
      assertThat(itemProp.get$ref()).isEqualTo("#/components/schemas/Ascription");
    }

    @Test
    void rewritesRefInPathResponses() {
      Schema<?> responseSchema = new Schema<>();
      responseSchema.set$ref("#/components/schemas/EntityModelAscription");

      MediaType mt = new MediaType();
      mt.setSchema(responseSchema);
      Content content = new Content();
      content.addMediaType("application/hal+json", mt);

      ApiResponse response = new ApiResponse();
      response.setContent(content);
      ApiResponses responses = new ApiResponses();
      responses.addApiResponse("200", response);

      Operation op = new Operation();
      op.setResponses(responses);
      PathItem path = new PathItem();
      path.setGet(op);

      Map<String, Schema> schemas = new LinkedHashMap<>();
      schemas.put("EntityModelAscription", new ObjectSchema());

      OpenAPI api = openApiWithSchemas(schemas);
      api.setPaths(new Paths());
      api.getPaths().addPathItem("/api/v1/ascriptions/{id}", path);

      customizer.customise(api);

      String updatedRef =
          api.getPaths()
              .get("/api/v1/ascriptions/{id}")
              .getGet()
              .getResponses()
              .get("200")
              .getContent()
              .get("application/hal+json")
              .getSchema()
              .get$ref();
      assertThat(updatedRef).isEqualTo("#/components/schemas/Ascription");
    }
  }

  // ========================================================================
  // STRIP ADDITIONAL PROPERTIES
  // ========================================================================

  @Nested
  class StripAdditionalPropertiesTests {

    @Test
    void stripsEmptySchemaAdditionalProperties() {
      ObjectSchema schema = new ObjectSchema();
      schema.addProperty("id", new StringSchema());
      schema.setAdditionalProperties(new Schema<>()); // empty schema

      Map<String, Schema> schemas = new LinkedHashMap<>();
      schemas.put("SomeSchema", schema);

      OpenAPI api = openApiWithSchemas(schemas);
      customizer.customise(api);

      assertThat(api.getComponents().getSchemas().get("SomeSchema").getAdditionalProperties())
          .isNull();
    }

    @Test
    void stripsTrueAdditionalProperties() {
      ObjectSchema schema = new ObjectSchema();
      schema.setAdditionalProperties(Boolean.TRUE);

      Map<String, Schema> schemas = new LinkedHashMap<>();
      schemas.put("SomeSchema", schema);

      OpenAPI api = openApiWithSchemas(schemas);
      customizer.customise(api);

      assertThat(api.getComponents().getSchemas().get("SomeSchema").getAdditionalProperties())
          .isNull();
    }

    @Test
    void preservesNonEmptyAdditionalProperties() {
      ObjectSchema addProp = new ObjectSchema();
      addProp.setType("string");

      ObjectSchema schema = new ObjectSchema();
      schema.setAdditionalProperties(addProp);

      Map<String, Schema> schemas = new LinkedHashMap<>();
      schemas.put("SomeSchema", schema);

      OpenAPI api = openApiWithSchemas(schemas);
      customizer.customise(api);

      assertThat(api.getComponents().getSchemas().get("SomeSchema").getAdditionalProperties())
          .isNotNull();
    }

    @Test
    void stripsRecursivelyInProperties() {
      ObjectSchema nested = new ObjectSchema();
      nested.setAdditionalProperties(new Schema<>()); // empty
      nested.addProperty("name", new StringSchema());

      ObjectSchema parent = new ObjectSchema();
      parent.addProperty("child", nested);

      Map<String, Schema> schemas = new LinkedHashMap<>();
      schemas.put("Parent", parent);

      OpenAPI api = openApiWithSchemas(schemas);
      customizer.customise(api);

      Schema childSchema =
          (Schema) api.getComponents().getSchemas().get("Parent").getProperties().get("child");
      assertThat(childSchema.getAdditionalProperties()).isNull();
    }
  }

  // ========================================================================
  // INLINE HATEOAS INFRASTRUCTURE
  // ========================================================================

  @Nested
  class InlineHateoasTests {

    @Test
    void inlinesLinksForAscription() {
      ObjectSchema ascription = new ObjectSchema();
      ascription.addProperty("id", new StringSchema());
      ascription.addProperty("_links", new ObjectSchema());

      Map<String, Schema> schemas = new LinkedHashMap<>();
      schemas.put("Ascription", ascription);

      OpenAPI api = openApiWithSchemas(schemas);
      customizer.customise(api);

      Schema links =
          (Schema) api.getComponents().getSchemas().get("Ascription").getProperties().get("_links");
      assertThat(links.getProperties())
          .containsKeys("self", "describedby", "type", "collection", "create-form");
    }

    @Test
    void inlinesLinksForDefinition() {
      ObjectSchema definition = new ObjectSchema();
      definition.addProperty("id", new StringSchema());
      definition.addProperty("_links", new ObjectSchema());

      Map<String, Schema> schemas = new LinkedHashMap<>();
      schemas.put("Definition", definition);

      OpenAPI api = openApiWithSchemas(schemas);
      customizer.customise(api);

      Schema links =
          (Schema) api.getComponents().getSchemas().get("Definition").getProperties().get("_links");
      assertThat(links.getProperties()).containsKeys("self", "first", "last");
    }

    @Test
    void inlinesLinksForAscriptionStatusTransition() {
      ObjectSchema transition = new ObjectSchema();
      transition.addProperty("id", new StringSchema());
      transition.addProperty("_links", new ObjectSchema());

      Map<String, Schema> schemas = new LinkedHashMap<>();
      schemas.put("AscriptionStatusTransition", transition);

      OpenAPI api = openApiWithSchemas(schemas);
      customizer.customise(api);

      Schema links =
          (Schema)
              api.getComponents()
                  .getSchemas()
                  .get("AscriptionStatusTransition")
                  .getProperties()
                  .get("_links");
      assertThat(links.getProperties())
          .containsKeys(
              "self", "collection", "up", "first", "last", "previous", "next", "create-form");
    }

    @Test
    void rebuildEmbeddedForCollectionSchemas() {
      ObjectSchema collection = new ObjectSchema();
      collection.addProperty("_embedded", new ObjectSchema());
      collection.addProperty("_links", new ObjectSchema());

      Map<String, Schema> schemas = new LinkedHashMap<>();
      schemas.put("AscriptionCollection", collection);
      schemas.put("Ascription", new ObjectSchema());

      OpenAPI api = openApiWithSchemas(schemas);
      customizer.customise(api);

      Schema embedded =
          (Schema)
              api.getComponents()
                  .getSchemas()
                  .get("AscriptionCollection")
                  .getProperties()
                  .get("_embedded");
      assertThat(embedded.getProperties()).containsKey("ascriptions");
    }

    @Test
    void inlinesPageMetadataForAscriptionPage() {
      ObjectSchema page = new ObjectSchema();
      page.addProperty("_links", new ObjectSchema());
      page.addProperty("_embedded", new ObjectSchema());
      page.addProperty("page", new Schema<>().$ref("#/components/schemas/PageMetadata"));

      Map<String, Schema> schemas = new LinkedHashMap<>();
      schemas.put("AscriptionPage", page);
      schemas.put("PageMetadata", new ObjectSchema());
      schemas.put("Ascription", new ObjectSchema());

      OpenAPI api = openApiWithSchemas(schemas);
      customizer.customise(api);

      Schema pageSchema =
          (Schema)
              api.getComponents().getSchemas().get("AscriptionPage").getProperties().get("page");
      assertThat(pageSchema.getProperties())
          .containsKeys("size", "totalElements", "totalPages", "number");
    }

    @Test
    void removesStandaloneInfrastructureSchemas() {
      Map<String, Schema> schemas = new LinkedHashMap<>();
      schemas.put("Links", new ObjectSchema());
      schemas.put("Link", new ObjectSchema());
      schemas.put("PageMetadata", new ObjectSchema());
      schemas.put("SomeOther", new ObjectSchema());

      OpenAPI api = openApiWithSchemas(schemas);
      customizer.customise(api);

      assertThat(api.getComponents().getSchemas())
          .doesNotContainKeys("Links", "Link", "PageMetadata")
          .containsKey("SomeOther");
    }
  }

  // ========================================================================
  // HAL-FORMS VARIANTS
  // ========================================================================

  @Nested
  class HalFormsVariantTests {

    @Test
    void createsHalFormsVariantForAscriptionPage() {
      ObjectSchema page = new ObjectSchema();
      page.addProperty("_links", new ObjectSchema());
      page.addProperty("_embedded", new ObjectSchema());

      Map<String, Schema> schemas = new LinkedHashMap<>();
      schemas.put("AscriptionPage", page);
      schemas.put("Ascription", new ObjectSchema());

      OpenAPI api = openApiWithSchemas(schemas);
      customizer.customise(api);

      assertThat(api.getComponents().getSchemas()).containsKey("AscriptionPageHalForms");
      Schema variant = api.getComponents().getSchemas().get("AscriptionPageHalForms");
      assertThat(variant.getAllOf()).hasSize(2);
    }

    @Test
    void rewritesHalFormsPathRefsToVariant() {
      Schema<?> responseSchema = new Schema<>();
      responseSchema.set$ref("#/components/schemas/AscriptionPage");

      MediaType mt = new MediaType();
      mt.setSchema(responseSchema);
      Content content = new Content();
      content.addMediaType("application/palgrave-hal-forms+json", mt);

      ApiResponse response = new ApiResponse();
      response.setContent(content);
      ApiResponses responses = new ApiResponses();
      responses.addApiResponse("200", response);

      Operation op = new Operation();
      op.setResponses(responses);
      PathItem path = new PathItem();
      path.setGet(op);

      ObjectSchema page = new ObjectSchema();
      page.addProperty("_links", new ObjectSchema());
      page.addProperty("_embedded", new ObjectSchema());

      Map<String, Schema> schemas = new LinkedHashMap<>();
      schemas.put("AscriptionPage", page);
      schemas.put("Ascription", new ObjectSchema());

      OpenAPI api = openApiWithSchemas(schemas);
      api.setPaths(new Paths());
      api.getPaths().addPathItem("/api/v1/ascriptions", path);

      customizer.customise(api);

      String updatedRef =
          api.getPaths()
              .get("/api/v1/ascriptions")
              .getGet()
              .getResponses()
              .get("200")
              .getContent()
              .get("application/palgrave-hal-forms+json")
              .getSchema()
              .get$ref();
      assertThat(updatedRef).isEqualTo("#/components/schemas/AscriptionPageHalForms");
    }
  }

  // ========================================================================
  // EDGE CASES
  // ========================================================================

  @Nested
  class EdgeCaseTests {

    @Test
    void handlesNullComponentsSchemas() {
      OpenAPI api = new OpenAPI();
      api.setComponents(new Components()); // no schemas
      customizer.customise(api); // should not throw
    }

    @Test
    void handlesNullComponents() {
      OpenAPI api = new OpenAPI(); // no components at all
      customizer.customise(api); // should not throw
    }

    @Test
    void handlesNullPaths() {
      Map<String, Schema> schemas = new LinkedHashMap<>();
      schemas.put("EntityModelAscription", new ObjectSchema());
      OpenAPI api = openApiWithSchemas(schemas);
      // paths is null
      customizer.customise(api); // should not throw
    }
  }
}
