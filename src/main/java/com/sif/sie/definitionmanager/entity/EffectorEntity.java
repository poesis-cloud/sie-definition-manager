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
@Table(name = "effector")
public class EffectorEntity extends AscriptionEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mechanism_id", nullable = false, updatable = false)
    private MechanismEntity mechanism;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "output_archetype_id", nullable = false, updatable = false)
    private ArchetypeEntity outputArchetype;

    protected EffectorEntity() {
    }

    public EffectorEntity(
            DefinitionEntity definition,
            ArchetypeEntity archetype,
            JsonNode compilation,
            MechanismEntity mechanism,
            ArchetypeEntity outputArchetype) {
        super(definition, archetype, compilation);
        this.mechanism = Objects.requireNonNull(mechanism, "mechanism");
        this.outputArchetype = Objects.requireNonNull(outputArchetype, "outputArchetype");
    }

    @NonNull
    public MechanismEntity getMechanism() {
        return mechanism;
    }

    @NonNull
    public ArchetypeEntity getOutputArchetype() {
        return outputArchetype;
    }
}
