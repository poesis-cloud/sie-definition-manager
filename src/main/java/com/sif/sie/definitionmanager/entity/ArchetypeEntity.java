package com.sif.sie.definitionmanager.entity;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "archetype")
public class ArchetypeEntity extends AbstractAscription {

    @Column(name = "schema_uri")
    private String schemaUri;

    protected ArchetypeEntity() {
    }

    public ArchetypeEntity(
            DefinitionEntity definition, ArchetypeEntity archetype, JsonNode statement) {
        super(definition, archetype, statement);
    }

    public String getSchemaUri() {
        return schemaUri;
    }

    public void setSchemaUri(String schemaUri) {
        this.schemaUri = schemaUri;
    }
}
