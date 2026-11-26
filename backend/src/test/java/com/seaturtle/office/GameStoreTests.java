package com.seaturtle.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.seaturtle.office.content.ContentRepository;
import com.seaturtle.office.content.Scenario;
import com.seaturtle.office.content.ScenarioImages;
import com.seaturtle.office.game.Answer;
import com.seaturtle.office.game.Game;
import com.seaturtle.office.game.GameConflictException;
import com.seaturtle.office.game.GameDtos;
import com.seaturtle.office.game.GameStatus;
import com.seaturtle.office.game.GameStore;

/** 게임을 DB에 쓰고 다시 읽는다. 테스트마다 다른 기기(client)를 쓴다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GameStoreTests {

	@Autowired
	GameStore store;

	@Autowired
	ContentRepository content;

	@Autowired
	ScenarioImages images;

	@Autowired
	JdbcClient jdbc;

	final String client = UUID.randomUUID().toString();

	Scenario scenario() {
		return content.scenario("boys-order").orElseThrow();
	}

	@Test
	void roundTripKeepsEverything() {
		Game game = store.create(client, scenario());
		for (int i = 0; i < 10; i++) {
			game.addQuestion("q" + i, Answer.YES);
		}
		game.addGuess("g", List.of(1, 3), List.of(1));
		store.save(game);

		Game loaded = store.find(client, game.id()).orElseThrow();
		assertThat(loaded.questions()).isEqualTo(game.questions());
		assertThat(loaded.guesses()).isEqualTo(game.guesses());
		assertThat(loaded.hints()).isEqualTo(game.hints());
		assertThat(loaded.hints()).hasSize(1);
		assertThat(loaded.hints().getFirst().text()).isEqualTo(scenario().hints().getFirst());
		assertThat(loaded.used()).isEqualTo(game.used());
		assertThat(loaded.seq()).isEqualTo(game.seq());
		assertThat(loaded.keywordFound()).isEqualTo(Set.of(1, 3));
		assertThat(loaded.bonusFound()).isEqualTo(Set.of(1));
		assertThat(loaded.status()).isEqualTo(GameStatus.PLAYING);
		assertThat(loaded.clientId()).isEqualTo(client);
	}

	@Test
	void otherClientCannotFind() {
		Game game = store.create(client, scenario());
		assertThat(store.find(UUID.randomUUID().toString(), game.id())).isEmpty();
		assertThat(store.find(client, "not-a-uuid")).isEmpty();
		assertThat(store.find("not-a-uuid", game.id())).isEmpty();
	}

	@Test
	void findByScenarioFindsMine() {
		Game game = store.create(client, scenario());
		assertThat(store.findByScenario(client, "boys-order").orElseThrow().id()).isEqualTo(game.id());
		assertThat(store.findByScenario(UUID.randomUUID().toString(), "boys-order")).isEmpty();
	}

	@Test
	void duplicateCreateFails() {
		store.create(client, scenario());
		assertThatThrownBy(() -> store.create(client, scenario())).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void staleSaveConflicts() {
		Game game = store.create(client, scenario());
		Game a = store.find(client, game.id()).orElseThrow();
		Game b = store.find(client, game.id()).orElseThrow();
		a.addQuestion("먼저", Answer.YES);
		store.save(a);
		b.addQuestion("나중", Answer.NO);
		assertThatThrownBy(() -> store.save(b)).isInstanceOf(GameConflictException.class);
		assertThat(store.find(client, game.id()).orElseThrow().questions())
				.extracting(Game.QuestionRecord::text).containsExactly("먼저");
	}

	@Test
	void savingTwiceInARowWorks() {
		Game game = store.create(client, scenario());
		game.addQuestion("하나", Answer.YES);
		store.save(game);
		Game again = store.find(client, game.id()).orElseThrow();
		again.addQuestion("둘", Answer.NO);
		store.save(again);
		assertThat(store.find(client, game.id()).orElseThrow().questions()).hasSize(2);
	}

	@Test
	void missingScenarioIsHidden() {
		String id = UUID.randomUUID().toString();
		OffsetDateTime now = OffsetDateTime.now();
		jdbc.sql("""
				insert into games (id, client_id, scenario_slug, status, used, seq, keyword_found, bonus_found,
				                   version, created_at, updated_at)
				values (?::uuid, ?::uuid, 'gone', 'PLAYING', 0, 0, '{}', '{}', 0, ?, ?)
				""").params(id, client, now, now).update();
		store.create(client, scenario());

		assertThat(store.list(client)).extracting(g -> g.scenario().slug()).containsExactly("boys-order");
		assertThat(store.find(client, id)).isEmpty();
	}

	@Test
	void outOfRangeKeywordsAreIgnoredOnReveal() {
		Scenario s = scenario();
		Game game = Game.restore(UUID.randomUUID().toString(), client, s, GameStatus.SOLVED, 3, 0,
				List.of(), List.of(), List.of(), Set.of(1, 2, 3, 9), Set.of(1, 7), 0);
		var reveal = GameDtos.GameView.of(game, images).reveal();
		assertThat(reveal.keywords()).hasSize(s.keywords().size());
		assertThat(reveal.bonuses()).hasSize(s.bonuses().size());
	}

	@Test
	void deleteRemovesEntries() {
		Game game = store.create(client, scenario());
		game.addQuestion("q", Answer.YES);
		store.save(game);

		assertThat(store.delete(UUID.randomUUID().toString(), game.id())).isFalse();
		assertThat(store.delete(client, game.id())).isTrue();
		assertThat(store.find(client, game.id())).isEmpty();
		long rows = jdbc.sql("select count(*) from game_entries where game_id = ?::uuid").param(game.id())
				.query(Long.class).single();
		assertThat(rows).isZero();
	}

	@Test
	void concurrentSavesGiveOneWinnerAndConflict() throws Exception {
		// 두 창의 판정이 거의 동시에 끝나 같은 판을 함께 저장한다. 하나만 저장되고 나머지는 충돌(409)이어야 한다.
		for (int round = 0; round < 20; round++) {
			Game game = store.create(UUID.randomUUID().toString(), scenario());
			Game a = store.find(game.clientId(), game.id()).orElseThrow();
			Game b = store.find(game.clientId(), game.id()).orElseThrow();
			a.addQuestion("가", Answer.YES);
			b.addQuestion("나", Answer.NO);
			var start = new java.util.concurrent.CountDownLatch(1);
			var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
			try {
				var fa = pool.submit(() -> {
					start.await();
					store.save(a);
					return null;
				});
				var fb = pool.submit(() -> {
					start.await();
					store.save(b);
					return null;
				});
				start.countDown();
				int conflicts = 0;
				for (var f : List.of(fa, fb)) {
					try {
						f.get();
					} catch (java.util.concurrent.ExecutionException e) {
						assertThat(e.getCause()).isInstanceOf(GameConflictException.class);
						conflicts++;
					}
				}
				assertThat(conflicts).isEqualTo(1);
				assertThat(store.find(game.clientId(), game.id()).orElseThrow().questions()).hasSize(1);
			} finally {
				pool.shutdownNow();
			}
		}
	}

	// ── 시나리오가 바뀐 뒤 ──

	@Test
	void hintButtonNeverLowersUsedWhenScenarioGainedHints() {
		// 힌트가 하나뿐일 때 35까지 쓴 판. 시나리오에 힌트가 늘어 지금은 셋이다.
		Scenario s = scenario();
		var hint1 = new Game.HintRecord(1, 11, s.hints().getFirst());
		Game game = Game.restore(UUID.randomUUID().toString(), client, s, GameStatus.PLAYING, 35, 40,
				List.of(), List.of(), List.of(hint1), Set.of(), Set.of(), 0);
		game.useHint();
		assertThat(game.used()).isEqualTo(35);
		assertThat(game.hintsOpen()).isEqualTo(3);

		Game atLimit = Game.restore(UUID.randomUUID().toString(), client, s, GameStatus.PLAYING, 40, 45,
				List.of(), List.of(), List.of(hint1), Set.of(), Set.of(), 0);
		atLimit.useHint();
		assertThat(atLimit.used()).isEqualTo(40);
		assertThat(atLimit.finalChance()).isTrue();
	}

	@Test
	void deleteKeepsGameWhoseScenarioIsGone() {
		String id = UUID.randomUUID().toString();
		OffsetDateTime now = OffsetDateTime.now();
		jdbc.sql("""
				insert into games (id, client_id, scenario_slug, status, used, seq, keyword_found, bonus_found,
				                   version, created_at, updated_at)
				values (?::uuid, ?::uuid, 'gone', 'SOLVED', 0, 0, '{}', '{}', 0, ?, ?)
				""").params(id, client, now, now).update();
		assertThat(store.delete(client, id)).isFalse();
		long rows = jdbc.sql("select count(*) from games where id = ?::uuid").param(id).query(Long.class).single();
		assertThat(rows).isOne();
	}

	@Test
	void deleteRacingWithSaveNeverFailsUnexpectedly() throws Exception {
		// 초기화와 다른 창의 저장이 겹친다. 초기화는 성공하고, 저장은 저장되거나 충돌(409)로만 끝나야 한다.
		for (int round = 0; round < 20; round++) {
			Game game = store.create(UUID.randomUUID().toString(), scenario());
			Game other = store.find(game.clientId(), game.id()).orElseThrow();
			other.addQuestion("다른 창", Answer.YES);
			var start = new java.util.concurrent.CountDownLatch(1);
			var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
			try {
				var deleted = pool.submit(() -> {
					start.await();
					return store.delete(game.clientId(), game.id());
				});
				var saved = pool.submit(() -> {
					start.await();
					store.save(other);
					return null;
				});
				start.countDown();
				assertThat(deleted.get()).isTrue();
				try {
					saved.get();
				} catch (java.util.concurrent.ExecutionException e) {
					assertThat(e.getCause()).isInstanceOf(GameConflictException.class);
				}
				assertThat(store.find(game.clientId(), game.id())).isEmpty();
			} finally {
				pool.shutdownNow();
			}
		}
	}
}
