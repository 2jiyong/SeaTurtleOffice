package com.seaturtle.office.event;

import java.util.Map;

import com.seaturtle.office.game.Game;
import com.seaturtle.office.judge.Judge;

/**
 * events 테이블 한 줄. 판정 이벤트(question_asked, guess_submitted)만 시간·지표 칸을 채운다.
 * outcome: ok / judge_error / timeout / conflict.
 */
public record Event(String type, String clientId, String gameId, String scenario, String outcome, String answer,
		Integer usedAfter, Integer textLength, Integer totalMs, Judge.Metrics metrics, Map<String, Object> detail) {

	public static final String GAME_STARTED = "game_started";
	public static final String QUESTION_ASKED = "question_asked";
	public static final String GUESS_SUBMITTED = "guess_submitted";
	public static final String HINT_USED = "hint_used";
	public static final String GAME_ENDED = "game_ended";
	public static final String GAME_RESET = "game_reset";

	/** 판정 없는 사건(시작, 힌트, 종료, 초기화) */
	public static Event of(String type, Game game, Map<String, Object> detail) {
		return new Event(type, game.clientId(), game.id(), game.scenario().slug(), "ok", null, game.used(), null,
				null, null, detail);
	}

	/** 판정 이벤트. text는 길이만 남긴다(앞뒤 공백 뺀 코드 포인트 수). */
	public static Event judged(String type, Game game, String outcome, String answer, String text, long startedNanos,
			Judge.Metrics metrics, Map<String, Object> detail) {
		String stripped = text.strip();
		int totalMs = (int) ((System.nanoTime() - startedNanos) / 1_000_000);
		return new Event(type, game.clientId(), game.id(), game.scenario().slug(), outcome, answer, game.used(),
				stripped.codePointCount(0, stripped.length()), totalMs, metrics, detail);
	}
}
