package com.seaturtle.office.game;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.seaturtle.office.content.ContentRepository;
import com.seaturtle.office.content.Scenario;
import com.seaturtle.office.judge.Judge;
import com.seaturtle.office.judge.JudgeException;

/**
 * 게임 진행. 요청마다 DB에서 판을 읽고, 규칙을 확인하고, (질문·추리는 트랜잭션 밖에서) 판정한 뒤 저장한다.
 * 읽은 뒤 다른 요청이 같은 판을 바꿨으면 저장 때 GameConflictException이 난다.
 */
@Service
public class GameService {

	/** created: 새로 만들었는가(이미 있던 판이면 false) */
	public record Started(Game game, boolean created) {
	}

	private final ContentRepository content;
	private final GameStore store;
	private final Judge judge;

	public GameService(ContentRepository content, GameStore store, Judge judge) {
		this.content = content;
		this.store = store;
		this.judge = judge;
	}

	/** 이 기기에 그 시나리오의 판이 있으면 그 판, 없으면 새 판. */
	public Started start(String clientId, String slug) {
		Scenario scenario = content.scenario(slug).orElseThrow(() -> new GameNotFoundException("시나리오"));
		var existing = store.findByScenario(clientId, slug);
		if (existing.isPresent()) {
			return new Started(existing.get(), false);
		}
		try {
			return new Started(store.create(clientId, scenario), true);
		} catch (DataIntegrityViolationException e) {
			// 같은 기기에서 시작 요청이 동시에 왔다. 먼저 만든 판을 돌려준다.
			return new Started(store.findByScenario(clientId, slug).orElseThrow(() -> e), false);
		}
	}

	public Game get(String clientId, String id) {
		return store.find(clientId, id).orElseThrow(() -> new GameNotFoundException("게임"));
	}

	public List<Game> list(String clientId) {
		return store.list(clientId);
	}

	public Game ask(String clientId, String id, String question) {
		Game game = get(clientId, id);
		game.requireQuestionAllowed();
		List<Judge.HistoryEntry> history = game.questions().stream()
				.map(q -> new Judge.HistoryEntry(q.text(), q.answer()))
				.toList();
		Judge.Verdict verdict = judge.question(game.scenario().slug(), history, question);
		game.addQuestion(question, verdict.answer());
		store.save(game);
		return game;
	}

	public Game guess(String clientId, String id, String answer) {
		Game game = get(clientId, id);
		game.requirePlaying();
		Judge.GuessVerdict verdict = judge.guess(game.scenario().slug(), answer);
		int total = game.scenario().keywords().size();
		int bonusTotal = game.scenario().bonuses().size();
		if (verdict.total() != total || verdict.bonusTotal() != bonusTotal) {
			// 두 서버가 다른 버전의 시나리오를 읽고 있다. 한쪽만 다시 켰을 때 생긴다.
			throw new JudgeException("키워드 수가 AI 서버(핵심 " + verdict.total() + ", 보너스 " + verdict.bonusTotal()
					+ ")와 백엔드(핵심 " + total + ", 보너스 " + bonusTotal + ")에서 다릅니다: "
					+ game.scenario().slug() + ". 두 서버를 모두 다시 켜세요.");
		}
		if (verdict.story() == null) {
			throw new JudgeException("AI 서버가 이야기 판정(story)을 보내지 않았습니다. 예전 판의 AI 서버입니다. 두 서버를 모두 다시 켜세요.");
		}
		game.addGuess(answer, verdict.matched(), verdict.bonus(), verdict.story());
		store.save(game);
		return game;
	}

	public Game hint(String clientId, String id) {
		return change(clientId, id, Game::useHint);
	}

	public Game giveUp(String clientId, String id) {
		return change(clientId, id, Game::giveUp);
	}

	/** 초기화: 판과 기록을 지운다. */
	public void reset(String clientId, String id) {
		if (!store.delete(clientId, id)) {
			throw new GameNotFoundException("게임");
		}
	}

	private Game change(String clientId, String id, Consumer<Game> action) {
		Game game = get(clientId, id);
		action.accept(game);
		store.save(game);
		return game;
	}
}
