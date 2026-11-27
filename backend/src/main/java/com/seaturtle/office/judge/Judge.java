package com.seaturtle.office.judge;

import java.util.List;

import com.seaturtle.office.game.Answer;

/** 질문과 추리를 판정한다. 실제 판정은 AI 서버(ai/)가 한다. */
public interface Judge {

	Verdict question(String scenario, List<HistoryEntry> history, String question);

	GuessVerdict guess(String scenario, String guess);

	/** 화면 상단 안내에 쓴다. AI 서버에 닿지 못하면 online=false. */
	Status status();

	/** proposition: 그 질문의 명제. 반려·예전 기록은 null. */
	record HistoryEntry(String text, Answer answer, String proposition) {

		public HistoryEntry(String text, Answer answer) {
			this(text, answer, null);
		}
	}

	/**
	 * 판정 하나의 비용(AI 서버가 잰 값). 시간은 ms, aiMs ⊇ llmMs. 예전 AI 서버는 보내지 않는다(null).
	 */
	record Metrics(String model, Integer aiMs, Integer llmMs, Integer inputTokens, Integer outputTokens,
			Integer cachedTokens) {
	}

	/**
	 * proposition: 진행자가 읽은 명제(화면에 보인다). reason: 판정 근거(DB에만 남긴다).
	 * 예전 AI 서버는 둘 다 보내지 않는다(null).
	 */
	record Verdict(Answer answer, String proposition, String reason, Metrics metrics) {

		public Verdict(Answer answer, Metrics metrics) {
			this(answer, null, null, metrics);
		}
	}

	/**
	 * 번호는 1부터. 핵심 키워드와 보너스 키워드 중 충족한 것.
	 * story: 답안 전체가 진상과 맞는 이야기인가. 핵심을 다 맞혀도 이것이 거짓이면 정답이 아니다.
	 * 이 값을 모르는 예전 AI 서버는 보내지 않는다(null).
	 */
	record GuessVerdict(List<Integer> matched, int total, List<Integer> bonus, int bonusTotal, Boolean story,
			String reason, Metrics metrics) {

		public GuessVerdict(List<Integer> matched, int total, List<Integer> bonus, int bonusTotal, Boolean story,
				Metrics metrics) {
			this(matched, total, bonus, bonusTotal, story, null, metrics);
		}
	}

	record Status(String judge, boolean fake, boolean online) {
	}
}
