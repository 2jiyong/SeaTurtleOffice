package com.seaturtle.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.seaturtle.office.game.Answer;
import com.seaturtle.office.judge.AiJudgeClient;
import com.seaturtle.office.judge.JudgeException;
import com.seaturtle.office.judge.JudgeProperties;

/** AI 서버 응답의 판정 지표(metrics)를 읽는다. 실패와 시간 초과도 구분한다. */
class AiJudgeClientTests {

	static final String METRICS = "{\"model\":\"gpt-5-mini\",\"aiMs\":4085,\"llmMs\":4070,"
			+ "\"inputTokens\":5210,\"outputTokens\":38,\"cachedTokens\":4096}";

	RestClient.Builder builder = RestClient.builder();
	MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	AiJudgeClient client = new AiJudgeClient(builder, new JudgeProperties("http://ai", Duration.ofSeconds(28)), null);

	JudgeException failure() {
		return catchThrowableOfType(JudgeException.class, () -> client.question("s", List.of(), "q"));
	}

	@Test
	void readsMetricsOnSuccess() {
		server.expect(requestTo("http://ai/judge/question"))
				.andRespond(withSuccess("{\"answer\":\"YES\",\"metrics\":" + METRICS + "}", MediaType.APPLICATION_JSON));
		var verdict = client.question("s", List.of(), "q");
		assertThat(verdict.answer()).isEqualTo(Answer.YES);
		assertThat(verdict.metrics().model()).isEqualTo("gpt-5-mini");
		assertThat(verdict.metrics().inputTokens()).isEqualTo(5210);
		assertThat(verdict.metrics().aiMs()).isEqualTo(4085);
		assertThat(verdict.metrics().cachedTokens()).isEqualTo(4096);
	}

	@Test
	void guessReadsMetrics() {
		server.expect(requestTo("http://ai/judge/guess")).andRespond(withSuccess(
				"{\"matched\":[1],\"total\":3,\"bonus\":[],\"bonusTotal\":1,\"story\":true,\"metrics\":" + METRICS + "}",
				MediaType.APPLICATION_JSON));
		assertThat(client.guess("s", "g").metrics().llmMs()).isEqualTo(4070);
	}

	@Test
	void sendsHistoryPropositionAndReadsPropositionAndReason() {
		server.expect(requestTo("http://ai/judge/question"))
				.andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath(
						"$.history[0].proposition").value("앞 명제"))
				.andRespond(withSuccess("{\"answer\":\"NO\",\"proposition\":\"명제\",\"reason\":\"근거\"}",
						MediaType.APPLICATION_JSON));
		var verdict = client.question("s",
				List.of(new com.seaturtle.office.judge.Judge.HistoryEntry("앞 질문", Answer.YES, "앞 명제")), "q");
		assertThat(verdict.proposition()).isEqualTo("명제");
		assertThat(verdict.reason()).isEqualTo("근거");
	}

	@Test
	void guessReadsReason() {
		server.expect(requestTo("http://ai/judge/guess")).andRespond(withSuccess(
				"{\"matched\":[1],\"total\":3,\"bonus\":[],\"bonusTotal\":1,\"story\":true,\"reason\":\"채점\"}",
				MediaType.APPLICATION_JSON));
		assertThat(client.guess("s", "g").reason()).isEqualTo("채점");
	}

	@Test
	void oldServerWithoutMetrics() {
		server.expect(requestTo("http://ai/judge/question"))
				.andRespond(withSuccess("{\"answer\":\"YES\"}", MediaType.APPLICATION_JSON));
		var verdict = client.question("s", List.of(), "q");
		assertThat(verdict.answer()).isEqualTo(Answer.YES);
		assertThat(verdict.metrics()).isNull();
	}

	@Test
	void errorBodyMetricsReachException() {
		server.expect(requestTo("http://ai/judge/question")).andRespond(withStatus(HttpStatus.BAD_GATEWAY)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"detail\":\"x\",\"metrics\":{\"model\":\"m\",\"aiMs\":10,\"llmMs\":9,\"inputTokens\":100,"
						+ "\"outputTokens\":0,\"cachedTokens\":0}}"));
		JudgeException e = failure();
		assertThat(e.metrics().inputTokens()).isEqualTo(100);
		assertThat(e.timeout()).isFalse();
	}

	@Test
	void errorBodyNotJson() {
		server.expect(requestTo("http://ai/judge/question"))
				.andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("oops"));
		JudgeException e = failure();
		assertThat(e).isNotNull();
		assertThat(e.metrics()).isNull();
	}

	@Test
	void timeoutIsMarked() {
		server.expect(requestTo("http://ai/judge/question"))
				.andRespond(withException(new SocketTimeoutException("Read timed out")));
		JudgeException e = failure();
		assertThat(e.timeout()).isTrue();
		assertThat(e.metrics()).isNull();
	}

	@Test
	void connectTimeoutIsNotTimeout() {
		// 연결조차 못 했다(3초). "AI가 28초 안에 답하지 않음"과 섞지 않는다.
		server.expect(requestTo("http://ai/judge/question"))
				.andRespond(withException(new SocketTimeoutException("Connect timed out")));
		assertThat(failure().timeout()).isFalse();
	}

	@Test
	void missingAnswerKeepsMetrics() {
		server.expect(requestTo("http://ai/judge/question"))
				.andRespond(withSuccess("{\"metrics\":" + METRICS + "}", MediaType.APPLICATION_JSON));
		JudgeException e = failure();
		assertThat(e).isNotNull();
		assertThat(e.metrics().inputTokens()).isEqualTo(5210);
	}

	@Test
	void nullGuessListsKeepMetrics() {
		server.expect(requestTo("http://ai/judge/guess")).andRespond(withSuccess(
				"{\"total\":3,\"bonusTotal\":1,\"story\":true,\"metrics\":" + METRICS + "}", MediaType.APPLICATION_JSON));
		JudgeException e = catchThrowableOfType(JudgeException.class, () -> client.guess("s", "g"));
		assertThat(e).isNotNull();
		assertThat(e.metrics().inputTokens()).isEqualTo(5210);
	}
}
