package com.sif.sie.definitionmanager.entity;

import java.util.Objects;

import org.springframework.lang.NonNull;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@SuppressWarnings("null") // JPA lifecycle: fields are always populated when accessed
@Entity
@Table(name = "archetype")
public class ArchetypeEntity extends AbstractAscription {

    @Column(name = "schema_uri", nullable = false, updatable = false)
    private String schemaUri;

    protected ArchetypeEntity() {
    }

    public ArchetypeEntity(
            DefinitionEntity definition, ArchetypeEntity archetype, JsonNode statement,
            String schemaUri) {
        super(definition, archetype, statement);
        this.schemaUri = Objects.requireNonNull(schemaUri, "schemaUri");
    }

    @NonNull
    public String getSchemaUri() {
        return schemaUri;
    }
}
