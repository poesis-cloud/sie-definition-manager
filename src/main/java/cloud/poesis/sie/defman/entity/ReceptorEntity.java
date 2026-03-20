package cloud.poesis.sie.defman.entity;

import java.util.Objects;

import org.springframework.lang.NonNull;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Receptor — input endpoint of a Mechanism. Extends AscriptionEntity.
 *
 * <p>
 * Carries the standard 6-trigger set on the {@code receptor} table
 * (see {@link AscriptionEntity} for details).
 *
 * @author Clément Cazaud
 * @since 0.1.0
 */
@SuppressWarnings("null") // JPA lifecycle: fields are always populated when accessed
@Entity
@Table(name = "receptor")
public class ReceptorEntity extends AscriptionEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mechanism_id", nullable = false, updatable = false)
    private MechanismEntity mechanism;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "input_archetype_id", nullable = false, updatable = false)
    private ArchetypeEntity inputArchetype;

    protected ReceptorEntity() {
    }

    /**
     * Creates a new Receptor ascription for the given mechanism.
     *
     * @param definition     the stable identity this receptor ascribes to
     * @param archetype      the typing archetype (ReceptorArchetype)
     * @param statement      the JSON payload for this receptor
     * @param mechanism      the owning mechanism
     * @param inputArchetype the data archetype this receptor consumes
     */
    public ReceptorEntity(
            DefinitionEntity definition,
            ArchetypeEntity archetype,
            JsonNode statement,
            MechanismEntity mechanism,
            ArchetypeEntity inputArchetype) {
        super(definition, archetype, statement);
        this.mechanism = Objects.requireNonNull(mechanism, "mechanism");
        this.inputArchetype = Objects.requireNonNull(inputArchetype, "inputArchetype");
    }

    /**
     * Returns the owning mechanism.
     *
     * @return the mechanism, never {@code null}
     */
    @NonNull
    public MechanismEntity getMechanism() {
        return mechanism;
    }

    /**
     * Returns the data archetype this receptor consumes.
     *
     * @return the input archetype, never {@code null}
     */
    @NonNull
    public ArchetypeEntity getInputArchetype() {
        return inputArchetype;
    }
}
