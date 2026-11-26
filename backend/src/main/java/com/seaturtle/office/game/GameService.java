package com.seaturtle.office.game;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.seaturtle.office.content.ContentRepository;
import com.seaturtle.office.content.Scenario;
import com.seaturtle.office.event.Event;
import com.seaturtle.office.event.EventRecorder;
import com.seaturtle.office.judge.Judge;
import com.seaturtle.office.judge.JudgeException;

/**
 * 게임 진행. 요청마다 DB에서 판을 읽고, 규칙을 확인하고, (질문·추리는 트랜잭션 밖에서) 판정한 뒤 저장한다.
 * 읽은 뒤 다른 요청이 같은 판을 바꿨으면 저장 때 GameConflictException이 난다.
 * 판정을 시도한 요청과 게임의 주요 사건은 events에 남긴다(EventRecorder). 규칙 위반으로 판정 전에 막힌 요청은 남기지 않는다.
 */
@Service
public class GameService {

	/** created: 새로 만들었는가(이미 있던 판이면 false) */
	public record Started(Game game, boolean created) {
	}

	private final ContentRepository content;
	private final GameStore store;
	private final Judge judge;
	private final EventRecorder events;

	public GameService(ContentRepository content, GameStore store, Judge judge, EventRecorder events) {
		this.content = content;
		this.store = store;
		this.judge = judge;
		this.events = events;
	}

	/** 이 기기에 그 시나리오의 판이 있으면 그 판, 없으면 새 판. */
	public Started start(String clientId, String slug) {
		Scenario scenario = content.scenario(slug).orElseThrow(() -> new GameNotFoundException("시나리오"));
		var existing = store.findByScenario(clientId, slug);
		if (existing.isPresent()) {
			return new Started(existing.get(), false);
		}
		Game created;
		try {
			created = store.create(clientId, scenario);
		} catch (DataIntegrityViolationException e) {
			// 같은 기기에서 시작 요청이 동시에 왔다. 먼저 만든 판을 돌려준다.
			return new Started(store.findByScenario(clientId, slug).orElseThrow(() -> e), false);
		}
		events.record(Event.of(Event.GAME_STARTED, created, null));
		return new Started(created, true);
	}

	public Game get(String clientId, String id) {
		return store.find(clientId, id).orElseThrow(() -> new GameNotFoundException("게임"));
	}

	public List<Game> list(String clientId) {
		return store.list(clientId);
	}

	public Game ask(String clientId, String id, String question) {
		long started = System.nanoTime();
		Game game = get(clientId, id);
		game.requireQuestionAllowed();
		List<Judge.HistoryEntry> history = game.questions().stream()
				.map(q -> new Judge.HistoryEntry(q.text(), q.answer()))
				.toList();
		Judge.Verdict verdict;
		try {
			verdict = judge.question(game.scenario().slug(), history, question);
		} catch (JudgeException e) {
			events.record(Event.judged(Event.QUESTION_ASKED, game, failure(e), null, question, started, e.metrics(), null));
			throw e;
		}
		game.addQuestion(question, verdict.answer());
		String answer = verdict.answer().name();
		saveJudged(game, Event.QUESTION_ASKED, answer, question, started, verdict.metrics(), null);
		return game;
	}

	public Game guess(String clientId, String id, String answer) {
		long started = System.nanoTime();
		Game game = get(clientId, id);
		game.requirePlaying();
		Judge.GuessVerdict verdict;
		try {
			verdict = judge.guess(game.scenario().slug(), answer);
			check(game, verdict);
		} catch (JudgeException e) {
			events.record(Event.judged(Event.GUESS_SUBMITTED, game, failure(e), null, answer, started, e.metrics(), null));
			throw e;
		}
		game.addGuess(answer, verdict.matched(), verdict.bonus(), verdict.story());
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("matched", verdict.matched());
		detail.put("total", verdict.total());
		detail.put("bonus", verdict.bonus());
		detail.put("story", verdict.story());
		String closeness = game.guesses().getLast().closeness().name();
		saveJudged(game, Event.GUESS_SUBMITTED, closeness, answer, started, verdict.metrics(), detail);
		return game;
	}

