package cloud.poesis.sie.defman.repository;

import cloud.poesis.sie.defman.entity.DefinitionEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link DefinitionEntity} (the {@code definition} table).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public interface DefinitionRepository extends JpaRepository<DefinitionEntity, UUID> {

  /**
   * Fetches a Definition with its ascriptions and their archetypes eagerly loaded (avoids N+1 on
   * archetype access).
   *
   * @param id the definition UUID
   * @return the definition with ascriptions and archetypes initialized
   */
  @EntityGraph("definition-with-ascription-archetypes")
  Optional<DefinitionEntity> findWithAscriptionArchetypesById(UUID id);
}
