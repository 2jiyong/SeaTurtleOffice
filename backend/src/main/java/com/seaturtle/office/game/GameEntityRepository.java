package com.seaturtle.office.game;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

interface GameEntityRepository extends JpaRepository<GameEntity, UUID> {

	@EntityGraph(attributePaths = "entries")
	Optional<GameEntity> findByIdAndClientId(UUID id, UUID clientId);

	@EntityGraph(attributePaths = "entries")
	Optional<GameEntity> findByClientIdAndScenarioSlug(UUID clientId, String scenarioSlug);

	@EntityGraph(attributePaths = "entries")
	List<GameEntity> findByClientId(UUID clientId);

	/**
	 * 저장 직전에 행을 잠그고 읽는다(SELECT … FOR UPDATE). 같은 판을 동시에 저장하면 뒤의 것이 기다렸다가
	 * 바뀐 version을 보고 충돌로 끝난다. Postgres는 outer join에 FOR UPDATE를 못 거므로 항목은 같은 트랜잭션에서 따로 읽는다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<GameEntity> findLockedByIdAndClientId(UUID id, UUID clientId);

	long deleteByIdAndClientId(UUID id, UUID clientId);
}
