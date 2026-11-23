package com.seaturtle.office.game;

import java.util.List;

import org.springframework.stereotype.Service;

import com.seaturtle.office.content.ContentRepository;
import com.seaturtle.office.content.Scenario;
import com.seaturtle.office.judge.Judge;
import com.seaturtle.office.judge.JudgeException;

@Service
public class GameService {

	private final ContentRepository content;
	private final GameStore store;
	private final Judge judge;

	public GameService(ContentRepository content, GameStore store, Judge judge) {
		this.content = content;
		this.store = store;
		this.judge = judge;
	}

	public Game start(String slug) {
		Scenario scenario = content.scenario(slug).orElseThrow(() -> new GameNotFoundException("시나리오"));
		return store.create(scenario);
	}

	public Game get(String id) {
		return store.get(id);
	}

	public Game.QuestionRecord ask(String id, String question) {
		Game game = store.get(id);
		game.requirePlaying();
		List<Judge.HistoryEntry> history = game.questions().stream()
				.map(q -> new Judge.HistoryEntry(q.text(), q.answer()))
				.toList();
		Judge.Verdict verdict = judge.question(game.scenario().slug(), history, question);
		return game.addQuestion(question, verdict.answer());
	}

	public Game guess(String id, String answer) {
		Game game = store.get(id);
		game.requirePlaying();
		Judge.GuessVerdict verdict = judge.guess(game.scenario().slug(), answer);
		int total = game.scenario().keyPoints().size();
		if (verdict.total() != total) {
			// 두 서버가 다른 버전의 시나리오를 읽고 있다. 한쪽만 다시 켰을 때 생긴다.
			throw new JudgeException("핵심 포인트 수가 AI 서버(" + verdict.total() + ")와 백엔드(" + total
					+ ")에서 다릅니다: " + game.scenario().slug() + ". 두 서버를 모두 다시 켜세요.");
		}
		game.addGuess(answer, verdict.matched(), total);
		return game;
	}

	public Game hint(String id) {
		Game game = store.get(id);
		game.useHint();
		return game;
	}

	public Game giveUp(String id) {
		Game game = store.get(id);
		game.giveUp();
		return game;
	}
}
