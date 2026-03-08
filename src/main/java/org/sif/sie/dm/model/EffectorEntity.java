package org.sif.sie.dm.model;

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

    public MechanismEntity getMechanism() { return mechanism; }
    public void setMechanism(MechanismEntity mechanism) { this.mechanism = mechanism; }

    public ArchetypeEntity getPortArchetype() { return portArchetype; }
    public void setPortArchetype(ArchetypeEntity portArchetype) { this.portArchetype = portArchetype; }

    public InterfaceEntity getExposedBy() { return exposedBy; }
    public void setExposedBy(InterfaceEntity exposedBy) { this.exposedBy = exposedBy; }
}
