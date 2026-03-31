package cloud.poesis.sie.defman.repository;

import cloud.poesis.sie.defman.entity.DirectiveEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
   * Returns directives targeting the given qualifier and purpose pair.
   *
   * @param qualifierDefinitionId the qualifier archetype definition UUID
   * @param purposeDefinitionId the purpose structure definition UUID
   * @param statuses the lifecycle statuses to match
   * @return the matching directive entities
   */
  List<DirectiveEntity> findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
      UUID qualifierDefinitionId,
      UUID purposeDefinitionId,
      Collection<AscriptionStatusType> statuses);

  /**
   * Returns directives whose purpose targets the given structure definition.
   *
   * @param purposeDefinitionId the purpose structure definition UUID
   * @param statuses the lifecycle statuses to match
   * @return the matching directive entities
   */
  List<DirectiveEntity> findAllByPurposeDefinitionIdAndStatusIn(
      UUID purposeDefinitionId, Collection<AscriptionStatusType> statuses);
}
