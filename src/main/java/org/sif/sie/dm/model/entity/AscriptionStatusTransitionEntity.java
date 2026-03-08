package org.sif.sie.dm.model.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.sif.sie.dm.model.enums.AscriptionStatus;
import org.sif.sie.dm.model.enums.GsmType;
import org.sif.sie.dm.util.UuidV7;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Immutable audit record of a lifecycle state change on an Ascription.
 * Maps to the shared {@code ascription_status_transition} table.
 */
@Entity
@Immutable
@Table(name = "ascription_status_transition")
public class AscriptionStatusTransitionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "gsm_type", nullable = false, updatable = false)
    private GsmType gsmType;

    @Column(name = "revision_id", nullable = false, updatable = false)
    private UUID revisionId;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "pre_status", updatable = false)
    private AscriptionStatus preStatus;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "post_status", nullable = false, updatable = false)
    private AscriptionStatus postStatus;

    @Column(name = "\"timestamp\"", insertable = false, updatable = false)
    private Instant timestamp;

    @PrePersist
    void ensureId() {
        if (id == null) {
            id = UuidV7.generate();
        }
    }

    // ---- accessors ----

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public GsmType getGsmType() {
        return gsmType;
    }

    public void setGsmType(GsmType gsmType) {
        this.gsmType = gsmType;
    }

    public UUID getRevisionId() {
        return revisionId;
    }

    public void setRevisionId(UUID revisionId) {
        this.revisionId = revisionId;
    }

    public AscriptionStatus getPreStatus() {
        return preStatus;
    }

    public void setPreStatus(AscriptionStatus preStatus) {
        this.preStatus = preStatus;
    }

    public AscriptionStatus getPostStatus() {
        return postStatus;
    }

    public void setPostStatus(AscriptionStatus postStatus) {
        this.postStatus = postStatus;
    }

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
