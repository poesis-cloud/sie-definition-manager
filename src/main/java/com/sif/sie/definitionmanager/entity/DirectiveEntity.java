package com.sif.sie.definitionmanager.entity;

import java.util.Objects;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "directive")
public class DirectiveEntity extends AbstractAscription {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "structure_id", nullable = false, updatable = false)
    private StructureEntity structure;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "qualifier_id", nullable = false, updatable = false)
    private ArchetypeEntity qualifier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purpose_id", updatable = false)
    private StructureEntity purpose;

    protected DirectiveEntity() {
    }

    public DirectiveEntity(
            DefinitionEntity definition,
            ArchetypeEntity archetype,
            JsonNode statement,
            StructureEntity structure,
            ArchetypeEntity qualifier,
            @Nullable StructureEntity purpose) {
        super(definition, archetype, statement);
        this.structure = Objects.requireNonNull(structure, "structure");
        this.qualifier = Objects.requireNonNull(qualifier, "qualifier");
        this.purpose = purpose;
    }

    @NonNull
    public StructureEntity getStructure() {
        return structure;
    }

    @NonNull
    public ArchetypeEntity getQualifier() {
        return qualifier;
    }

    @Nullable
    public StructureEntity getPurpose() {
        return purpose;
    }
}
