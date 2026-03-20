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
 * Interaction — causal coupling between Mechanisms. Extends AscriptionEntity.
 *
 * <p>
 * Carries the standard 6-trigger set on the {@code interaction} table
 * (see {@link AscriptionEntity} for details).
 *
 * @author Clément Cazaud
 * @since 0.1.0
 */
@SuppressWarnings("null") // JPA lifecycle: fields are always populated when accessed
@Entity
@Table(name = "interaction")
public class InteractionEntity extends AscriptionEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "effector_id", nullable = false, updatable = false)
    private EffectorEntity effector;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receptor_id", nullable = false, updatable = false)
    private ReceptorEntity receptor;

    protected InteractionEntity() {
    }

    /**
     * Creates a new Interaction coupling an effector to a receptor.
     *
     * @param definition the stable identity this interaction ascribes to
     * @param archetype  the typing archetype for this interaction
     * @param statement  the JSON payload for this interaction
     * @param effector   the emitting endpoint
     * @param receptor   the receiving endpoint
     */
    public InteractionEntity(
            DefinitionEntity definition,
            ArchetypeEntity archetype,
            JsonNode statement,
            EffectorEntity effector,
            ReceptorEntity receptor) {
        super(definition, archetype, statement);
        this.effector = Objects.requireNonNull(effector, "effector");
        this.receptor = Objects.requireNonNull(receptor, "receptor");
    }

    /**
     * Returns the emitting endpoint of this interaction.
     *
     * @return the effector, never {@code null}
     */
    @NonNull
    public EffectorEntity getEffector() {
        return effector;
    }

    /**
     * Returns the receiving endpoint of this interaction.
     *
     * @return the receptor, never {@code null}
     */
    @NonNull
    public ReceptorEntity getReceptor() {
        return receptor;
    }
}
