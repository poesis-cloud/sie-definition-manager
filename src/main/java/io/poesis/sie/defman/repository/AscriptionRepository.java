package io.poesis.sie.defman.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.poesis.sie.defman.entity.AscriptionEntity;

public interface AscriptionRepository extends JpaRepository<AscriptionEntity, UUID> {
}
