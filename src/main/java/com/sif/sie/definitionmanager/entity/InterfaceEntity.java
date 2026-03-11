package com.sif.sie.definitionmanager.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@SuppressWarnings("null") // JPA lifecycle: fields are always populated when accessed
@Entity
@Table(name = "interface")
public class InterfaceEntity extends AscriptionEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "structure_id", nullable = false, updatable = false)
    private StructureEntity structure;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "interface_effector", joinColumns = @JoinColumn(name = "interface_id"), inverseJoinColumns = @JoinColumn(name = "effector_id"))
    private List<EffectorEntity> effectors = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "interface_receptor", joinColumns = @JoinColumn(name = "interface_id"), inverseJoinColumns = @JoinColumn(name = "receptor_id"))
    private List<ReceptorEntity> receptors = new ArrayList<>();

    protected InterfaceEntity() {
    }

    public InterfaceEntity(
            DefinitionEntity definition,
            ArchetypeEntity archetype,
            JsonNode compilation,
            StructureEntity structure,
            @Nullable List<EffectorEntity> effectors,
            @Nullable List<ReceptorEntity> receptors) {
        super(definition, archetype, compilation);
        this.structure = Objects.requireNonNull(structure, "structure");
        this.effectors = effectors != null ? new ArrayList<>(effectors) : new ArrayList<>();
        this.receptors = receptors != null ? new ArrayList<>(receptors) : new ArrayList<>();
    }

    @NonNull
    public StructureEntity getStructure() {
        return structure;
    }

    @NonNull
    public List<EffectorEntity> getEffectors() {
        return Collections.unmodifiableList(effectors);
    }

    @NonNull
    public List<ReceptorEntity> getReceptors() {
        return Collections.unmodifiableList(receptors);
    }
}
