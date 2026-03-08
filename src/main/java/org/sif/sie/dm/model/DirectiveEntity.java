package org.sif.sie.dm.model;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "directive")
public class DirectiveEntity extends AbstractAscription {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "structure_id", nullable = false, updatable = false)
    private StructureEntity structure;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "qualifier_id", nullable = false, updatable = false)
    private ArchetypeEntity qualifier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purpose_id", updatable = false)
    private StructureEntity purpose;

    public StructureEntity getStructure() { return structure; }
    public void setStructure(StructureEntity structure) { this.structure = structure; }

    public ArchetypeEntity getQualifier() { return qualifier; }
    public void setQualifier(ArchetypeEntity qualifier) { this.qualifier = qualifier; }

    public StructureEntity getPurpose() { return purpose; }
    public void setPurpose(StructureEntity purpose) { this.purpose = purpose; }
}
