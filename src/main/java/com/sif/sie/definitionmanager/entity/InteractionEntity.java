package com.sif.sie.definitionmanager.entity;

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

    public EffectorEntity getEffector() {
        return effector;
    }

    public void setEffector(EffectorEntity effector) {
        this.effector = effector;
    }

    public ReceptorEntity getReceptor() {
        return receptor;
    }

    public void setReceptor(ReceptorEntity receptor) {
        this.receptor = receptor;
    }
}
