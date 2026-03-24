package cloud.poesis.sie.defman.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;

/**
 * Focused post-processing of the generated OpenAPI spec:
 * <ol>
 * <li>Rename springdoc-hateoas wrapper schemas to clean domain names</li>
 * <li>Strip empty {@code additionalProperties} (cosmetic — removes
 * Swagger UI {@code additionalProp1/2/3} rendering artifacts)</li>
 * <li>Inline HATEOAS infrastructure ({@code _links} with per-resource
 * link relations, {@code PageMetadata}) and remove standalone
 * {@code Link}, {@code Links}, {@code PageMetadata} schemas</li>
 * </ol>
 *
 * <p>
 * Bean name {@code linksSchemaCustomizer} overrides springdoc's
 * {@code OpenApiHateoasLinksCustomizer} via
 * {@code @ConditionalOnMissingBean(name = "linksSchemaCustomizer")}.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Configuration
class OpenApiCustomizerConfig {

    private static final Map<String, String> SCHEMA_RENAMES = Map.of(
            "EntityModelAscription", "Ascription",
            "EntityModelDefinition", "Definition",
            "EntityModelAscriptionStatusTransition", "AscriptionStatusTransition",
            "CollectionModelEntityModelAscription", "AscriptionCollection",
            "CollectionModelEntityModelAscriptionStatusTransition", "AscriptionStatusTransitionCollection",
            "PagedModelEntityModelAscription", "AscriptionPage");

    /**
     * Per-resource HAL link relations (post-rename schema names).
     * Each entry maps a schema name to an ordered map of rel → description.
     */
    private static final Map<String, Map<String, String>> RESOURCE_LINKS = Map.of(
            "Ascription", linkedMap(
                    "self", "This ascription resource",
                    "describedby",
                    "Composed JSON Schema: Ascription envelope with per-instance Archetype schema inlined as the statement property (application/schema+json)",
                    "type", "The typing Archetype definition for this ascription",
                    "collection", "All ascriptions for this ascription's parent definition",
                    "create-form", "Endpoint that accepts new ascriptions (POST)"),
            "Definition", linkedMap(
                    "self", "This definition resource",
                    "first", "Oldest ascription for this definition (by timestamp)",
                    "last", "Newest ascription for this definition (by timestamp)",
                    "latest-version", "Most recent governance-approved ascription (version >= 1)",
                    "version-history", "All governance-approved ascription versions for this definition"),
            "AscriptionStatusTransition", linkedMap(
                    "self", "This transition record",
                    "collection", "All transitions for the owning ascription",
                    "up", "The owning ascription",
                    "first", "Oldest transition for the owning ascription",
                    "last", "Newest transition for the owning ascription",
                    "previous", "Previous transition in chronological order",
                    "next", "Next transition in chronological order",
                    "create-form", "Endpoint that accepts new status transitions (POST)"),
            "AscriptionCollection", linkedMap("self", "This collection"),
            "AscriptionPage", linkedMap("self", "This page"),
            "AscriptionStatusTransitionCollection", linkedMap("self", "This collection"));

    /**
     * Collection/page schemas → (embedded relation name, item schema ref).
     * Used to rebuild {@code _embedded} inline with a clean array.
     */
    private static final Map<String, String[]> COLLECTION_EMBEDDED = Map.of(
            "AscriptionCollection", new String[] { "ascriptions", "Ascription" },
            "AscriptionPage", new String[] { "ascriptions", "Ascription" },
            "AscriptionStatusTransitionCollection",
            new String[] { "ascriptionStatusTransitions", "AscriptionStatusTransition" });

    private static final String HAL_FORMS_MEDIA_TYPE = "application/palgrave-hal-forms+json";

    /**
     * Schemas that carry HAL-FORMS {@code _templates} (resources with declared
     * affordances). Only these get a separate HAL-FORMS variant schema.
     */
    private static final Set<String> HALFORMS_SCHEMAS = Set.of("AscriptionPage");

    /**
     * Single customizer that replaces springdoc's {@code linksSchemaCustomizer}
     * and performs all spec post-processing in deterministic order.
     */
    @Bean
    GlobalOpenApiCustomizer linksSchemaCustomizer() {
        return openApi -> {
            renameSchemas(openApi);
            stripEmptyAdditionalProperties(openApi);
            inlineHateoasInfrastructure(openApi);
        };
    }

    // ------------------------------------------------------------------
    // Phase 1: Rename wrapper schemas and rewrite all $ref pointers
    // ------------------------------------------------------------------

    private void renameSchemas(OpenAPI openApi) {
        @SuppressWarnings("rawtypes")
        Map<String, Schema> schemas = schemas(openApi);
        if (schemas == null)
            return;

        Map<String, String> refMap = new LinkedHashMap<>();
        for (var entry : SCHEMA_RENAMES.entrySet()) {
            refMap.put("#/components/schemas/" + entry.getKey(),
                    "#/components/schemas/" + entry.getValue());
        }

        for (var entry : SCHEMA_RENAMES.entrySet()) {
            @SuppressWarnings("rawtypes")
            Schema s = schemas.remove(entry.getKey());
            if (s != null)
                schemas.put(entry.getValue(), s);
        }

        schemas.values().forEach(s -> rewriteRefs(s, refMap));
        if (openApi.getPaths() != null) {
            openApi.getPaths().values().forEach(p -> rewritePathRefs(p, refMap));
        }
    }

    @SuppressWarnings("rawtypes")
    private void rewriteRefs(Schema schema, Map<String, String> refMap) {
        if (schema == null)
            return;
        String ref = schema.get$ref();
        if (ref != null) {
            String updated = refMap.get(ref);
            if (updated != null)
                schema.set$ref(updated);
        }
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(p -> rewriteRefs((Schema) p, refMap));
        }
        if (schema.getItems() != null) {
            rewriteRefs(schema.getItems(), refMap);
        }
        rewriteList(schema.getAllOf(), refMap);
        rewriteList(schema.getOneOf(), refMap);
        rewriteList(schema.getAnyOf(), refMap);
        if (schema.getAdditionalProperties() instanceof Schema addProp) {
            rewriteRefs(addProp, refMap);
        }
    }

    @SuppressWarnings("rawtypes")
    private void rewriteList(java.util.List<Schema> list, Map<String, String> refMap) {
        if (list != null)
            list.forEach(s -> rewriteRefs(s, refMap));
    }

    private void rewritePathRefs(PathItem pathItem, Map<String, String> refMap) {
        pathItem.readOperations().forEach(op -> {
            if (op.getRequestBody() != null) {
                rewriteContentRefs(op.getRequestBody().getContent(), refMap);
            }
            if (op.getResponses() != null) {
                op.getResponses().values()
                        .forEach(r -> rewriteContentRefs(r.getContent(), refMap));
            }
        });
    }

    private void rewriteContentRefs(Content content, Map<String, String> refMap) {
        if (content == null)
            return;
        content.values().forEach(mt -> {
            if (mt.getSchema() != null)
                rewriteRefs(mt.getSchema(), refMap);
        });
    }

    // ------------------------------------------------------------------
    // Phase 2: Strip empty additionalProperties (cosmetic cleanup)
    // ------------------------------------------------------------------

    private void stripEmptyAdditionalProperties(OpenAPI openApi) {
        @SuppressWarnings("rawtypes")
        Map<String, Schema> schemas = schemas(openApi);
        if (schemas == null)
            return;
        schemas.values().forEach(this::stripRecursive);
    }

    @SuppressWarnings("rawtypes")
    private void stripRecursive(Schema schema) {
        if (schema == null)
            return;
        if (isStrippableAdditionalProperties(schema.getAdditionalProperties())) {
            schema.setAdditionalProperties(null);
        }
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(p -> stripRecursive((Schema) p));
        }
        if (schema.getItems() != null) {
            stripRecursive(schema.getItems());
        }
        stripList(schema.getAllOf());
        stripList(schema.getOneOf());
        stripList(schema.getAnyOf());
    }

    @SuppressWarnings("rawtypes")
    private void stripList(java.util.List<Schema> list) {
        if (list != null)
            list.forEach(this::stripRecursive);
    }

    /**
     * Returns {@code true} for {@code additionalProperties: {}} (empty Schema)
     * or {@code additionalProperties: true} — both are semantically equivalent
     * to omitting the keyword but cause Swagger UI to render example
     * {@code additionalProp1/2/3} fields.
     */
    @SuppressWarnings("rawtypes")
    private boolean isStrippableAdditionalProperties(Object obj) {
        if (Boolean.TRUE.equals(obj))
            return true;
        return obj instanceof Schema s
                && s.get$ref() == null
                && s.getType() == null
                && s.getProperties() == null
                && s.getItems() == null
                && s.getAllOf() == null
                && s.getOneOf() == null
                && s.getAnyOf() == null;
    }

    // ------------------------------------------------------------------
    // Phase 3: Inline HATEOAS infrastructure (Links, Link, PageMetadata)
    // ------------------------------------------------------------------

    /**
     * Replaces generic {@code _links: $ref Links} with per-resource inline
     * schemas listing actual HAL link relations, inlines PageMetadata into
     * AscriptionPage, and removes standalone infrastructure schemas.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void inlineHateoasInfrastructure(OpenAPI openApi) {
        Map<String, Schema> schemas = schemas(openApi);
        if (schemas == null)
            return;

        for (var entry : RESOURCE_LINKS.entrySet()) {
            Schema schema = schemas.get(entry.getKey());
            if (schema != null && schema.getProperties() != null) {
                schema.getProperties().put("_links", buildLinksSchema(entry.getValue()));
            }
        }

        // Rebuild _embedded with clean inline array (no EntityModel wrapper)
        for (var entry : COLLECTION_EMBEDDED.entrySet()) {
            Schema schema = schemas.get(entry.getKey());
            if (schema != null && schema.getProperties() != null) {
                String rel = entry.getValue()[0];
                String itemRef = entry.getValue()[1];
                schema.getProperties().put("_embedded", buildEmbeddedSchema(rel, itemRef));
            }
        }

        Schema ascriptionPage = schemas.get("AscriptionPage");
        if (ascriptionPage != null && ascriptionPage.getProperties() != null) {
            ascriptionPage.getProperties().put("page", buildPageMetadataSchema());
        }

        schemas.remove("Links");
        schemas.remove("Link");
        schemas.remove("PageMetadata");

        // Create HAL-FORMS variant schemas with _templates for resources
        // that declare affordances, and rewrite path-level content refs
        createHalFormsVariants(openApi, schemas);
    }

    /**
     * For each schema in {@link #HALFORMS_SCHEMAS}, creates an
     * {@code allOf}-composed variant that adds {@code _templates}, and
     * rewrites {@code application/palgrave-hal-forms+json} response entries
     * to reference the variant instead of the base schema.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void createHalFormsVariants(OpenAPI openApi, Map<String, Schema> schemas) {
        for (String schemaName : HALFORMS_SCHEMAS) {
            if (!schemas.containsKey(schemaName))
                continue;
            String variantName = schemaName + "HalForms";

            Schema<?> baseRef = new Schema<>();
            baseRef.set$ref("#/components/schemas/" + schemaName);
            ObjectSchema extra = new ObjectSchema();
            extra.addProperty("_templates", buildHalFormsTemplatesSchema());

            Schema<?> variant = new Schema<>();
            variant.setAllOf(List.of(baseRef, extra));
            schemas.put(variantName, variant);
        }

        if (openApi.getPaths() == null)
            return;
        String refPrefix = "#/components/schemas/";
        openApi.getPaths().values().forEach(pathItem -> pathItem.readOperations().forEach(op -> {
            if (op.getResponses() == null)
                return;
            op.getResponses().values().forEach(response -> {
                if (response.getContent() == null)
                    return;
                var halForms = response.getContent().get(HAL_FORMS_MEDIA_TYPE);
                if (halForms == null || halForms.getSchema() == null)
                    return;
                String ref = halForms.getSchema().get$ref();
                if (ref != null && ref.startsWith(refPrefix)) {
                    String name = ref.substring(refPrefix.length());
                    if (HALFORMS_SCHEMAS.contains(name)) {
                        halForms.getSchema().set$ref(ref + "HalForms");
                    }
                }
            });
        }));
    }

    private Schema<?> buildLinksSchema(Map<String, String> rels) {
        ObjectSchema links = new ObjectSchema();
        links.setDescription("HAL link relations");
        for (var entry : rels.entrySet()) {
            links.addProperty(entry.getKey(), buildLinkObjectSchema(entry.getValue()));
        }
        return links;
    }

    private Schema<?> buildLinkObjectSchema(String description) {
        ObjectSchema link = new ObjectSchema();
        link.setDescription(description);
        link.addProperty("href", new StringSchema().format("uri"));
        link.setRequired(List.of("href"));
        return link;
    }

    private Schema<?> buildPageMetadataSchema() {
        ObjectSchema page = new ObjectSchema();
        page.setDescription("Pagination metadata");
        page.addProperty("size", new IntegerSchema().format("int64").description("Page size"));
        page.addProperty("totalElements",
                new IntegerSchema().format("int64").description("Total elements across all pages"));
        page.addProperty("totalPages",
                new IntegerSchema().format("int64").description("Total number of pages"));
        page.addProperty("number",
                new IntegerSchema().format("int64").description("Current page number (0-based)"));
        return page;
    }

    @SuppressWarnings("rawtypes")
    private Schema<?> buildEmbeddedSchema(String relation, String itemSchemaName) {
        ArraySchema array = new ArraySchema();
        Schema itemRef = new Schema<>();
        itemRef.set$ref("#/components/schemas/" + itemSchemaName);
        array.setItems(itemRef);

        ObjectSchema embedded = new ObjectSchema();
        embedded.setDescription("Embedded HAL resources");
        embedded.addProperty(relation, array);
        return embedded;
    }

    /**
     * Builds the HAL-FORMS {@code _templates} schema — an object whose keys
     * are template names ({@code "default"} is the primary affordance) and
     * whose values describe available state transitions.
     */
    private Schema<?> buildHalFormsTemplatesSchema() {
        ObjectSchema property = new ObjectSchema();
        property.setDescription("Affordance property descriptor");
        property.addProperty("name", new StringSchema().description("Property name"));
        property.addProperty("type", new StringSchema()
                .description("HTML input type hint (text, url, number, etc.)"));
        property.addProperty("required", new BooleanSchema()
                .description("Whether the property is required"));
        property.addProperty("readOnly", new BooleanSchema()
                .description("Whether the property is read-only"));
        property.addProperty("prompt", new StringSchema()
                .description("Human-readable label"));

        ArraySchema properties = new ArraySchema();
        properties.setItems(property);
        properties.setDescription("Input properties for this affordance");

        ObjectSchema template = new ObjectSchema();
        template.setDescription("Affordance template describing an available state transition");
        template.addProperty("method", new StringSchema()
                .description("HTTP method (POST, PUT, PATCH, DELETE)"));
        template.addProperty("target", new StringSchema().format("uri")
                .description("Target URI (defaults to self link href when absent)"));
        template.addProperty("contentType", new StringSchema()
                .description("Request body media type"));
        template.addProperty("properties", properties);

        ObjectSchema templates = new ObjectSchema();
        templates.setDescription(
                "HAL-FORMS affordance templates. "
                        + "Each key is a template name (\"default\" is the primary affordance).");
        templates.setAdditionalProperties(template);
        return templates;
    }

    // ------------------------------------------------------------------
    // Utility
    // ------------------------------------------------------------------

    @SuppressWarnings("rawtypes")
    private Map<String, Schema> schemas(OpenAPI openApi) {
        return openApi.getComponents() != null ? openApi.getComponents().getSchemas() : null;
    }

    /** Builds an insertion-ordered map from alternating key/value pairs. */
    private static Map<String, String> linkedMap(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
