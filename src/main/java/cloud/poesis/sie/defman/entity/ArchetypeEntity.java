package cloud.poesis.sie.defman.entity;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Archetype — Type definition (meta-model). Extends AscriptionEntity.
 *
 * <p>
 * No materialized columns from {@code statement} — the schema is a JSON
 * document inside the JSONB payload. Identity uniqueness ({@code schema.title})
 * is enforced via a partial expression index, not a dedicated column.
 *
 * <p>
 * Carries the standard 6-trigger set on the {@code archetype} table
 * (see {@link AscriptionEntity} for details).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Entity
@Table(name = "archetype")
public class ArchetypeEntity extends AscriptionEntity {

    protected ArchetypeEntity() {
    }

    /**
     * Creates a new Archetype ascription.
     *
     * @param definition the stable identity this archetype ascribes to
     * @param archetype  the typing archetype (self-referencing for the seed
     *                   archetype)
     * @param statement  the JSON Schema document defining this archetype
     */
    public ArchetypeEntity(
            DefinitionEntity definition, ArchetypeEntity archetype, JsonNode statement) {
        super(definition, archetype, statement);
    }
}
