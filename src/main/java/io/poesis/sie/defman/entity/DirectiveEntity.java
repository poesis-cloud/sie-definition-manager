package io.poesis.sie.defman.entity;

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
@Table(name = "directive")
public class DirectiveEntity extends AscriptionEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "structure_id", nullable = false, updatable = false)
    private StructureEntity structure;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "qualifier_id", nullable = false, updatable = false)
    private ArchetypeEntity qualifier;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purpose_id", nullable = false, updatable = false)
    private StructureEntity purpose;

    protected DirectiveEntity() {
    }

    public DirectiveEntity(
            DefinitionEntity definition,
            ArchetypeEntity archetype,
            JsonNode statement,
            StructureEntity structure,
            ArchetypeEntity qualifier,
            StructureEntity purpose) {
        super(definition, archetype, statement);
        this.structure = Objects.requireNonNull(structure, "structure");
        this.qualifier = Objects.requireNonNull(qualifier, "qualifier");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
    }

    @NonNull
    public StructureEntity getStructure() {
        return structure;
    }

    @NonNull
    public ArchetypeEntity getQualifier() {
        return qualifier;
    }

    @NonNull
    public StructureEntity getPurpose() {
        return purpose;
    }
}
