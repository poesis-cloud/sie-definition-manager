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
@Table(name = "receptor")
public class ReceptorEntity extends AscriptionEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mechanism_id", nullable = false, updatable = false)
    private MechanismEntity mechanism;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "input_archetype_id", nullable = false, updatable = false)
    private ArchetypeEntity inputArchetype;

    protected ReceptorEntity() {
    }

    public ReceptorEntity(
            DefinitionEntity definition,
            ArchetypeEntity archetype,
            JsonNode statement,
            MechanismEntity mechanism,
            ArchetypeEntity inputArchetype) {
        super(definition, archetype, statement);
        this.mechanism = Objects.requireNonNull(mechanism, "mechanism");
        this.inputArchetype = Objects.requireNonNull(inputArchetype, "inputArchetype");
    }

    @NonNull
    public MechanismEntity getMechanism() {
        return mechanism;
    }

    @NonNull
    public ArchetypeEntity getInputArchetype() {
        return inputArchetype;
    }
}
