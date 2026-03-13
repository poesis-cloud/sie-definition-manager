package io.poesis.sie.defman.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.poesis.sie.defman.entity.DefinitionEntity;

public interface DefinitionRepository extends JpaRepository<DefinitionEntity, UUID> {
}
