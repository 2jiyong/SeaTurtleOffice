package com.seaturtle.office;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.seaturtle.office.judge.AiJudgeClient;
import com.seaturtle.office.judge.JudgeProperties;
import com.seaturtle.office.log.RequestIdFilter;

/** 백엔드 요청 ID가 AI 서버 요청 헤더로 넘어간다. */
class AiJudgeClientHeaderTests {

	RestClient.Builder builder = RestClient.builder();
	MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	AiJudgeClient client = new AiJudgeClient(builder, new JudgeProperties("http://ai", Duration.ofSeconds(28)), null);

	@AfterEach
	void clear() {
		MDC.clear();
	}

	@Test
	void forwardsRequestId() {
		MDC.put(RequestIdFilter.MDC_KEY, "req-12345678");
		server.expect(requestTo("http://ai/judge/question"))
				.andExpect(header(RequestIdFilter.HEADER, "req-12345678"))
				.andRespond(withSuccess("{\"answer\":\"YES\"}", MediaType.APPLICATION_JSON));
		client.question("boys-order", List.of(), "q");
		server.verify();
	}

	@Test
	void noHeaderWithoutRequestId() {
		server.expect(requestTo("http://ai/judge/question"))
				.andExpect(headerDoesNotExist(RequestIdFilter.HEADER))
				.andRespond(withSuccess("{\"answer\":\"YES\"}", MediaType.APPLICATION_JSON));
		client.question("boys-order", List.of(), "q");
		server.verify();
	}
}
