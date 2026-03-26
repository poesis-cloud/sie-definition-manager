package cloud.poesis.sie.defman.entity;

import cloud.poesis.sie.defman.type.AscriptionStatusType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Abstract base for all 9 GSM class tables.
 *
 * <p>Maps the GSM Ascription concept: a governed normative snapshot of a Definition. Each row is an
 * immutable snapshot whose identity ({@code id}) is distinct from the stable subject identity held
 * by the referenced {@link DefinitionEntity}.
 *
 * <p>Immutable-after-creation fields ({@code definition}, {@code archetype}, {@code statement}) are
 * set via constructor. All other fields ({@code timestamp}, {@code status}, {@code version}) are DB
 * trigger-managed — no setters exposed.
 *
 * <p>DB triggers (6 per concrete table, TABLE_PER_CLASS):
 *
 * <ul>
 *   <li>{@code tgf_assign_id} — BEFORE INSERT: generates {@code id} via {@code uuid_v7()} if null
 *   <li>{@code tgf_assign_timestamp} — BEFORE INSERT: sets {@code timestamp} to {@code
 *       clock_timestamp()}
 *   <li>{@code tgf_reject_id_update} — BEFORE UPDATE OF id: blocks PK mutation
 *   <li>{@code tgf_reject_status_update} — BEFORE UPDATE OF status: blocks direct status writes
 *       (only trigger-cascaded updates from transition insert are allowed)
 *   <li>{@code tgf_restrict_ascription_delete_when_transitions_exist} — BEFORE DELETE: prevents
 *       deletion when transition rows exist
 *   <li>{@code tgf_assert_ascription_status_matches_history} — AFTER INSERT/UPDATE (deferred):
 *       verifies {@code status} matches the latest transition's {@code post_status}
 * </ul>
 *
 * <p>Additionally, inserting into {@code ascription_status_transition} fires:
 *
 * <ul>
 *   <li>{@code tgf_sync_ascription_status} — cascades {@code post_status} to this row's {@code
 *       status}
 *   <li>{@code tgf_assign_ascription_version} — increments {@code version} when {@code post_status
 *       = 'APPROVED'}
 * </ul>
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
@NamedEntityGraph(
    name = "ascription-with-refs",
    attributeNodes = {@NamedAttributeNode("definition"), @NamedAttributeNode("archetype")})
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
  private int version;

  /** JPA requires a no-arg constructor. */
  protected AscriptionEntity() {}

  /**
   * Creates a new Ascription for the given definition, typed by the given archetype.
   *
   * @param definition the stable identity this ascription ascribes to
   * @param archetype the archetype that types this ascription
   * @param statement the JSON payload ascribed to the element
   */
  protected AscriptionEntity(
      DefinitionEntity definition, ArchetypeEntity archetype, JsonNode statement) {
    this.definition = Objects.requireNonNull(definition, "definition");
    this.archetype = Objects.requireNonNull(archetype, "archetype");
    this.statement = Objects.requireNonNull(statement, "statement");
  }

  // ---- accessors ----

  /**
   * Returns the globally unique identity of this Ascription (UUIDv7).
   *
   * @return the ascription id, never {@code null}
   */
  public UUID getId() {
    return id;
  }

  /**
   * Returns the stable Definition this Ascription ascribes to.
   *
   * @return the owning definition, never {@code null}
   */
  public DefinitionEntity getDefinition() {
    return definition;
  }

  /**
   * Returns the Archetype that types this Ascription.
   *
   * @return the typing archetype, never {@code null}
   */
  public ArchetypeEntity getArchetype() {
    return archetype;
  }

  /**
   * Returns the JSON statement payload ascribed to the governed subject.
   *
   * @return the JSONB statement, never {@code null}
   */
  public JsonNode getStatement() {
    return statement;
  }

  /**
   * Returns the authoritative creation timestamp (DB-assigned).
   *
   * @return the timestamp, never {@code null}
   */
  public Instant getTimestamp() {
    return timestamp;
  }

  /**
   * Returns the current lifecycle status (trigger-managed).
   *
   * @return the ascription status, never {@code null}
   */
  public AscriptionStatusType getStatus() {
    return status;
  }

  /**
   * Returns the governance-validated lineage version (trigger-assigned on APPROVED).
   *
   * @return the version number ({@code 0} before approval, {@code >= 1} after)
   */
  public int getVersion() {
    return version;
  }

  // ---- equals / hashCode (Vlad Mihalcea pattern) ----

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AscriptionEntity that = (AscriptionEntity) o;
    return id != null && Objects.equals(id, that.id);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
