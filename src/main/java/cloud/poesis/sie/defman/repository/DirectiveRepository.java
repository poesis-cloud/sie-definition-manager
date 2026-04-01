package cloud.poesis.sie.defman.repository;

import cloud.poesis.sie.defman.entity.DirectiveEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA repository for {@link DirectiveEntity} (the {@code directive} table).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public interface DirectiveRepository extends AbstractAscriptionRepository<DirectiveEntity> {

  /**
   * Returns a page of directives authored by a specific structure.
   *
   * @param structureId the structure UUID
   * @param pageable pagination parameters
   * @return a page of matching directive entities
   */
  Page<DirectiveEntity> findAllByStructureId(UUID structureId, Pageable pageable);

  /**
   * Returns all directives authored by a specific structure.
   *
   * @param structureId the structure UUID
   * @return the matching directive entities
   */
  List<DirectiveEntity> findAllByStructureId(UUID structureId);

  /**
   * Returns in-effect directives whose statement purpose matches the given string.
   *
   * @param purpose the governed purpose string (from statement JSONB)
   * @return the matching directive entities
   */
  @Query(
      value =
          "SELECT * FROM directive"
              + " WHERE statement->>'purpose' = ?1"
              + " AND status IN ('ACTIVE', 'DEPRECATED')",
      nativeQuery = true)
  List<DirectiveEntity> findAllInEffectByPurpose(String purpose);
}
