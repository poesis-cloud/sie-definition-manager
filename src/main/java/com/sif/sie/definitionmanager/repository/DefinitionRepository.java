package com.sif.sie.definitionmanager.repository;

import com.sif.sie.definitionmanager.entity.DefinitionEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DefinitionRepository extends JpaRepository<DefinitionEntity, UUID> {}
