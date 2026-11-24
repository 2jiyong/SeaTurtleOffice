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

	/** 번호는 1부터. 핵심 키워드와 보너스 키워드 중 충족한 것. */
	record GuessVerdict(List<Integer> matched, int total, List<Integer> bonus, int bonusTotal) {
	}

	record Status(String judge, boolean fake, boolean online) {
	}
}
