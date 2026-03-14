package io.poesis.sie.defman.entity;

import java.util.Objects;

import org.springframework.lang.NonNull;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Mechanism — logical causal unit. Extends AscriptionEntity.
 *
 * <p>
 * Carries the standard 6-trigger set on the {@code mechanism} table
 * (see {@link AscriptionEntity} for details).
 */
@SuppressWarnings("null") // JPA lifecycle: fields are always populated when accessed
@Entity
@Table(name = "mechanism")
public class MechanismEntity extends AscriptionEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "structure_id", nullable = false, updatable = false)
    private StructureEntity structure;

    protected MechanismEntity() {
    }

    public MechanismEntity(
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
