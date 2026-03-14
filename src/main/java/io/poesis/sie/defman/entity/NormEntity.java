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
 * Norm — measurable constraint predicate. Extends AscriptionEntity.
 *
 * <p>
 * Carries the standard 6-trigger set on the {@code norm} table
 * (see {@link AscriptionEntity} for details).
 */
@SuppressWarnings("null") // JPA lifecycle: fields are always populated when accessed
@Entity
@Table(name = "norm")
public class NormEntity extends AscriptionEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "structure_id", nullable = false, updatable = false)
    private StructureEntity structure;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "qualifier_id", nullable = false, updatable = false)
    private ArchetypeEntity qualifier;

    protected NormEntity() {
    }

    public NormEntity(
            DefinitionEntity definition,
            ArchetypeEntity archetype,
            JsonNode statement,
            StructureEntity structure,
            ArchetypeEntity qualifier) {
        super(definition, archetype, statement);
        this.structure = Objects.requireNonNull(structure, "structure");
        this.qualifier = Objects.requireNonNull(qualifier, "qualifier");
    }

    @NonNull
    public StructureEntity getStructure() {
        return structure;
    }

    @NonNull
    public ArchetypeEntity getQualifier() {
        return qualifier;
    }
}
