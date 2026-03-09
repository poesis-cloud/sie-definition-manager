package com.sif.sie.definitionmanager.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.sif.sie.definitionmanager.enums.AscriptionStatus;
import com.sif.sie.definitionmanager.enums.DefinitionSubjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Read-only projection of any Ascription, backed by the {@code ascription_all} union view.
 *
 * <p>Used by {@link DefinitionEntity#getAscriptions()} to provide a lazy-loaded, ordered collection
 * of all Ascriptions for a given Definition — regardless of their concrete GSM class table.
 *
 * <p>This entity is {@link Immutable}: Hibernate will never attempt INSERT, UPDATE, or DELETE on it.
 */
@Entity
@Immutable
@Table(name = "ascription_all")
public class AscriptionProjection {

    @Id
    @Column(name = "id")
    private UUID id;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "subject_type")
    private DefinitionSubjectType subjectType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "definition_id", insertable = false, updatable = false)
    private DefinitionEntity definition;

    @Column(name = "\"timestamp\"")
    private Instant timestamp;

    @Column(name = "archetype_id")
    private UUID archetypeId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "statement", columnDefinition = "jsonb")
    private JsonNode statement;

    @Column(name = "version")
    private Integer version;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status")
    private AscriptionStatus status;

    public UUID getId() {
        return id;
    }

    public DefinitionSubjectType getSubjectType() {
        return subjectType;
    }

    public DefinitionEntity getDefinition() {
        return definition;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public UUID getArchetypeId() {
        return archetypeId;
    }

    public JsonNode getStatement() {
        return statement;
    }

    public Integer getVersion() {
        return version;
    }

    public AscriptionStatus getStatus() {
        return status;
    }
}
