package cloud.poesis.sie.defman.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * Directive — identity-level normative constraint. Extends AscriptionEntity.
 *
 * <p>Carries the standard 6-trigger set on the {@code directive} table (see {@link
 * AscriptionEntity} for details).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Entity
@Table(name = "directive")
public class DirectiveEntity extends AscriptionEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "structure_id", nullable = false, updatable = false)
  private StructureEntity structure;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "qualifier_id", nullable = false, updatable = false)
  private ArchetypeEntity qualifier;

  protected DirectiveEntity() {}

  /**
   * Creates a new Directive ascription.
   *
   * @param definition the stable identity this directive ascribes to
   * @param archetype the typing archetype (Directive)
   * @param statement the JSON payload containing modal, verb, and governance grammar
   * @param structure the authoring structure
   * @param qualifier the archetype defining the viability dimension being governed
   */
  public DirectiveEntity(
      DefinitionEntity definition,
      ArchetypeEntity archetype,
      JsonNode statement,
      StructureEntity structure,
      ArchetypeEntity qualifier) {
    super(definition, archetype, statement);
    this.structure = Objects.requireNonNull(structure, "structure");
    this.qualifier = Objects.requireNonNull(qualifier, "qualifier");
  }

  /**
   * Returns the authoring structure that owns this directive.
   *
   * @return the structure, never {@code null}
   */
  public StructureEntity getStructure() {
    return structure;
  }

  /**
   * Returns the qualifier archetype defining the governed viability dimension.
   *
   * @return the qualifier archetype, never {@code null}
   */
  public ArchetypeEntity getQualifier() {
    return qualifier;
  }
}
