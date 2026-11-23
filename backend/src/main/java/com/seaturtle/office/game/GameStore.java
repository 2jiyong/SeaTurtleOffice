package com.seaturtle.office.game;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.seaturtle.office.content.Scenario;

/** 진행 중인 게임을 메모리에 둔다. 서버를 다시 켜면 사라진다. */
@Component
public class GameStore {

	private final Map<String, Game> games = new ConcurrentHashMap<>();
	private final GameProperties props;

	public GameStore(GameProperties props) {
		this.props = props;
	}

	public Game create(Scenario scenario) {
		Game game = new Game(UUID.randomUUID().toString(), scenario);
		games.put(game.id(), game);
		return game;
	}

	public Game get(String id) {
		Game game = games.get(id);
		if (game == null) {
			throw new GameNotFoundException("게임");
		}
		game.touch();
		return game;
	}

	@Scheduled(fixedDelay = 600_000)
	void evictIdle() {
		Instant cutoff = Instant.now().minus(props.idleTtl());
		games.values().removeIf(game -> game.lastActive().isBefore(cutoff));
	}
}
