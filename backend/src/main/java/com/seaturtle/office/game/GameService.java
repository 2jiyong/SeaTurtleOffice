package com.seaturtle.office.game;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.seaturtle.office.content.ContentRepository;
import com.seaturtle.office.content.Scenario;
import com.seaturtle.office.llm.LlmClient;
import com.seaturtle.office.llm.LlmException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class GameService {

	private static final Logger log = LoggerFactory.getLogger(GameService.class);

	record QuestionJudgement(String reason, Answer answer, boolean key) {
	}

	record GuessJudgement(String reason, List<Integer> matched) {
	}

	private final ContentRepository content;
	private final GameStore store;
	private final JudgePrompts prompts;
	private final LlmClient llm;
	private final JsonMapper json;
	private final GameProperties props;

	public GameService(ContentRepository content, GameStore store, JudgePrompts prompts, LlmClient llm,
			JsonMapper json, GameProperties props) {
		this.content = content;
		this.store = store;
		this.prompts = prompts;
		this.llm = llm;
		this.json = json;
		this.props = props;
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
		List<Game.QuestionRecord> history = game.questions();
		if (history.size() > props.historyLimit()) {
			history = history.subList(history.size() - props.historyLimit(), history.size());
		}
		var prompt = prompts.question(game.scenario(), history, question);
		QuestionJudgement judgement = parse(llm.completeJson(prompt), QuestionJudgement.class);
		log.info("[{}] Q: {} → {} (key={}) / {}", game.scenario().slug(), question, judgement.answer(),
				judgement.key(), judgement.reason());
		return game.addQuestion(question, judgement.answer(), judgement.key());
	}

	public Game guess(String id, String answer) {
		Game game = store.get(id);
		game.requirePlaying();
		Scenario scenario = game.scenario();
		GuessJudgement judgement = parse(llm.completeJson(prompts.guess(scenario, answer)), GuessJudgement.class);
		int total = scenario.keyPoints().size();
		int matched = (int) judgement.matched().stream().filter(n -> n >= 1 && n <= total).distinct().count();
		log.info("[{}] 답안 {}/{} : {} / {}", scenario.slug(), matched, total, answer, judgement.reason());
		game.addGuess(answer, matched, total);
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

	private <T> T parse(String raw, Class<T> type) {
		try {
			T value = json.readValue(raw, type);
			if (value == null) {
				throw new LlmException("판정 결과가 비어 있습니다");
			}
			return value;
		} catch (JacksonException e) {
			throw new LlmException("판정 결과를 읽을 수 없습니다: " + raw, e);
		}
	}
}
