package cloud.poesis.sie.defman.entity;

import java.util.Collections;
import java.util.List;

import org.springframework.lang.NonNull;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * Structure — the foundational aggregate. Extends AscriptionEntity.
 *
 * <p>
 * Carries the standard 6-trigger set on the {@code structure} table
 * (see {@link AscriptionEntity} for details).
 */
@SuppressWarnings("null") // JPA lifecycle: fields are always populated when accessed
@Entity
@Table(name = "structure")
public class StructureEntity extends AscriptionEntity {

    @OneToMany(mappedBy = "structure", fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<MechanismEntity> mechanisms;

    protected StructureEntity() {
    }

    public StructureEntity(
            DefinitionEntity definition, ArchetypeEntity archetype, JsonNode statement) {
        super(definition, archetype, statement);
    }

    @NonNull
    public List<MechanismEntity> getMechanisms() {
        return Collections.unmodifiableList(mechanisms);
    }
}
