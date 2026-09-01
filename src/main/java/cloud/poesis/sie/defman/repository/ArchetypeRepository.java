package cloud.poesis.sie.defman.repository;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link ArchetypeEntity} (the {@code archetype} table).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public interface ArchetypeRepository extends AbstractAscriptionRepository<ArchetypeEntity> {

  /**
   * Returns all archetypes filtered by lifecycle status.
   *
   * @param status the lifecycle status to match
   * @return the matching archetype entities
   */
  List<ArchetypeEntity> findAllByStatus(AscriptionStatusType status);

  /**
   * Returns a page of archetypes filtered by a set of lifecycle statuses.
   *
   * @param statuses the lifecycle statuses to match
   * @param pageable pagination parameters
   * @return a page of matching archetype entities
   */
  Page<ArchetypeEntity> findAllByStatusIn(
      Collection<AscriptionStatusType> statuses, Pageable pageable);

  /**
   * Returns all archetypes filtered by a set of lifecycle statuses.
   *
   * @param statuses the lifecycle statuses to match
   * @return the matching archetype entities
   */
  List<ArchetypeEntity> findAllByStatusIn(Collection<AscriptionStatusType> statuses);

  /**
   * Resolves the single archetype row that this exact, version-pinned Archetype URI dereferences
   * to.
   *
   * <p>Resolvability is acquired <em>once</em>, at approval, when the governance version is
   * materialized ({@code version > 0}), and is thereafter <em>permanent</em>: no later status
   * change revokes it. So candidate URIs (DRAFT/PROPOSED) never resolve here — and rival candidates
   * may legitimately share one URI — while every post-approval status resolves, including {@code
   * DEPRECATED} and {@code RETIRED}, because statements are immutable and existing {@code $ref}
   * chains must keep resolving. Resolution is therefore not an eligibility check: callers needing
   * typing eligibility (ACTIVE/DEPRECATED) must additionally validate status themselves (see {@code
   * ArchetypeService#validateTypingEligibility}). At most one row can ever match: {@code
   * uq_archetype_resolvable_uri} DB-enforces global uniqueness of the resolvable URI across all
   * archetypes.
   *
   * @param uri the complete version-pinned {@code gsmarc://} Archetype URI
   * @return the Ascription the URI resolves to, if any
   */
  @Query(
      value = "SELECT * FROM archetype WHERE statement->>'$id' = :uri AND version > 0",
      nativeQuery = true)
  Optional<ArchetypeEntity> findResolvableByUri(@Param("uri") String uri);

  /**
   * Atomically acquires or reads the permanent owner of an Archetype identity stem.
   *
   * @param stem the Archetype URI without its version suffix
   * @param definitionId the Definition attempting to own the stem
   * @return the Definition that permanently owns the stem
   */
  @Query(
      value = "SELECT gsm_acquire_archetype_stem_owner(:stem, :definitionId)",
      nativeQuery = true)
  UUID acquireDefinitionIdByStem(
      @Param("stem") String stem, @Param("definitionId") UUID definitionId);
}
