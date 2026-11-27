package com.seaturtle.office.game;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** games 테이블 한 줄. 규칙은 Game에 있고, 여기는 저장 모양만 갖는다. */
@Entity
@Table(name = "games")
class GameEntity {

	@Id
	UUID id;

	@Column(name = "client_id", nullable = false)
	UUID clientId;

	@Column(name = "scenario_slug", nullable = false)
	String scenarioSlug;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	GameStatus status;

	int used;

	int seq;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "keyword_found", nullable = false)
	Integer[] keywordFound;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "bonus_found", nullable = false)
	Integer[] bonusFound;

	/** null이면 새 행(persist). 저장할 때마다 Hibernate가 올린다. */
	@Version
	Long version;

	@Column(name = "created_at", nullable = false)
	OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	OffsetDateTime updatedAt;

	/** 초기화한 때. null이 아니면 기기에서는 없는 판으로 본다. */
	@Column(name = "hidden_at")
	OffsetDateTime hiddenAt;

	@OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("seq")
	List<GameEntryEntity> entries = new ArrayList<>();

	int lastEntrySeq() {
		return entries.stream().mapToInt(e -> e.seq).max().orElse(0);
	}
}
