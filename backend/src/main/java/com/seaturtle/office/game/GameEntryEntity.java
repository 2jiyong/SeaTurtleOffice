package com.seaturtle.office.game;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** game_entries 테이블 한 줄: 질문, 추리, 힌트 가운데 하나. */
@Entity
@Table(name = "game_entries")
class GameEntryEntity {

	enum Kind {
		QUESTION, GUESS, HINT
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "game_id")
	GameEntity game;

	int seq;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	Kind kind;

	/** 질문 번호, 힌트 번호. 추리는 null. */
	Integer no;

	/** 질문문, 추리문, 힌트 본문(열린 순간의 문장) */
	@Column(nullable = false)
	String text;

	@Enumerated(EnumType.STRING)
	Answer answer;

	Boolean correct;

	@Enumerated(EnumType.STRING)
	Closeness closeness;

	/** 질문의 명제(화면에 보인다). 반려·예전 기록은 null. */
	String proposition;

	/** 질문·추리의 판정 근거. 진상이 담겨 있어 화면에는 보내지 않는다. */
	String reason;

	@Column(name = "created_at", nullable = false)
	OffsetDateTime createdAt;
}
