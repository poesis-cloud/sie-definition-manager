package cloud.poesis.sie.defman.repository;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Shared repository contract for all GSM ascription subtype repositories.
 *
 * <p>Declares the common query methods shared across all eight ascription repositories (Structure,
 * Mechanism, Archetype, Directive, Norm, Effector, Receptor, Interaction). Concrete repositories
 * extend this interface and add subtype-specific query methods.
 *
 * @param <T> the ascription entity type
 * @author Clément Cazaud
 * @since 1.0.0
 */
@NoRepositoryBean
public interface AbstractAscriptionRepository<T extends AscriptionEntity>
    extends JpaRepository<T, UUID>, JpaSpecificationExecutor<T> {

  Page<T> findAllByStatus(AscriptionStatusType status, Pageable pageable);

  List<T> findAllByDefinitionIdOrderByTimestampDesc(UUID definitionId);

  List<T> findAllByDefinitionIdAndStatusIn(
      UUID definitionId, Collection<AscriptionStatusType> statuses);
}
