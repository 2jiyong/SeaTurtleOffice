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

	record GuessVerdict(int matched, int total) {
	}

	record Status(String judge, boolean fake, boolean online) {
	}
}
