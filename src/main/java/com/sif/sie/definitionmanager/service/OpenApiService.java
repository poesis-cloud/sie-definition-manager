package com.sif.sie.definitionmanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;
import com.sif.sie.definitionmanager.enums.AscriptionStatus;
import com.sif.sie.definitionmanager.repository.ArchetypeRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OpenApiService {

    private final ArchetypeRepository archetypeRepository;
    private final ObjectMapper mapper;

    public OpenApiService(ArchetypeRepository archetypeRepository, ObjectMapper mapper) {
        this.archetypeRepository = archetypeRepository;
        this.mapper = mapper;
    }

    public JsonNode buildOpenApi() {
        ObjectNode root = mapper.createObjectNode();
        root.put("openapi", "3.1.0");

        ObjectNode info = root.putObject("info");
        info.put("title", "SIE Definition Manager API");
        info.put("version", "v1");
        info.put(
                "description",
                "Dynamic API specification. Definition schemas are generated from "
                        + "active GSM archetypes stored in the database.");

        root.putArray("servers").addObject().put("url", "/api/v1");

        buildPaths(root.putObject("paths"));

        ObjectNode components = root.putObject("components");
        ObjectNode schemas = components.putObject("schemas");
        buildFixedSchemas(schemas);
        buildArchetypeSchemas(schemas);

        return root;
    }

    private void buildPaths(ObjectNode paths) {
        buildAscriptionsPath(paths.putObject("/ascriptions"));
        buildAscriptionByIdPath(paths.putObject("/ascriptions/{revisionId}"));
        buildRevisionsPath(paths.putObject("/ascriptions/revisions"));
        buildTransitionsPath(paths.putObject("/ascriptions/{revisionId}/transitions"));
    }

    private void buildAscriptionsPath(ObjectNode path) {
        ObjectNode post = path.putObject("post");
        post.put("summary", "Create an ascription");
        post.put("operationId", "createAscription");
        ObjectNode postBody = post.putObject("requestBody");
        postBody.put("required", true);
        postBody
                .putObject("content")
                .putObject("application/json")
                .putObject("schema")
                .put("$ref", "#/components/schemas/AscriptionRequest");
        ObjectNode postResponses = post.putObject("responses");
        jsonResponse(postResponses, "201", "Created", "#/components/schemas/AscriptionResponse");
        problemResponse(postResponses, "400", "Validation error");

        ObjectNode get = path.putObject("get");
        get.put("summary", "List ascriptions (paginated)");
        get.put("operationId", "listAscriptions");
        ArrayNode getParams = get.putArray("parameters");
        queryParam(
                getParams,
                "type",
                "string",
                true,
                "GSM type (archetype, structure, mechanism, interface, effector, receptor, interaction, directive, norm)");
        queryParam(getParams, "status", "string", false, "Filter by status");
        queryParam(getParams, "page", "integer", false, "Page number (0-based)");
        queryParam(getParams, "size", "integer", false, "Page size (default 20)");
        ObjectNode getResponses = get.putObject("responses");
        jsonResponse(
                getResponses, "200", "Paginated list", "#/components/schemas/PagedAscriptionResponse");
    }

    private void buildAscriptionByIdPath(ObjectNode path) {
        ObjectNode get = path.putObject("get");
        get.put("summary", "Get ascription by revision ID");
        get.put("operationId", "getAscriptionByRevisionId");
        ArrayNode params = get.putArray("parameters");
        pathParam(params, "revisionId", "string", "uuid", "Ascription revision ID");
        ObjectNode responses = get.putObject("responses");
        jsonResponse(responses, "200", "Found", "#/components/schemas/AscriptionResponse");
        problemResponse(responses, "400", "Not found");
    }

    private void buildRevisionsPath(ObjectNode path) {
        ObjectNode get = path.putObject("get");
        get.put("summary", "Get revision history for a lineage");
        get.put("operationId", "getRevisionHistory");
        ArrayNode params = get.putArray("parameters");
        queryParam(params, "id", "string", true, "Lineage ID (shared across revisions)");
        queryParam(params, "type", "string", true, "GSM type");
        ObjectNode responses = get.putObject("responses");
        ObjectNode resp200 = responses.putObject("200");
        resp200.put("description", "Revision list");
        resp200
                .putObject("content")
                .putObject("application/hal+json")
                .putObject("schema")
                .put("type", "array")
                .putObject("items")
                .put("$ref", "#/components/schemas/AscriptionResponse");
    }

    private void buildTransitionsPath(ObjectNode path) {
        ObjectNode get = path.putObject("get");
        get.put("summary", "Get transition audit trail");
        get.put("operationId", "getTransitions");
        ArrayNode getParams = get.putArray("parameters");
        pathParam(getParams, "revisionId", "string", "uuid", "Ascription revision ID");
        ObjectNode getResponses = get.putObject("responses");
        ObjectNode resp200 = getResponses.putObject("200");
        resp200.put("description", "Transition history");
        resp200
                .putObject("content")
                .putObject("application/json")
                .putObject("schema")
                .put("type", "array")
                .putObject("items")
                .put("$ref", "#/components/schemas/TransitionResponse");

        ObjectNode post = path.putObject("post");
        post.put("summary", "Transition ascription to a new status");
        post.put("operationId", "transitionAscription");
        ArrayNode postParams = post.putArray("parameters");
        pathParam(postParams, "revisionId", "string", "uuid", "Ascription revision ID");
        ObjectNode postBody = post.putObject("requestBody");
        postBody.put("required", true);
        postBody
                .putObject("content")
                .putObject("application/json")
                .putObject("schema")
                .put("$ref", "#/components/schemas/TransitionRequest");
        ObjectNode postResponses = post.putObject("responses");
        jsonResponse(
                postResponses, "200", "Transition applied", "#/components/schemas/TransitionResponse");
        problemResponse(postResponses, "400", "Invalid transition");
    }

    private void buildFixedSchemas(ObjectNode schemas) {
        ObjectNode req = schemas.putObject("AscriptionRequest");
        req.put("type", "object");
        ObjectNode reqProps = req.putObject("properties");
        uuidProp(reqProps, "archetypeId", "Reference to the typing Archetype revision_id");
        reqProps.putObject("definition").put("$ref", "#/components/schemas/DefinitionPayload");
        uuidProp(reqProps, "id", "Optional: set for new revision of existing lineage");
        req.putArray("required").add("archetypeId").add("definition");

        ObjectNode resp = schemas.putObject("AscriptionResponse");
        resp.put("type", "object");
        ObjectNode respProps = resp.putObject("properties");
        respProps.putObject("gsmType").put("type", "string");
        uuidProp(respProps, "id", "Lineage identity (shared across revisions)");
        uuidProp(respProps, "revisionId", "Unique revision identity");
        respProps.putObject("revisionTimestamp").put("type", "string").put("format", "date-time");
        uuidProp(respProps, "archetypeId", "Typing archetype revision_id");
        respProps
                .putObject("definition")
                .put("type", "object")
                .put("description", "Definition payload (schema depends on archetype)");
        respProps
                .putObject("version")
                .put("type", "integer")
                .put("description", "Governance version (assigned at APPROVED)");
        respProps.putObject("status").put("type", "string");
        respProps
                .putObject("schemaUri")
                .put("type", "string")
                .put("description", "Schema URI (archetypes only)");

        ObjectNode tReq = schemas.putObject("TransitionRequest");
        tReq.put("type", "object");
        ObjectNode tReqProps = tReq.putObject("properties");
        ObjectNode targetStatus = tReqProps.putObject("targetStatus");
        targetStatus.put("type", "string");
        ArrayNode statusEnum = targetStatus.putArray("enum");
        for (AscriptionStatus status : AscriptionStatus.values()) {
            statusEnum.add(status.name());
        }
        tReq.putArray("required").add("targetStatus");

        ObjectNode tResp = schemas.putObject("TransitionResponse");
        tResp.put("type", "object");
        ObjectNode tRespProps = tResp.putObject("properties");
        uuidProp(tRespProps, "transitionId", "Transition record ID");
        uuidProp(tRespProps, "revisionId", "Owning ascription revision_id");
        tRespProps.putObject("preStatus").put("type", "string");
        tRespProps.putObject("postStatus").put("type", "string");
        tRespProps.putObject("timestamp").put("type", "string").put("format", "date-time");

        ObjectNode paged = schemas.putObject("PagedAscriptionResponse");
        paged.put("type", "object");
        ObjectNode pagedProps = paged.putObject("properties");
        ObjectNode embedded = pagedProps.putObject("_embedded");
        embedded.put("type", "object");
        embedded
                .putObject("properties")
                .putObject("ascriptionResponseList")
                .put("type", "array")
                .putObject("items")
                .put("$ref", "#/components/schemas/AscriptionResponse");
        ObjectNode pageMeta = pagedProps.putObject("page");
        pageMeta.put("type", "object");
        ObjectNode pageProps = pageMeta.putObject("properties");
        pageProps.putObject("size").put("type", "integer");
        pageProps.putObject("totalElements").put("type", "integer");
        pageProps.putObject("totalPages").put("type", "integer");
        pageProps.putObject("number").put("type", "integer");
    }

    private void buildArchetypeSchemas(ObjectNode schemas) {
        List<ArchetypeEntity> archetypes = archetypeRepository.findAllByStatus(AscriptionStatus.ACTIVE);
        ArrayNode oneOf = mapper.createArrayNode();

        for (ArchetypeEntity archetype : archetypes) {
            JsonNode definition = archetype.getDefinition();
            if (definition == null || !definition.has("schema")) {
                continue;
            }

            String schemaUri = archetype.getSchemaUri();
            if (schemaUri == null) {
                continue;
            }

            String name = deriveSchemaName(schemaUri);
            if (name == null) {
                continue;
            }

            ObjectNode archetypeSchema = definition.get("schema").deepCopy();
            archetypeSchema.remove("$schema");
            archetypeSchema.remove("$id");
            archetypeSchema.remove("$gsm:sealed");
            archetypeSchema.put("title", name + " definition");
            schemas.set(name + "Definition", archetypeSchema);

            oneOf.addObject().put("$ref", "#/components/schemas/" + name + "Definition");
        }

        ObjectNode payload = schemas.putObject("DefinitionPayload");
        payload.put(
                "description",
                "Definition payload. The applicable schema depends on the archetypeId "
                        + "used in the request. See per-archetype schemas below.");
        if (oneOf.isEmpty()) {
            payload.put("type", "object");
        } else {
            payload.set("oneOf", oneOf);
        }
    }

    private String deriveSchemaName(String schemaUri) {
        int lastColon = schemaUri.lastIndexOf(':');
        if (lastColon < 0) {
            return null;
        }
        String suffix = schemaUri.substring(lastColon + 1);
        int dot = suffix.indexOf('.');
        return dot > 0 ? suffix.substring(0, dot) : suffix;
    }

    private void queryParam(
            ArrayNode params, String name, String type, boolean required, String desc) {
        ObjectNode parameter = params.addObject();
        parameter.put("name", name);
        parameter.put("in", "query");
        parameter.put("required", required);
        parameter.put("description", desc);
        parameter.putObject("schema").put("type", type);
    }

    private void pathParam(ArrayNode params, String name, String type, String format, String desc) {
        ObjectNode parameter = params.addObject();
        parameter.put("name", name);
        parameter.put("in", "path");
        parameter.put("required", true);
        parameter.put("description", desc);
        parameter.putObject("schema").put("type", type).put("format", format);
    }

    private void jsonResponse(ObjectNode responses, String code, String desc, String schemaRef) {
        ObjectNode response = responses.putObject(code);
        response.put("description", desc);
        response
                .putObject("content")
                .putObject("application/hal+json")
                .putObject("schema")
                .put("$ref", schemaRef);
    }

    private void problemResponse(ObjectNode responses, String code, String desc) {
        ObjectNode response = responses.putObject(code);
        response.put("description", desc);
        response
                .putObject("content")
                .putObject("application/problem+json")
                .putObject("schema")
                .put("type", "object");
    }

    private void uuidProp(ObjectNode props, String name, String desc) {
        props.putObject(name).put("type", "string").put("format", "uuid").put("description", desc);
    }
}
