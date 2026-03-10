package com.sif.sie.definitionmanager.entity;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "structure")
public class StructureEntity extends AscriptionEntity {

    protected StructureEntity() {
    }

    public StructureEntity(
            DefinitionEntity definition, ArchetypeEntity archetype, JsonNode statement) {
        super(definition, archetype, statement);
    }
}
