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
@Table(name = "interaction")
public class InteractionEntity extends AscriptionEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "effector_id", nullable = false, updatable = false)
    private EffectorEntity effector;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receptor_id", nullable = false, updatable = false)
    private ReceptorEntity receptor;

    protected InteractionEntity() {
    }

    public InteractionEntity(
            DefinitionEntity definition,
            ArchetypeEntity archetype,
            JsonNode statement,
            EffectorEntity effector,
            ReceptorEntity receptor) {
        super(definition, archetype, statement);
        this.effector = Objects.requireNonNull(effector, "effector");
        this.receptor = Objects.requireNonNull(receptor, "receptor");
    }

    @NonNull
    public EffectorEntity getEffector() {
        return effector;
    }

    @NonNull
    public ReceptorEntity getReceptor() {
        return receptor;
    }
}
