package com.sif.sie.definitionmanager.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "norm")
public class NormEntity extends AbstractAscription {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "structure_id", nullable = false, updatable = false)
    private StructureEntity structure;

    protected NormEntity() {}

    public NormEntity(
            DefinitionEntity definition,
            ArchetypeEntity archetype,
            JsonNode statement,
            StructureEntity structure) {
        super(definition, archetype, statement);
        this.structure = structure;
    }

    public StructureEntity getStructure() {
        return structure;
    }
}
