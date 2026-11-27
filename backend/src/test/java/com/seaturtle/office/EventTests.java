package com.seaturtle.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.seaturtle.office.game.Answer;
import com.seaturtle.office.game.ClientIdFilter;
import com.seaturtle.office.game.GameConflictException;
import com.seaturtle.office.game.GameEndedException;
import com.seaturtle.office.game.GameService;
import com.seaturtle.office.judge.Judge;
import com.seaturtle.office.judge.JudgeException;
import com.seaturtle.office.log.RequestIdFilter;

import jakarta.servlet.http.Cookie;

/** events 테이블: 판정 지표와 게임 사건을 남긴다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventTests {

	static final Judge.Metrics METRICS = new Judge.Metrics("stub", 100, 90, 1000, 10, 500);

	static class StubJudge implements Judge {
		volatile Runnable duringSlow = () -> {
		};

		@Override
		public Verdict question(String scenario, List<HistoryEntry> history, String question) {
			if (question.contains("오류")) {
				throw new JudgeException("테스트용 판정 실패", METRICS);
			}
			if (question.contains("시간")) {
				throw new JudgeException("테스트용 시간 초과", null, null, true);
			}
			if (question.contains("느린")) {
				duringSlow.run();
			}
			return new Verdict(Answer.YES, METRICS);
		}

		@Override
		public GuessVerdict guess(String scenario, String guess) {
			if (guess.contains("정답")) {
				return new GuessVerdict(List.of(1, 2, 3), 3, List.of(), 1, true, METRICS);
			}
			if (guess.contains("개수 다름")) {
				return new GuessVerdict(List.of(1), 5, List.of(), 1, true, METRICS);
			}
			return new GuessVerdict(List.of(1), 3, List.of(), 1, true, METRICS);
		}

		@Override
		public Status status() {
			return new Status("테스트 판정기", true, true);
		}
	}

	@TestConfiguration
	static class Config {
		@Bean
		@Primary
		StubJudge eventStubJudge() {
			return new StubJudge();
		}
	}

	@Autowired
	GameService games;

	@Autowired
	StubJudge judge;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MockMvc mvc;

	final String client = UUID.randomUUID().toString();

	List<Map<String, Object>> events(String gameId) {
		return jdbc.sql("select * from events where game_id = ?::uuid order by id").param(gameId).query().listOfRows();
	}

	List<Map<String, Object>> events(String gameId, String type) {
		return events(gameId).stream().filter(e -> type.equals(e.get("type"))).toList();
	}

	String start() {
		return games.start(client, "boys-order").game().id();
	}

	@Test
	void questionRecordsOneEvent() {
		String id = start();
		games.ask(client, id, "세입자들은 죽었나요?");
		var rows = events(id, "question_asked");
		assertThat(rows).hasSize(1);
		var e = rows.getFirst();
		assertThat(e.get("outcome")).isEqualTo("ok");
		assertThat(e.get("answer")).isEqualTo("YES");
		assertThat(e.get("model")).isEqualTo("stub");
		assertThat(e.get("input_tokens")).isEqualTo(1000);
		assertThat(e.get("output_tokens")).isEqualTo(10);
		assertThat(e.get("cached_tokens")).isEqualTo(500);
		assertThat(e.get("ai_ms")).isEqualTo(100);
		assertThat(e.get("llm_ms")).isEqualTo(90);
		assertThat((Integer) e.get("total_ms")).isGreaterThanOrEqualTo(0);
		// 스텁은 즉시 답하면서 aiMs=100을 보낸다. 반올림 차이 등으로 음수가 되지 않게 0에서 멈춘다.
		assertThat(e.get("backend_ms")).isEqualTo(Math.max(0, (Integer) e.get("total_ms") - 100));
		assertThat(e.get("text_length")).isEqualTo("세입자들은 죽었나요?".codePointCount(0, "세입자들은 죽었나요?".length()));
		assertThat(e.get("app_version")).isEqualTo("dev");
		assertThat(e.get("used_after")).isEqualTo(1);
		assertThat(e.get("client_id").toString()).isEqualTo(client);
		assertThat(e.get("scenario")).isEqualTo("boys-order");
	}

	@Test
	void noQuestionTextInEvents() {
		String id = start();
		games.ask(client, id, "아주특이한문장");
		games.guess(client, id, "아주특이한추리");
		for (var row : events(id)) {
			assertThat(row.values().toString()).doesNotContain("아주특이한");
		}
	}

	@Test
	void judgeErrorKeepsTokens() {
		String id = start();
		assertThatThrownBy(() -> games.ask(client, id, "오류")).isInstanceOf(JudgeException.class);
		var e = events(id, "question_asked").getFirst();
		assertThat(e.get("outcome")).isEqualTo("judge_error");
		assertThat(e.get("input_tokens")).isEqualTo(1000);
		assertThat(e.get("answer")).isNull();
		assertThat(e.get("used_after")).isEqualTo(0);
	}

	@Test
	void backendRejectionKeepsTokens() {
		// AI 서버는 답했지만 백엔드가 거절했다(키워드 수가 다름). 토큰은 썼다.
		String id = start();
		assertThatThrownBy(() -> games.guess(client, id, "개수 다름")).isInstanceOf(JudgeException.class);
		var e = events(id, "guess_submitted").getFirst();
		assertThat(e.get("outcome")).isEqualTo("judge_error");
		assertThat(e.get("input_tokens")).isEqualTo(1000);
	}

	@Test
	void timeoutOutcome() {
		String id = start();
		assertThatThrownBy(() -> games.ask(client, id, "시간")).isInstanceOf(JudgeException.class);
		var e = events(id, "question_asked").getFirst();
		assertThat(e.get("outcome")).isEqualTo("timeout");
		assertThat(e.get("input_tokens")).isNull();
		assertThat(e.get("ai_ms")).isNull();
		assertThat(e.get("backend_ms")).isNull();
		assertThat(e.get("total_ms")).isNotNull();
	}

	@Test
	void conflictKeepsTokensAndNoEnd() {
		String id = start();
		judge.duringSlow = () -> games.giveUp(client, id);
		try {
			assertThatThrownBy(() -> games.ask(client, id, "느린 질문")).isInstanceOf(GameConflictException.class);
		} finally {
			judge.duringSlow = () -> {
			};
		}
		var asked = events(id, "question_asked").getFirst();
		assertThat(asked.get("outcome")).isEqualTo("conflict");
		assertThat(asked.get("input_tokens")).isEqualTo(1000);
		assertThat(asked.get("answer")).isEqualTo("YES");
		// 저장되지 않았으므로 사용 횟수는 판정 전 그대로다.
		assertThat(asked.get("used_after")).isEqualTo(0);
		// 포기 쪽의 game_ended 한 줄뿐이다.
		var ended = events(id, "game_ended");
		assertThat(ended).hasSize(1);
		assertThat(ended.getFirst().get("detail").toString()).contains("GAVE_UP");
	}

	@Test
	void solveRecordsEndedWithGradeMaterials() throws Exception {
		String id = start();
		mvc.perform(post("/api/games/" + id + "/guesses").cookie(new Cookie(ClientIdFilter.COOKIE, client))
				.header(RequestIdFilter.HEADER, "solve-req-0001")
				.contentType(APPLICATION_JSON).content("{\"text\":\"정답입니다\"}"))
				.andExpect(status().isOk());
		var all = events(id);
		var guess = events(id, "guess_submitted").getFirst();
		var ended = events(id, "game_ended").getFirst();
		assertThat(all.indexOf(guess)).isLessThan(all.indexOf(ended));
		assertThat(guess.get("answer")).isEqualTo("CORRECT");
		assertThat(guess.get("detail").toString()).contains("\"matched\"").contains("\"story\"");
		String detail = ended.get("detail").toString();
		assertThat(detail).contains("SOLVED").contains("\"grade\"").contains("\"adjustedUsed\"")
				.contains("\"keywordTotal\": 3").contains("\"bonusTotal\": 1");
		assertThat(guess.get("request_id")).isEqualTo("solve-req-0001");
		assertThat(ended.get("request_id")).isEqualTo("solve-req-0001");
	}

	@Test
	void startOnlyOnce() {
		String id = start();
		start();
		assertThat(events(id, "game_started")).hasSize(1);
	}

	@Test
	void hintAndResetRecorded() {
		String id = start();
		games.hint(client, id);
		assertThat(events(id, "hint_used").getFirst().get("detail").toString()).contains("\"hint\": 1");
		games.reset(client, id);
		var reset = events(id, "game_reset");
		assertThat(reset).hasSize(1);
		assertThat(reset.getFirst().get("detail").toString()).contains("PLAYING").contains("\"used\": 10");
		// 판은 숨겨졌고 이벤트도 남는다.
		assertThat(events(id, "game_started")).hasSize(1);
		assertThat(events(id, "hint_used")).hasSize(1);
	}

	@Test
	void rejectedByRulesNotRecorded() {
		String id = start();
		games.giveUp(client, id);
		int before = events(id).size();
		assertThatThrownBy(() -> games.ask(client, id, "끝난 뒤 질문")).isInstanceOf(GameEndedException.class);
		assertThat(events(id)).hasSize(before);
	}

	@Test
	void recorderFailureDoesNotBreakGame() throws Exception {
		String id = start();
		jdbc.sql("alter table events rename to events_off").update();
		try {
			mvc.perform(post("/api/games/" + id + "/questions").cookie(new Cookie(ClientIdFilter.COOKIE, client))
					.contentType(APPLICATION_JSON).content("{\"text\":\"기록이 꺼져도\"}"))
					.andExpect(status().isOk());
		} finally {
			jdbc.sql("alter table events_off rename to events").update();
		}
		assertThat(games.get(client, id).questions()).hasSize(1);
	}

	@Test
	void nulCharacterRejectedBeforeJudging() throws Exception {
		// PostgreSQL text에 저장할 수 없는 글자. 판정(토큰) 전에 막는다.
		String id = start();
		int before = events(id).size();
		mvc.perform(post("/api/games/" + id + "/questions").cookie(new Cookie(ClientIdFilter.COOKIE, client))
				.contentType(APPLICATION_JSON).content("{\"text\":\"널\\u0000문자\"}"))
				.andExpect(status().isBadRequest());
		assertThat(events(id)).hasSize(before);
		assertThat(games.get(client, id).questions()).isEmpty();
	}

	@Autowired
	javax.sql.DataSource dataSource;

	@Test
	void lockedEventsTableDoesNotHangGame() throws Exception {
		String id = start();
		try (var lock = dataSource.getConnection()) {
			lock.setAutoCommit(false);
			lock.createStatement().execute("lock table events in access exclusive mode");
			long t0 = System.nanoTime();
			games.ask(client, id, "잠긴 동안");
			long ms = (System.nanoTime() - t0) / 1_000_000;
			assertThat(ms).isLessThan(5_000);
			lock.rollback();
		}
		assertThat(games.get(client, id).questions()).hasSize(1);
	}
}
