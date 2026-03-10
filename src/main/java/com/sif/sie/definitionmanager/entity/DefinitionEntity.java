package com.sif.sie.definitionmanager.entity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.lang.NonNull;

import com.sif.sie.definitionmanager.type.DefinitionSubjectType;
import com.sif.sie.definitionmanager.util.UuidV7GeneratorUtil;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Stable identity of a governed subject in GSM.
 *
 * <p>
 * A Definition answers <em>what thing is being governed</em> — the referent
 * that persists across
 * all Ascriptions (normative snapshots) that describe it over time. Definition
 * does NOT extend
 * Ascription; it is a separate entity providing the identity anchor.
 *
 * <p>
 * Maps to the {@code definition} table. Both fields ({@code id},
 * {@code subjectType}) are
 * immutable after creation — assigned via constructor, no setters exposed.
 */
@SuppressWarnings("null") // JPA lifecycle: fields are always populated when accessed
@Entity
@Table(name = "definition")
public class DefinitionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "subject_type", nullable = false, updatable = false)
    private DefinitionSubjectType subjectType;

    @OneToMany(mappedBy = "definition", fetch = FetchType.LAZY)
    @OrderBy("timestamp DESC")
    private List<AscriptionEntity> ascriptions;

    /** JPA requires a no-arg constructor. */
    protected DefinitionEntity() {
    }

    public DefinitionEntity(DefinitionSubjectType subjectType) {
        this.subjectType = Objects.requireNonNull(subjectType, "subjectType");
    }

    @PrePersist
    void ensureId() {
        if (id == null) {
            id = UuidV7GeneratorUtil.generate();
        }
    }

    // ---- accessors (read-only) ----

    @NonNull
    public UUID getId() {
        return id;
    }

    @NonNull
    public DefinitionSubjectType getSubjectType() {
        return subjectType;
    }

    @NonNull
    public List<AscriptionEntity> getAscriptions() {
        return ascriptions;
    }

    // ---- equals / hashCode ----

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        DefinitionEntity that = (DefinitionEntity) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
