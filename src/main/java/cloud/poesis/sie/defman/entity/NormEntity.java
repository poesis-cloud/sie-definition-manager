package cloud.poesis.sie.defman.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import org.springframework.lang.NonNull;

/**
 * Norm — measurable constraint predicate. Extends AscriptionEntity.
 *
 * <p>Carries the standard 6-trigger set on the {@code norm} table (see {@link AscriptionEntity} for
 * details).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Entity
@Table(name = "norm")
public class NormEntity extends AscriptionEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "structure_id", nullable = false, updatable = false)
  private StructureEntity structure;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "qualifier_id", nullable = false, updatable = false)
  private ArchetypeEntity qualifier;

  protected NormEntity() {}

  /**
   * Creates a new Norm ascription.
   *
   * @param definition the stable identity this norm ascribes to
   * @param archetype the typing archetype (NormArchetype)
   * @param statement the JSON payload containing guard, predicate, and tolerance settings
   * @param structure the authoring structure
   * @param qualifier the archetype whose properties are constrained by the predicate
   */
  public NormEntity(
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
   * Returns the authoring structure that owns this norm.
   *
   * @return the structure, never {@code null}
   */
  @NonNull
  public StructureEntity getStructure() {
    return structure;
  }

  /**
   * Returns the qualifier archetype whose properties are constrained.
   *
   * @return the qualifier archetype, never {@code null}
   */
  @NonNull
  public ArchetypeEntity getQualifier() {
    return qualifier;
  }
}
