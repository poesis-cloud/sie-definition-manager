package cloud.poesis.sie.defman.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.poesis.sie.defman.entity.DefinitionEntity;

public interface DefinitionRepository extends JpaRepository<DefinitionEntity, UUID> {
}
