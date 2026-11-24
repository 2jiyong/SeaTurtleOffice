package com.seaturtle.office.game;

import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.seaturtle.office.content.ContentRepository;
import com.seaturtle.office.content.ScenarioImages;
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
	private final ScenarioImages images;

	public GameController(ContentRepository content, GameService games, Judge judge, ScenarioImages images) {
		this.content = content;
		this.games = games;
		this.judge = judge;
		this.images = images;
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
		return content.scenarios().stream().map(s -> ScenarioSummary.of(s, images)).toList();
	}

	/** 시나리오 그림. 이름은 cover, hint-1~3만. 없는 시나리오·이름·파일은 404. */
	@GetMapping("/scenarios/{slug}/images/{name}")
	public ResponseEntity<Resource> image(@PathVariable String slug, @PathVariable String name) {
		if (content.scenario(slug).isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		return images.find(slug, name)
				.<ResponseEntity<Resource>>map(path -> {
					var resource = new FileSystemResource(path);
					MediaType type = MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM);
					return ResponseEntity.ok().contentType(type).body(resource);
				})
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@PostMapping("/games")
	@ResponseStatus(HttpStatus.CREATED)
	public GameView start(@Valid @RequestBody StartRequest request) {
		return GameView.of(games.start(request.scenario()), images);
	}

	@GetMapping("/games/{id}")
	public GameView get(@PathVariable String id) {
		return GameView.of(games.get(id), images);
	}

	@PostMapping("/games/{id}/questions")
	public GameView ask(@PathVariable String id, @Valid @RequestBody QuestionRequest request) {
		// 질문 하나로 힌트가 열리거나 마지막 기회가 될 수 있어서 게임 전체를 돌려준다.
		games.ask(id, request.text().strip());
		return GameView.of(games.get(id), images);
	}

	@PostMapping("/games/{id}/guesses")
	public GameView guess(@PathVariable String id, @Valid @RequestBody GuessRequest request) {
		return GameView.of(games.guess(id, request.text().strip()), images);
	}

	@PostMapping("/games/{id}/hints")
	public GameView hint(@PathVariable String id) {
		return GameView.of(games.hint(id), images);
	}

	@PostMapping("/games/{id}/give-up")
	public GameView giveUp(@PathVariable String id) {
		return GameView.of(games.giveUp(id), images);
	}
}
