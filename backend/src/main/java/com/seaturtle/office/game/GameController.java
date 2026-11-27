package com.seaturtle.office.game;

import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.seaturtle.office.content.ContentRepository;
import com.seaturtle.office.content.ScenarioImages;
import com.seaturtle.office.game.GameDtos.GameView;
import com.seaturtle.office.game.GameDtos.GuessRequest;
import com.seaturtle.office.game.GameDtos.MyGame;
import com.seaturtle.office.game.GameDtos.QuestionRequest;
import com.seaturtle.office.game.GameDtos.ScenarioSummary;
import com.seaturtle.office.game.GameDtos.StartRequest;
import com.seaturtle.office.judge.Judge;
import com.seaturtle.office.limit.UsageLimiter;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class GameController {

	private final ContentRepository content;
	private final GameService games;
	private final Judge judge;
	private final ScenarioImages images;
	private final UsageLimiter limiter;

	public GameController(ContentRepository content, GameService games, Judge judge, ScenarioImages images,
			UsageLimiter limiter) {
		this.content = content;
		this.games = games;
		this.judge = judge;
		this.images = images;
		this.limiter = limiter;
	}

	/** budgetExhausted: 오늘 판정 예산을 다 썼다(쓴 금액 자체는 공개하지 않는다). */
	public record ServerInfo(String judge, boolean fakeJudge, boolean judgeOnline, boolean budgetExhausted) {
	}

	@GetMapping("/info")
	public ServerInfo info() {
		Judge.Status status = judge.status();
		return new ServerInfo(status.judge(), status.fake(), status.online(), limiter.budgetExhausted());
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

	/** 이 기기에 그 시나리오의 판이 있으면 그 판(200), 없으면 새 판(201). */
	@PostMapping("/games")
	public ResponseEntity<GameView> start(@RequestAttribute(ClientIdFilter.ATTRIBUTE) String clientId,
			@Valid @RequestBody StartRequest request) {
		var started = games.start(clientId, request.scenario());
		return ResponseEntity.status(started.created() ? HttpStatus.CREATED : HttpStatus.OK)
				.body(GameView.of(started.game(), images));
	}

	/** 이 기기의 판 목록. 메인 페이지의 진행 상태에 쓴다. */
	@GetMapping("/games")
	public List<MyGame> myGames(@RequestAttribute(ClientIdFilter.ATTRIBUTE) String clientId) {
		return games.list(clientId).stream().map(MyGame::of).toList();
	}

	@GetMapping("/games/{id}")
	public GameView get(@RequestAttribute(ClientIdFilter.ATTRIBUTE) String clientId, @PathVariable String id) {
		return GameView.of(games.get(clientId, id), images);
	}

	@PostMapping("/games/{id}/questions")
	public GameView ask(@RequestAttribute(ClientIdFilter.ATTRIBUTE) String clientId, @PathVariable String id,
			@Valid @RequestBody QuestionRequest request) {
		// 질문 하나로 힌트가 열리거나 마지막 기회가 될 수 있어서 게임 전체를 돌려준다.
		return GameView.of(games.ask(clientId, id, request.text().strip()), images);
	}

	@PostMapping("/games/{id}/guesses")
	public GameView guess(@RequestAttribute(ClientIdFilter.ATTRIBUTE) String clientId, @PathVariable String id,
			@Valid @RequestBody GuessRequest request) {
		return GameView.of(games.guess(clientId, id, request.text().strip()), images);
	}

	@PostMapping("/games/{id}/hints")
	public GameView hint(@RequestAttribute(ClientIdFilter.ATTRIBUTE) String clientId, @PathVariable String id) {
		return GameView.of(games.hint(clientId, id), images);
	}

	@PostMapping("/games/{id}/give-up")
	public GameView giveUp(@RequestAttribute(ClientIdFilter.ATTRIBUTE) String clientId, @PathVariable String id) {
		return GameView.of(games.giveUp(clientId, id), images);
	}

	/** 초기화: 이 판과 기록을 지운다. 다시 하려면 POST /games. */
	@DeleteMapping("/games/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void reset(@RequestAttribute(ClientIdFilter.ATTRIBUTE) String clientId, @PathVariable String id) {
		games.reset(clientId, id);
	}
}
