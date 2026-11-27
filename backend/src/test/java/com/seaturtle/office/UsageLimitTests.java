package com.seaturtle.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.seaturtle.office.game.Answer;
import com.seaturtle.office.game.ClientIdFilter;
import com.seaturtle.office.game.GameService;
import com.seaturtle.office.judge.Judge;
import com.seaturtle.office.judge.JudgeException;
import com.seaturtle.office.limit.UsageLimitException;
import com.seaturtle.office.limit.UsageLimiter;
import com.seaturtle.office.log.IpHasher;

import jakarta.servlet.http.Cookie;

/** 판정 전 한도: 동시 판정, 하루 예산, 기기, IP. 한도는 작게 잡아 시험한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = { "limits.device-per-day=3", "limits.ip-per-day=5", "limits.daily-budget-usd=0.01",
		"limits.max-in-flight=1" })
@ExtendWith(OutputCaptureExtension.class)
class UsageLimitTests {

	static class CountingJudge implements Judge {
		final AtomicInteger calls = new AtomicInteger();
		volatile Runnable duringSlow = () -> {
		};

		@Override
		public Verdict question(String scenario, List<HistoryEntry> history, String question) {
			calls.incrementAndGet();
			if (question.contains("오류")) {
				throw new JudgeException("테스트용 판정 실패");
			}
			if (question.contains("느린")) {
				duringSlow.run();
			}
			return new Verdict(Answer.YES, null);
		}

		@Override
		public GuessVerdict guess(String scenario, String guess) {
			calls.incrementAndGet();
			return new GuessVerdict(List.of(1), 3, List.of(), 1, true, null);
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
		CountingJudge countingJudge() {
			return new CountingJudge();
		}
	}

	@Autowired
	GameService games;

	@Autowired
	CountingJudge judge;

	@Autowired
	UsageLimiter limiter;

	@Autowired
	IpHasher hasher;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MockMvc mvc;

	final String client = UUID.randomUUID().toString();

	@BeforeEach
	@AfterEach
	void clearEvents() {
		// 하루 예산은 모든 이벤트를 합하므로, 다른 테스트가 남긴 이벤트를 비우고 시작·끝낸다.
		jdbc.sql("delete from events").update();
		judge.calls.set(0);
	}

	void insertJudged(String clientId, String ipHash, OffsetDateTime at, int input, int cached, int output,
			String outcome) {
		jdbc.sql("""
				insert into events (at, type, client_id, outcome, input_tokens, cached_tokens, output_tokens,
				                    app_version, ip_hash)
				values (?, 'question_asked', ?::uuid, ?, ?, ?, ?, 'test', ?)
				""").params(at, clientId, outcome, input, cached, output, ipHash).update();
	}

	void insertJudged(String clientId, String ipHash) {
		insertJudged(clientId, ipHash, OffsetDateTime.now(), 0, 0, 0, "ok");
	}

	String start() {
		return games.start(client, "boys-order").game().id();
	}

	long limited(String clientId) {
		return jdbc.sql("select count(*) from events where client_id = ?::uuid and outcome = 'limited'")
				.param(clientId).query(Long.class).single();
	}

	@Test
	void deviceLimit() {
		String id = start();
		for (int i = 0; i < 3; i++) {
			insertJudged(client, null);
		}
		assertThatThrownBy(() -> games.ask(client, id, "네 번째"))
				.isInstanceOfSatisfying(UsageLimitException.class, e -> assertThat(e.reason()).isEqualTo("device"));
		assertThat(judge.calls).hasValue(0);
		assertThat(limited(client)).isEqualTo(1);
		String detail = jdbc.sql("select detail::text from events where outcome = 'limited'").query(String.class)
				.single();
		assertThat(detail).contains("device");
	}

	@Test
	void ipLimit() throws Exception {
		String ip = hasher.hash("198.51.100.20");
		for (int i = 0; i < 5; i++) {
			insertJudged(UUID.randomUUID().toString(), ip);
		}
		String id = start();
		mvc.perform(post("/api/games/" + id + "/questions").cookie(new Cookie(ClientIdFilter.COOKIE, client))
				.with(r -> {
					r.setRemoteAddr("198.51.100.20");
					return r;
				})
				.contentType(APPLICATION_JSON).content("{\"text\":\"질문\"}"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.reason").value("ip"))
				.andExpect(jsonPath("$.detail").value("오늘 이 단말의 교신 한도를 넘었습니다. 내일 다시 시도해 주세요."));
		assertThat(judge.calls).hasValue(0);
	}

	@Test
	void budgetLimit() throws Exception {
		// 0.025달러 > 0.01달러
		insertJudged(UUID.randomUUID().toString(), null, OffsetDateTime.now(), 100_000, 0, 0, "ok");
		String id = start();
		mvc.perform(post("/api/games/" + id + "/questions").cookie(new Cookie(ClientIdFilter.COOKIE, client))
				.contentType(APPLICATION_JSON).content("{\"text\":\"질문\"}"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.reason").value("budget"))
				.andExpect(jsonPath("$.detail").value("오늘 응시자와의 교신량이 모두 찼습니다. 내일 다시 시도해 주세요."));
		assertThat(judge.calls).hasValue(0);
	}

	@Test
	void todayStartsAtKoreanMidnight() {
		// 구현이 UTC나 JVM 시간대 자정을 쓰면 여기서 걸린다(아래 테스트는 todayStart를 기준으로 삼아 못 잡는다).
		var start = limiter.todayStart();
		assertThat(start.getOffset()).isEqualTo(java.time.ZoneOffset.ofHours(9));
		assertThat(start.getHour()).isZero();
		assertThat(start.getMinute()).isZero();
		assertThat(start.toLocalDate()).isEqualTo(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));
	}

	@Test
	void yesterdayNotCounted() {
		// 한국 시간 어제 23:30. UTC로는 오늘 날짜일 수 있어 UTC 기준으로 세면 틀린다.
		OffsetDateTime lastNight = limiter.todayStart().minusMinutes(30);
		for (int i = 0; i < 3; i++) {
			insertJudged(client, null, lastNight, 100_000, 0, 0, "ok");
		}
		String id = start();
		games.ask(client, id, "오늘 첫 질문");
		assertThat(judge.calls).hasValue(1);
	}

	@Test
	void limitedNotCounted() {
		for (int i = 0; i < 3; i++) {
			insertJudged(client, null, OffsetDateTime.now(), 0, 0, 0, "limited");
		}
		String id = start();
		games.ask(client, id, "질문");
		assertThat(judge.calls).hasValue(1);
	}

	@Test
	void concurrentJudgingRejected() {
		String id = start();
		judge.duringSlow = () -> assertThatThrownBy(() -> games.ask(client, id, "끼어든 질문"))
				.isInstanceOf(IllegalStateException.class).hasMessage("이전 판정을 기다리는 중입니다.");
		try {
			games.ask(client, id, "느린 질문");
		} finally {
			judge.duringSlow = () -> {
			};
		}
		assertThat(judge.calls).hasValue(1);
		assertThat(games.get(client, id).questions()).hasSize(1);
	}

	@Test
	void slotReleasedAfterFailure() {
		String id = start();
		assertThatThrownBy(() -> games.ask(client, id, "오류")).isInstanceOf(JudgeException.class);
		games.ask(client, id, "다시");
		assertThat(games.get(client, id).questions()).hasSize(1);
	}

	@Test
	void checkFailureDoesNotBlock() {
		String id = start();
		jdbc.sql("alter table events rename to events_off").update();
		try {
			games.ask(client, id, "검사가 안 되어도");
		} finally {
			jdbc.sql("alter table events_off rename to events").update();
		}
		assertThat(games.get(client, id).questions()).hasSize(1);
	}

	@Test
	void hintAndGiveUpNotLimited() {
		String id = start();
		for (int i = 0; i < 3; i++) {
			insertJudged(client, null);
		}
		games.hint(client, id);
		games.giveUp(client, id);
		assertThat(judge.calls).hasValue(0);
	}

	@Test
	void budgetMath() {
		insertJudged(client, null, OffsetDateTime.now(), 100_000, 40_000, 1_000, "ok");
		// (100000 − 40000) × 0.25e-6 + 40000 × 0.025e-6 + 1000 × 2e-6
		assertThat(limiter.spentTodayUsd()).isCloseTo(0.018, within(1e-9));
	}

	@Test
	void infoShowsBudgetExhausted() throws Exception {
		mvc.perform(get("/api/info")).andExpect(jsonPath("$.budgetExhausted").value(false));
		insertJudged(UUID.randomUUID().toString(), null, OffsetDateTime.now(), 100_000, 0, 0, "ok");
		mvc.perform(get("/api/info"))
				.andExpect(jsonPath("$.budgetExhausted").value(true))
				// 쓴 금액 자체는 공개하지 않는다.
				.andExpect(jsonPath("$.spentUsd").doesNotExist());
	}

	@Test
	// 경고 단계는 메모리에 남는다. 다른 테스트가 먼저 예산을 넘겼으면 이미 찍혀 있으므로 새 컨텍스트에서 본다.
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
	void warnsOncePerThreshold(CapturedOutput output) {
		// 0.0085달러 = 예산(0.01)의 85%
		insertJudged(UUID.randomUUID().toString(), null, OffsetDateTime.now(), 34_000, 0, 0, "ok");
		String id = start();
		games.ask(client, id, "하나");
		games.ask(client, id, "둘");
		String out = output.getOut();
		assertThat(out.split("판정 비용 80%", -1)).hasSize(2);
		assertThat(out).contains("판정 비용 50%");
	}

	@Test
	void inFlightCheckedBeforeLimits() {
		// 앞 판정이 진행 중이면, 그사이 한도가 찼어도 뒤 요청은 409(판정 중)여야 한다. 한도 검사·limited 기록보다 먼저다.
		String id = start();
		insertJudged(client, null);
		insertJudged(client, null);
		judge.duringSlow = () -> {
			insertJudged(client, null);
			assertThatThrownBy(() -> games.ask(client, id, "끼어든 질문"))
					.isInstanceOf(IllegalStateException.class).hasMessage("이전 판정을 기다리는 중입니다.");
		};
		try {
			games.ask(client, id, "느린 질문");
		} finally {
			judge.duringSlow = () -> {
			};
		}
		assertThat(limited(client)).isZero();
	}

	@Test
	void serverWideInFlightCap() {
		// 서버 전체 동시 판정 상한(이 테스트는 1). 다른 판·다른 기기라도 넘으면 503으로 거절하고 판정기를 부르지 않는다.
		String id = start();
		String other = UUID.randomUUID().toString();
		String otherGame = games.start(other, "boys-order").game().id();
		judge.duringSlow = () -> assertThatThrownBy(() -> games.ask(other, otherGame, "다른 판"))
				.isInstanceOf(com.seaturtle.office.limit.JudgeBusyException.class);
		try {
			games.ask(client, id, "느린 질문");
		} finally {
			judge.duringSlow = () -> {
			};
		}
		assertThat(judge.calls).hasValue(1);
		// 자리가 비면 다시 된다.
		games.ask(other, otherGame, "이제");
		assertThat(judge.calls).hasValue(2);
	}
}
