package com.seaturtle.office.game;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

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

	/**
	 * 한 번의 DELETE로 지운다(항목은 DB의 on delete cascade). 엔티티를 읽어 지우면 version 조건이 붙어
	 * 다른 창의 저장과 겹칠 때 낙관적 잠금 오류가 난다. 겹친 저장은 판을 못 찾아 충돌(409)로 끝난다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from GameEntity g where g.id = :id and g.clientId = :clientId")
	int deleteOwned(UUID id, UUID clientId);
}
