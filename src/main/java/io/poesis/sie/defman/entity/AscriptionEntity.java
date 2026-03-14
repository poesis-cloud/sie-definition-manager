package io.poesis.sie.defman.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.lang.NonNull;

import com.fasterxml.jackson.databind.JsonNode;

import io.poesis.sie.defman.type.AscriptionStatusType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

/**
 * Abstract base for all 9 GSM class tables.
 *
 * <p>
 * Maps the GSM Ascription concept: a governed normative snapshot of a
 * Definition. Each row is an immutable snapshot whose identity ({@code id})
 * is distinct from the stable subject identity held by the referenced
 * {@link DefinitionEntity}.
 *
 * <p>
 * Immutable-after-creation fields ({@code definition}, {@code archetype},
 * {@code statement}) are set via constructor. All other fields
 * ({@code timestamp}, {@code status}, {@code version}) are DB
 * trigger-managed — no setters exposed.
 *
 * <p>
 * DB triggers (6 per concrete table, TABLE_PER_CLASS):
 *
 * <ul>
 * <li>{@code tgf_assign_id} — BEFORE INSERT: generates {@code id} via
 * {@code uuid_v7()} if null
 * <li>{@code tgf_assign_timestamp} — BEFORE INSERT: sets {@code timestamp}
 * to {@code clock_timestamp()}
 * <li>{@code tgf_reject_id_update} — BEFORE UPDATE OF id: blocks PK mutation
 * <li>{@code tgf_reject_status_update} — BEFORE UPDATE OF status: blocks
 * direct status writes (only trigger-cascaded updates from transition
 * insert are allowed)
 * <li>{@code tgf_restrict_ascription_delete_when_transitions_exist} — BEFORE
 * DELETE: prevents deletion when transition rows exist
 * <li>{@code tgf_assert_ascription_status_matches_history} — AFTER
 * INSERT/UPDATE (deferred): verifies {@code status} matches the latest
 * transition's {@code post_status}
 * </ul>
 *
 * <p>
 * Additionally, inserting into {@code ascription_status_transition} fires:
 *
 * <ul>
 * <li>{@code tgf_sync_ascription_status} — cascades {@code post_status}
 * to this row's {@code status}
 * <li>{@code tgf_assign_ascription_version} — increments {@code version}
 * when {@code post_status = 'APPROVED'}
 * </ul>
 */
@SuppressWarnings("null") // JPA lifecycle: fields are always populated when accessed
@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
public abstract class AscriptionEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_id", nullable = false, updatable = false)
    private DefinitionEntity definition;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "archetype_id", nullable = false, updatable = false)
    private ArchetypeEntity archetype;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "statement", nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode statement;

    @Column(name = "\"timestamp\"", nullable = false, updatable = false, insertable = false)
    private Instant timestamp;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, updatable = false, insertable = false)
    private AscriptionStatusType status;

    @Column(name = "version", nullable = false, updatable = false, insertable = false)
    private Integer version;

    /** JPA requires a no-arg constructor. */
    protected AscriptionEntity() {
    }

    protected AscriptionEntity(
            DefinitionEntity definition, ArchetypeEntity archetype, JsonNode statement) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.archetype = Objects.requireNonNull(archetype, "archetype");
        this.statement = Objects.requireNonNull(statement, "statement");
    }

    // ---- accessors ----

    @NonNull
    public UUID getId() {
        return id;
    }

    @NonNull
    public DefinitionEntity getDefinition() {
        return definition;
    }

    @NonNull
    public ArchetypeEntity getArchetype() {
        return archetype;
    }

    @NonNull
    public JsonNode getStatement() {
        return statement;
    }

    @NonNull
    public Instant getTimestamp() {
        return timestamp;
    }

    @NonNull
    public AscriptionStatusType getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }

    // ---- equals / hashCode (Vlad Mihalcea pattern) ----

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        AscriptionEntity that = (AscriptionEntity) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
