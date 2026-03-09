package com.sif.sie.definitionmanager.util;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.sif.sie.definitionmanager.entity.ArchetypeEntity;

/**
 * Validates ascription {@code statement} payloads against their archetype's
 * JSON Schema ({@code statement.schema}).
 */
@Component
public class DefinitionValidator {

    private final JsonSchemaFactory schemaFactory;

    public DefinitionValidator() {
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    }

    /**
     * Validates the given statement against the archetype's embedded schema.
     *
     * @throws IllegalArgumentException if validation fails, with details
     */
    public void validate(JsonNode statement, ArchetypeEntity archetype) {
        JsonNode archetypeStatement = archetype.getStatement();
        JsonNode schemaNode = archetypeStatement.get("schema");
        if (schemaNode == null || schemaNode.isNull()) {
            // Archetype has no embedded schema — skip validation (bootstrap compat)
            return;
        }
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        JsonSchema schema = schemaFactory.getSchema(schemaNode, config);
        Set<ValidationMessage> errors = schema.validate(statement);
        if (!errors.isEmpty()) {
            String details = errors.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; "));
            throw new IllegalArgumentException(
                    "Definition validation failed against archetype schema ["
                            + archetype.getSchemaUri()
                            + "]: "
                            + details);
        }
    }
}
