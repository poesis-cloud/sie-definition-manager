package com.sif.sie.definitionmanager.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "effector")
public class EffectorEntity extends AbstractAscription {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mechanism_id", nullable = false, updatable = false)
    private MechanismEntity mechanism;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "port_archetype_id", nullable = false, updatable = false)
    private ArchetypeEntity portArchetype;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interface_id", updatable = false)
    private InterfaceEntity exposedBy;

    protected EffectorEntity() {}

    public EffectorEntity(
            DefinitionEntity definition,
            ArchetypeEntity archetype,
            JsonNode statement,
            MechanismEntity mechanism,
            ArchetypeEntity portArchetype,
            InterfaceEntity exposedBy) {
        super(definition, archetype, statement);
        this.mechanism = mechanism;
        this.portArchetype = portArchetype;
        this.exposedBy = exposedBy;
    }

    public MechanismEntity getMechanism() {
        return mechanism;
    }

    public ArchetypeEntity getPortArchetype() {
        return portArchetype;
    }

    public InterfaceEntity getExposedBy() {
        return exposedBy;
    }
}
