package com.sif.sie.definitionmanager.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import com.sif.sie.definitionmanager.type.AscriptionStatusType;
import com.sif.sie.definitionmanager.util.UuidV7GeneratorUtil;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ascription_id", nullable = false, updatable = false)
    private AscriptionEntity ascription;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "pre_status", updatable = false)
    private AscriptionStatusType preStatus;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "post_status", nullable = false, updatable = false)
    private AscriptionStatusType postStatus;

    @Column(name = "\"timestamp\"", nullable = false, updatable = false, insertable = false)
    private Instant timestamp;

    /** JPA requires a no-arg constructor. */
    protected AscriptionStatusTransitionEntity() {
    }

    public AscriptionStatusTransitionEntity(
            AscriptionEntity ascription,
            @Nullable AscriptionStatusType preStatus,
            AscriptionStatusType postStatus) {
        this.ascription = Objects.requireNonNull(ascription, "ascription");
        this.preStatus = preStatus;
        this.postStatus = Objects.requireNonNull(postStatus, "postStatus");
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
    public AscriptionEntity getAscription() {
        return ascription;
    }

    @Nullable
    public AscriptionStatusType getPreStatus() {
        return preStatus;
    }

    @NonNull
    public AscriptionStatusType getPostStatus() {
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