	/** AI 서버가 답했지만 믿을 수 없는 판정. 토큰은 이미 썼으므로 지표를 담아 던진다. */
	private static void check(Game game, Judge.GuessVerdict verdict) {
		int total = game.scenario().keywords().size();
		int bonusTotal = game.scenario().bonuses().size();
		if (verdict.total() != total || verdict.bonusTotal() != bonusTotal) {
			// 두 서버가 다른 버전의 시나리오를 읽고 있다. 한쪽만 다시 켰을 때 생긴다.
			throw new JudgeException("키워드 수가 AI 서버(핵심 " + verdict.total() + ", 보너스 " + verdict.bonusTotal()
					+ ")와 백엔드(핵심 " + total + ", 보너스 " + bonusTotal + ")에서 다릅니다: "
					+ game.scenario().slug() + ". 두 서버를 모두 다시 켜세요.", verdict.metrics());
		}
		if (verdict.story() == null) {
			throw new JudgeException("AI 서버가 이야기 판정(story)을 보내지 않았습니다. 예전 판의 AI 서버입니다. 두 서버를 모두 다시 켜세요.",
					verdict.metrics());
		}
	}

	/** 판정 결과를 저장하고 기록한다. 저장이 409면 conflict로(토큰은 썼다), 끝난 판이면 game_ended도. */
	private void saveJudged(Game game, String type, String answer, String text, long started, Judge.Metrics metrics,
			Map<String, Object> detail) {
		try {
			store.save(game);
		} catch (GameConflictException e) {
			events.record(Event.judged(type, game, "conflict", answer, text, started, metrics, detail));
			throw e;
		}
		events.record(Event.judged(type, game, "ok", answer, text, started, metrics, detail));
		recordEnd(game);
	}

	private static String failure(JudgeException e) {
		return e.timeout() ? "timeout" : "judge_error";
	}

	/** 끝난 판이면 game_ended. 등급만이 아니라 등급을 낸 원재료를 모두 남긴다(판이 지워져도 다시 계산할 수 있게). */
	private void recordEnd(Game game) {
		if (game.status() == GameStatus.PLAYING) {
			return;
		}
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("status", game.status().name());
		detail.put("grade", game.grade() == null ? null : game.grade().name());
		detail.put("used", game.used());
		detail.put("adjustedUsed", game.adjustedUsed());
		detail.put("bonusSaved", game.bonusSaved());
		detail.put("keywordsFound", game.keywordFound().size());
		detail.put("keywordTotal", game.scenario().keywords().size());
		detail.put("bonusFound", game.bonusFound().size());
		detail.put("bonusTotal", game.scenario().bonuses().size());
		events.record(Event.of(Event.GAME_ENDED, game, detail));
	}

	public Game hint(String clientId, String id) {
		Game game = change(clientId, id, Game::useHint);
		events.record(Event.of(Event.HINT_USED, game, Map.of("hint", game.hintsOpen())));
		return game;
	}

	public Game giveUp(String clientId, String id) {
		Game game = change(clientId, id, Game::giveUp);
		recordEnd(game);
		return game;
	}

	/** 초기화: 판과 기록을 지운다. 이벤트는 남는다. */
	public void reset(String clientId, String id) {
		Game game = get(clientId, id);
		if (!store.delete(clientId, id)) {
			throw new GameNotFoundException("게임");
		}
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("status", game.status().name());
		detail.put("used", game.used());
		events.record(Event.of(Event.GAME_RESET, game, detail));
	}

	private Game change(String clientId, String id, Consumer<Game> action) {
		Game game = get(clientId, id);
		action.accept(game);
		store.save(game);
		return game;
	}
}
