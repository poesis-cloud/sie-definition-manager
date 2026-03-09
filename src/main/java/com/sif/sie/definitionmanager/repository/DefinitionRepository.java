package com.sif.sie.definitionmanager.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sif.sie.definitionmanager.entity.DefinitionEntity;

public interface DefinitionRepository extends JpaRepository<DefinitionEntity, UUID> {
}
