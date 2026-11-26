package com.seaturtle.office.judge;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.seaturtle.office.log.RequestIdFilter;

/** 파이썬 AI 판정 서버(ai/)에 HTTP로 판정을 요청한다. */
@Component
public class AiJudgeClient implements Judge {

	private static final Logger log = LoggerFactory.getLogger(AiJudgeClient.class);

	record Health(String judge, boolean fake, int scenarios) {
	}

	private final RestClient http;

	@Autowired
	public AiJudgeClient(RestClient.Builder builder, JudgeProperties props) {
		this(builder, props, timeouts(props));
	}

	/** factory가 null이면 builder의 것을 그대로 쓴다(테스트에서 MockRestServiceServer를 묶을 때). */
	public AiJudgeClient(RestClient.Builder builder, JudgeProperties props, ClientHttpRequestFactory factory) {
		if (factory != null) {
			builder = builder.requestFactory(factory);
		}
		// 백엔드 요청 ID를 AI 서버로 넘겨 두 서버의 로그를 잇는다.
		this.http = builder.baseUrl(props.baseUrl())
				.requestInterceptor((request, body, execution) -> {
					String requestId = MDC.get(RequestIdFilter.MDC_KEY);
					if (requestId != null) {
						request.getHeaders().set(RequestIdFilter.HEADER, requestId);
					}
					return execution.execute(request, body);
				})
				.build();
		log.info("AI 판정 서버: {}", props.baseUrl());
	}

	private static ClientHttpRequestFactory timeouts(JudgeProperties props) {
		var factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(3_000);
		factory.setReadTimeout(props.timeout());
		return factory;
	}

	@Override
	public Verdict question(String scenario, List<HistoryEntry> history, String question) {
		return post("/judge/question",
				Map.of("scenario", scenario, "question", question, "history", history), Verdict.class);
	}

	@Override
	public GuessVerdict guess(String scenario, String guess) {
		return post("/judge/guess", Map.of("scenario", scenario, "guess", guess), GuessVerdict.class);
	}

	@Override
	public Status status() {
		try {
			Health health = http.get().uri("/health").retrieve().body(Health.class);
			if (health != null) {
				return new Status(health.judge(), health.fake(), true);
			}
		} catch (RestClientException e) {
			log.warn("AI 판정 서버에 연결할 수 없습니다: {}", e.getMessage());
		}
		return new Status("AI 서버 연결 안 됨", false, false);
	}

	private <T> T post(String path, Object body, Class<T> type) {
		T result;
		try {
			result = http.post().uri(path).body(body).retrieve().body(type);
		} catch (RestClientException e) {
			throw new JudgeException("AI 판정 서버 요청이 실패했습니다: " + e.getMessage(), e);
		}
		if (result == null) {
			throw new JudgeException("AI 판정 서버 응답이 비어 있습니다");
		}
		return result;
	}
}
