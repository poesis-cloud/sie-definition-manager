package com.sif.sie.definitionmanager.entity;

import java.util.Objects;

import org.springframework.lang.NonNull;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@SuppressWarnings("null") // JPA lifecycle: fields are always populated when accessed
@Entity
@Table(name = "norm")
public class NormEntity extends AscriptionEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "structure_id", nullable = false, updatable = false)
    private StructureEntity structure;

    protected NormEntity() {
    }

    public NormEntity(
            DefinitionEntity definition,
            ArchetypeEntity archetype,
            JsonNode statement,
            StructureEntity structure) {
        super(definition, archetype, statement);
        this.structure = Objects.requireNonNull(structure, "structure");
    }

    @NonNull
    public StructureEntity getStructure() {
        return structure;
    }
}
