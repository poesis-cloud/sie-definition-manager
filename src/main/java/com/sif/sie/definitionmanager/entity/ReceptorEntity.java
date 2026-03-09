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
@Table(name = "receptor")
public class ReceptorEntity extends AbstractAscription {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mechanism_id", nullable = false, updatable = false)
    private MechanismEntity mechanism;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "port_archetype_id", nullable = false, updatable = false)
    private ArchetypeEntity portArchetype;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interface_id", updatable = false)
    private InterfaceEntity exposedBy;

    protected ReceptorEntity() {
    }

    public ReceptorEntity(
            DefinitionEntity definition,
            ArchetypeEntity archetype,
            JsonNode statement,
            MechanismEntity mechanism,
            ArchetypeEntity portArchetype,
            @Nullable InterfaceEntity exposedBy) {
        super(definition, archetype, statement);
        this.mechanism = Objects.requireNonNull(mechanism, "mechanism");
        this.portArchetype = Objects.requireNonNull(portArchetype, "portArchetype");
        this.exposedBy = exposedBy;
    }

    @NonNull
    public MechanismEntity getMechanism() {
        return mechanism;
    }

    @NonNull
    public ArchetypeEntity getPortArchetype() {
        return portArchetype;
    }

    @Nullable
    public InterfaceEntity getExposedBy() {
        return exposedBy;
    }
}
