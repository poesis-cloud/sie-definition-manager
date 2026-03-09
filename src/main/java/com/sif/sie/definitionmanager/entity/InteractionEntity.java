package com.sif.sie.definitionmanager.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "interaction")
public class InteractionEntity extends AbstractAscription {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "effector_id", nullable = false, updatable = false)
    private EffectorEntity effector;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receptor_id", nullable = false, updatable = false)
    private ReceptorEntity receptor;

    protected InteractionEntity() {}

    public InteractionEntity(
            DefinitionEntity definition,
            ArchetypeEntity archetype,
            JsonNode statement,
            EffectorEntity effector,
            ReceptorEntity receptor) {
        super(definition, archetype, statement);
        this.effector = effector;
        this.receptor = receptor;
    }

    public EffectorEntity getEffector() {
        return effector;
    }

    public ReceptorEntity getReceptor() {
        return receptor;
    }
}
