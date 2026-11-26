package com.seaturtle.office.game;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.seaturtle.office.content.ContentRepository;
import com.seaturtle.office.content.Scenario;
import com.seaturtle.office.game.GameEntryEntity.Kind;

/**
 * 게임을 DB(games, game_entries)에 쓰고 되살린다. 기기(clientId)가 다르면 없는 판으로 본다.
 * 시나리오 파일이 사라진 판도 없는 판으로 본다(지우지는 않는다).
 */
@Component
public class GameStore {

	private final GameEntityRepository repo;
	private final ContentRepository content;

	public GameStore(GameEntityRepository repo, ContentRepository content) {
		this.repo = repo;
		this.content = content;
	}

	/** 새 판. 이 기기에 같은 시나리오의 판이 있으면 DataIntegrityViolationException. */
	@Transactional
	public Game create(String clientId, Scenario scenario) {
		Game game = new Game(UUID.randomUUID().toString(), clientId, scenario);
		GameEntity entity = new GameEntity();
		entity.id = UUID.fromString(game.id());
		entity.clientId = UUID.fromString(clientId);
		entity.scenarioSlug = scenario.slug();
		entity.createdAt = OffsetDateTime.now();
		copy(game, entity);
		repo.saveAndFlush(entity);
		return game;
	}

	@Transactional(readOnly = true)
	public Optional<Game> find(String clientId, String gameId) {
		Optional<UUID> client = uuid(clientId);
		Optional<UUID> id = uuid(gameId);
		if (client.isEmpty() || id.isEmpty()) {
			return Optional.empty();
		}
		return repo.findByIdAndClientId(id.get(), client.get()).flatMap(this::toGame);
	}

	@Transactional(readOnly = true)
	public Optional<Game> findByScenario(String clientId, String slug) {
		return uuid(clientId).flatMap(c -> repo.findByClientIdAndScenarioSlug(c, slug)).flatMap(this::toGame);
	}

	@Transactional(readOnly = true)
	public List<Game> list(String clientId) {
		return uuid(clientId).map(repo::findByClientId).orElse(List.of()).stream()
				.flatMap(e -> toGame(e).stream())
				.toList();
	}

	/** 읽었을 때의 버전과 DB 버전이 다르면(그사이 다른 요청이 바꿨거나 지웠으면) GameConflictException. */
	@Transactional
	public void save(Game game) {
		GameEntity entity = repo.findLockedByIdAndClientId(UUID.fromString(game.id()), UUID.fromString(game.clientId()))
				.orElseThrow(GameConflictException::new);
		if (entity.version != game.version()) {
			throw new GameConflictException();
		}
		copy(game, entity);
		int known = entity.lastEntrySeq();
		OffsetDateTime now = OffsetDateTime.now();
		for (GameEntryEntity entry : entries(game)) {
			if (entry.seq > known) {
				entry.game = entity;
				entry.createdAt = now;
				entity.entries.add(entry);
			}
		}
		try {
			repo.saveAndFlush(entity);
		} catch (ObjectOptimisticLockingFailureException e) {
			// 버전 확인과 저장 사이에 다른 트랜잭션이 먼저 저장했다.
			throw new GameConflictException();
		}
	}

	/** 판과 항목을 지운다. 이 기기의 판이 아니거나 시나리오가 사라진 판이면 false. */
	@Transactional
	public boolean delete(String clientId, String gameId) {
		Optional<UUID> client = uuid(clientId);
		Optional<UUID> id = uuid(gameId);
		if (client.isEmpty() || id.isEmpty()) {
			return false;
		}
		// 시나리오 파일이 사라진 판은 없는 판으로 본다(목록·조회와 같다). 지우지 않고 남겨 둔다.
		boolean visible = repo.findByIdAndClientId(id.get(), client.get())
				.filter(e -> content.scenario(e.scenarioSlug).isPresent())
				.isPresent();
		return visible && repo.deleteOwned(id.get(), client.get()) > 0;
	}

	private static void copy(Game game, GameEntity entity) {
		entity.status = game.status();
		entity.used = game.used();
		entity.seq = game.seq();
		entity.keywordFound = game.keywordFound().stream().sorted().toArray(Integer[]::new);
		entity.bonusFound = game.bonusFound().stream().sorted().toArray(Integer[]::new);
		entity.updatedAt = OffsetDateTime.now();
	}

	private static List<GameEntryEntity> entries(Game game) {
		List<GameEntryEntity> result = new ArrayList<>();
		for (var q : game.questions()) {
			GameEntryEntity e = entry(Kind.QUESTION, q.seq(), q.text());
			e.no = q.no();
			e.answer = q.answer();
			result.add(e);
		}
		for (var g : game.guesses()) {
			GameEntryEntity e = entry(Kind.GUESS, g.seq(), g.text());
			e.correct = g.correct();
			e.closeness = g.closeness();
			result.add(e);
		}
		for (var h : game.hints()) {
			GameEntryEntity e = entry(Kind.HINT, h.seq(), h.text());
			e.no = h.no();
			result.add(e);
		}
		return result;
	}

	private static GameEntryEntity entry(Kind kind, int seq, String text) {
		GameEntryEntity e = new GameEntryEntity();
		e.kind = kind;
		e.seq = seq;
		e.text = text;
		return e;
	}

	private Optional<Game> toGame(GameEntity entity) {
		return content.scenario(entity.scenarioSlug).map(scenario -> {
			List<Game.QuestionRecord> questions = new ArrayList<>();
			List<Game.GuessRecord> guesses = new ArrayList<>();
			List<Game.HintRecord> hints = new ArrayList<>();
			for (GameEntryEntity e : entity.entries) {
				switch (e.kind) {
					case QUESTION -> questions.add(new Game.QuestionRecord(e.no, e.seq, e.text, e.answer));
					case GUESS -> guesses.add(new Game.GuessRecord(e.seq, e.text, e.correct, e.closeness));
					case HINT -> hints.add(new Game.HintRecord(e.no, e.seq, e.text));
				}
			}
			return Game.restore(entity.id.toString(), entity.clientId.toString(), scenario, entity.status,
					entity.used, entity.seq, questions, guesses, hints,
					Arrays.asList(entity.keywordFound), Arrays.asList(entity.bonusFound), entity.version);
		});
	}

	/** UUID가 아니면 빈 값. 대소문자만 다른 값도 같은 UUID로 읽는다. */
	private static Optional<UUID> uuid(String value) {
		try {
			return Optional.of(UUID.fromString(value));
		} catch (IllegalArgumentException | NullPointerException e) {
			return Optional.empty();
		}
	}
}
