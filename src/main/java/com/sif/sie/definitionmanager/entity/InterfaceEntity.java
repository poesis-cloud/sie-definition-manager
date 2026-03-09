package com.sif.sie.definitionmanager.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "interface")
public class InterfaceEntity extends AbstractAscription {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "structure_id", nullable = false, updatable = false)
    private StructureEntity structure;

    public StructureEntity getStructure() {
        return structure;
    }

    public void setStructure(StructureEntity structure) {
        this.structure = structure;
    }
}
