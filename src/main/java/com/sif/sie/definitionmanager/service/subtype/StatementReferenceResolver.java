package com.sif.sie.definitionmanager.service.subtype;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

@Component
public class StatementReferenceResolver {

    public <T> T requireRef(
            JpaRepository<T, UUID> repo,
            JsonNode statement,
            String jsonField,
            String displayName) {
        JsonNode node = statement.get(jsonField);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "Required field '" + jsonField + "' missing in statement payload");
        }
        UUID refId;
        try {
            refId = UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid UUID for field '" + jsonField + "': " + node.asText());
        }
        return repo.findById(refId)
                .orElseThrow(() -> new IllegalArgumentException(displayName + " not found: " + refId));
    }

    public <T> List<T> resolveRefList(
            JpaRepository<T, UUID> repo,
            JsonNode statement,
            String jsonField) {
        JsonNode node = statement.get(jsonField);
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        List<T> result = new java.util.ArrayList<>(node.size());
        for (JsonNode element : node) {
            UUID refId;
            try {
                refId = UUID.fromString(element.asText());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid UUID in '" + jsonField + "': " + element.asText());
            }
            result.add(repo.findById(refId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            jsonField + " element not found: " + refId)));
        }
        return result;
    }
}
