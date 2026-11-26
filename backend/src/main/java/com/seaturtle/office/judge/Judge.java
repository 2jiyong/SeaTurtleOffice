package com.seaturtle.office.judge;

import java.util.List;

import com.seaturtle.office.game.Answer;

/** 질문과 추리를 판정한다. 실제 판정은 AI 서버(ai/)가 한다. */
public interface Judge {

	Verdict question(String scenario, List<HistoryEntry> history, String question);

	GuessVerdict guess(String scenario, String guess);

	/** 화면 상단 안내에 쓴다. AI 서버에 닿지 못하면 online=false. */
	Status status();

	record HistoryEntry(String text, Answer answer) {
	}

	record Verdict(Answer answer) {
	}

	/**
	 * 번호는 1부터. 핵심 키워드와 보너스 키워드 중 충족한 것.
	 * story: 답안 전체가 진상과 맞는 이야기인가. 핵심을 다 맞혀도 이것이 거짓이면 정답이 아니다.
	 * 이 값을 모르는 예전 AI 서버는 보내지 않는다(null).
	 */
	record GuessVerdict(List<Integer> matched, int total, List<Integer> bonus, int bonusTotal, Boolean story) {
	}

	record Status(String judge, boolean fake, boolean online) {
	}
}
