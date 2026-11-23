package com.seaturtle.office.game;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.seaturtle.office.content.ContentRepository;
import com.seaturtle.office.game.GameDtos.GameView;
import com.seaturtle.office.game.GameDtos.GuessRequest;
import com.seaturtle.office.game.GameDtos.QuestionRequest;
import com.seaturtle.office.game.GameDtos.ScenarioSummary;
import com.seaturtle.office.game.GameDtos.StartRequest;
import com.seaturtle.office.judge.Judge;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class GameController {

	private final ContentRepository content;
	private final GameService games;
	private final Judge judge;

	public GameController(ContentRepository content, GameService games, Judge judge) {
		this.content = content;
		this.games = games;
		this.judge = judge;
	}

	public record ServerInfo(String judge, boolean fakeJudge, boolean judgeOnline) {
	}

	@GetMapping("/info")
	public ServerInfo info() {
		Judge.Status status = judge.status();
		return new ServerInfo(status.judge(), status.fake(), status.online());
	}

	@GetMapping("/scenarios")
	public List<ScenarioSummary> scenarios() {
		return content.scenarios().stream().map(ScenarioSummary::of).toList();
	}

	@PostMapping("/games")
	@ResponseStatus(HttpStatus.CREATED)
	public GameView start(@Valid @RequestBody StartRequest request) {
		return GameView.of(games.start(request.scenario()));
	}

	@GetMapping("/games/{id}")
	public GameView get(@PathVariable String id) {
		return GameView.of(games.get(id));
	}

	@PostMapping("/games/{id}/questions")
	public Game.QuestionRecord ask(@PathVariable String id, @Valid @RequestBody QuestionRequest request) {
		return games.ask(id, request.text().strip());
	}

	@PostMapping("/games/{id}/guesses")
	public GameView guess(@PathVariable String id, @Valid @RequestBody GuessRequest request) {
		return GameView.of(games.guess(id, request.text().strip()));
	}

	@PostMapping("/games/{id}/hints")
	public GameView hint(@PathVariable String id) {
		return GameView.of(games.hint(id));
	}

	@PostMapping("/games/{id}/give-up")
	public GameView giveUp(@PathVariable String id) {
		return GameView.of(games.giveUp(id));
	}
}
