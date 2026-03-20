package cloud.poesis.sie.defman.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.DefinitionEntity;

/**
 * Spring Data JPA repository for {@link DefinitionEntity} (the
 * {@code definition} table).
 *
 * @author Clément Cazaud
 * @since 0.1.0
 */
public interface DefinitionRepository extends JpaRepository<DefinitionEntity, UUID> {
}
