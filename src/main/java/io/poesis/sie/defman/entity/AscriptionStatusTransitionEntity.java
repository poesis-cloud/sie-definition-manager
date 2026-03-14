package io.poesis.sie.defman.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import io.poesis.sie.defman.type.AscriptionStatusType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Immutable audit record of a lifecycle state change on an Ascription. Maps to
 * the shared {@code ascription_status_transition} table.
 *
 * <p>
 * All fields are immutable after creation — assigned via constructor, no
 * setters exposed. {@code id} and {@code timestamp} are DB-generated via
 * column defaults ({@code uuid_v7()} and {@code clock_timestamp()}).
 *
 * <p>
 * DB triggers on this table:
 *
 * <ul>
 * <li>{@code tgf_assert_transition_ascription_exists} — BEFORE INSERT:
 * validates that {@code ascription_id} references an existing ascription
 * row across all 9 class tables
 * <li>{@code tgf_sync_ascription_status} — AFTER INSERT: cascades
 * {@code post_status} to the referenced ascription's {@code status}
 * <li>{@code tgf_assign_ascription_version} — AFTER INSERT: increments the
 * referenced ascription's {@code version} when
 * {@code post_status = 'APPROVED'}
 * <li>{@code tgf_reject_transition_mutation} — BEFORE UPDATE/DELETE: blocks
 * any mutation (rows are append-only)
 * </ul>
 */
@SuppressWarnings("null") // JPA lifecycle: fields are always populated when accessed
@Entity
@Immutable
@Table(name = "ascription_status_transition")
public class AscriptionStatusTransitionEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
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
