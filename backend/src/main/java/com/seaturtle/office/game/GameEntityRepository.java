package com.seaturtle.office.game;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

interface GameEntityRepository extends JpaRepository<GameEntity, UUID> {

	@EntityGraph(attributePaths = "entries")
	Optional<GameEntity> findByIdAndClientId(UUID id, UUID clientId);

	@EntityGraph(attributePaths = "entries")
	Optional<GameEntity> findByClientIdAndScenarioSlug(UUID clientId, String scenarioSlug);

	@EntityGraph(attributePaths = "entries")
	List<GameEntity> findByClientId(UUID clientId);

	long deleteByIdAndClientId(UUID id, UUID clientId);
}
