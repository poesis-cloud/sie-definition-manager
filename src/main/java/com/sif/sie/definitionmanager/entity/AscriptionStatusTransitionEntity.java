package com.sif.sie.definitionmanager.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import com.sif.sie.definitionmanager.enums.AscriptionStatus;
import com.sif.sie.definitionmanager.enums.DefinitionSubjectType;
import com.sif.sie.definitionmanager.util.UuidV7Generator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Immutable audit record of a lifecycle state change on an Ascription. Maps to
 * the shared {@code ascription_status_transition} table.
 *
 * <p>
 * All fields are immutable after creation — assigned via constructor, no
 * setters exposed.
 */
@SuppressWarnings("null") // JPA lifecycle: fields are always populated when accessed
@Entity
@Immutable
@Table(name = "ascription_status_transition")
public class AscriptionStatusTransitionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "subject_type", nullable = false, updatable = false)
    private DefinitionSubjectType subjectType;

    @Column(name = "ascription_id", nullable = false, updatable = false)
    private UUID ascriptionId;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "pre_status", updatable = false)
    private AscriptionStatus preStatus;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "post_status", nullable = false, updatable = false)
    private AscriptionStatus postStatus;

    @Column(name = "\"timestamp\"", insertable = false, updatable = false)
    private Instant timestamp;

    /** JPA requires a no-arg constructor. */
    protected AscriptionStatusTransitionEntity() {
    }

    public AscriptionStatusTransitionEntity(
            DefinitionSubjectType subjectType,
            UUID ascriptionId,
            @Nullable AscriptionStatus preStatus,
            AscriptionStatus postStatus) {
        this.subjectType = Objects.requireNonNull(subjectType, "subjectType");
        this.ascriptionId = Objects.requireNonNull(ascriptionId, "ascriptionId");
        this.preStatus = preStatus;
        this.postStatus = Objects.requireNonNull(postStatus, "postStatus");
    }

    @PrePersist
    void ensureId() {
        if (id == null) {
            id = UuidV7Generator.generate();
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
    public UUID getAscriptionId() {
        return ascriptionId;
    }

    @Nullable
    public AscriptionStatus getPreStatus() {
        return preStatus;
    }

    @NonNull
    public AscriptionStatus getPostStatus() {
        return postStatus;
    }

    @NonNull
    public Instant getTimestamp() {
        return timestamp;
    }

    // ---- equals / hashCode ----

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        AscriptionStatusTransitionEntity that = (AscriptionStatusTransitionEntity) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
