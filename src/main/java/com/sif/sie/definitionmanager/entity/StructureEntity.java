package com.sif.sie.definitionmanager.entity;

import java.util.Collections;
import java.util.List;

import org.springframework.lang.NonNull;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@SuppressWarnings("null") // JPA lifecycle: fields are always populated when accessed
@Entity
@Table(name = "structure")
public class StructureEntity extends AscriptionEntity {

    @OneToMany(mappedBy = "structure", fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<MechanismEntity> mechanisms;

    @OneToMany(mappedBy = "structure", fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<InterfaceEntity> interfaces;

    protected StructureEntity() {
    }

    public StructureEntity(
            DefinitionEntity definition, ArchetypeEntity archetype, JsonNode compilation) {
        super(definition, archetype, compilation);
    }

    @NonNull
    public List<MechanismEntity> getMechanisms() {
        return Collections.unmodifiableList(mechanisms);
    }

    @NonNull
    public List<InterfaceEntity> getInterfaces() {
        return Collections.unmodifiableList(interfaces);
    }
}
