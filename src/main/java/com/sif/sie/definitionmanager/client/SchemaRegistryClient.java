package com.sif.sie.definitionmanager.client;

import java.net.URI;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Client for the Confluent Schema Registry.
 *
 * <p>
 * The definition-manager is the sole write proxy for governed schemas.
 * Consumers read directly from the registry at runtime.
 */
@Component
public class SchemaRegistryClient {

    private static final String SCHEMA_TYPE = "JSON";
    private final RestClient restClient;
    private final ObjectMapper mapper;

    public SchemaRegistryClient(
            @Value("${spring.kafka.properties.schema.registry.url}") String baseUrl,
            ObjectMapper mapper) {
        this.restClient = RestClient.builder()
                .baseUrl(Objects.requireNonNull(baseUrl, "schema registry baseUrl must not be null"))
                .build();
        this.mapper = mapper;
    }

    /**
     * Register a JSON Schema under the given subject.
     *
     * @param subject registry subject name (e.g., "gsm_archetype_definition_{id}")
     * @param schema  the JSON Schema content
     * @return the globally unique schema ID assigned by the registry
     */
    public int registerSchema(String subject, JsonNode schema) {
        ObjectNode body = mapper.createObjectNode();
        body.put("schemaType", SCHEMA_TYPE);
        body.put("schema", schema.toString());

        JsonNode response = Objects.requireNonNull(
                restClient
                        .post()
                        .uri("/subjects/{subject}/versions", subject)
                        .contentType(MediaType
                                .valueOf("application/vnd.schemaregistry.v1+json"))
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class),
                "Schema Registry returned an empty register response");

        return Objects.requireNonNull(response.get("id"), "Schema Registry response missing id")
                .asInt();
    }

    /**
     * Retrieve a schema by its global ID.
     *
     * @param schemaId global schema ID
     * @return the schema content
     */
    public JsonNode getSchema(int schemaId) {
        JsonNode response = Objects.requireNonNull(
                restClient.get().uri("/schemas/ids/{id}", schemaId).retrieve().body(JsonNode.class),
                "Schema Registry returned an empty schema response");

        try {
            return mapper.readTree(
                    Objects.requireNonNull(response.get("schema"),
                            "Schema Registry response missing schema")
                            .asText());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse schema from registry", e);
        }
    }

    /**
     * Build the canonical schema URI for a registered schema.
     *
     * @param schemaId the global schema ID
     * @return URI in the form used by Kafka serde consumers
     */
    public String buildSchemaUri(int schemaId) {
        URI baseUri = restClient.options().uri("/").retrieve().toBodilessEntity().getHeaders().getLocation();
        return Objects.requireNonNull(baseUri, "Schema Registry location header missing")
                + "schemas/ids/"
                + schemaId;
    }
}
