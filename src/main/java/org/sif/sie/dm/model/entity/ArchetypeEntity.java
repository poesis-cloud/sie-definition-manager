package org.sif.sie.dm.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "archetype")
public class ArchetypeEntity extends AbstractAscription {

    @Column(name = "schema_uri")
    private String schemaUri;

    public String getSchemaUri() {
        return schemaUri;
    }

    public void setSchemaUri(String schemaUri) {
        this.schemaUri = schemaUri;
    }
}
