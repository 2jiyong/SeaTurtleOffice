package com.seaturtle.office.judge;

import java.net.SocketTimeoutException;
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
import org.springframework.web.client.RestClientResponseException;

import com.seaturtle.office.log.RequestIdFilter;

/** 파이썬 AI 판정 서버(ai/)에 HTTP로 판정을 요청한다. */
@Component
public class AiJudgeClient implements Judge {

	private static final Logger log = LoggerFactory.getLogger(AiJudgeClient.class);

	record Health(String judge, boolean fake, int scenarios) {
	}

	/** AI 서버의 502 본문 */
	record ErrorBody(String detail, Metrics metrics) {
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
		Verdict verdict = post("/judge/question",
				Map.of("scenario", scenario, "question", question, "history", history), Verdict.class);
		if (verdict.answer() == null) {
			// 토큰은 썼으므로 지표를 담아 실패로 돌린다.
			throw new JudgeException("AI 판정 서버 응답에 answer가 없습니다", verdict.metrics());
		}
		return verdict;
	}

	@Override
	public GuessVerdict guess(String scenario, String guess) {
		GuessVerdict verdict = post("/judge/guess", Map.of("scenario", scenario, "guess", guess), GuessVerdict.class);
		if (verdict.matched() == null || verdict.bonus() == null
				|| verdict.matched().contains(null) || verdict.bonus().contains(null)) {
			throw new JudgeException("AI 판정 서버 응답의 matched·bonus가 비었습니다", verdict.metrics());
		}
		return verdict;
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
		} catch (RestClientResponseException e) {
			// AI 서버가 오류로 답했다. 토큰을 쓴 뒤의 실패면 본문에 metrics가 있다.
			throw new JudgeException("AI 판정 서버 요청이 실패했습니다: " + e.getMessage(), e, errorMetrics(e), false);
		} catch (RestClientException e) {
			throw new JudgeException("AI 판정 서버 요청이 실패했습니다: " + e.getMessage(), e, null, isTimeout(e));
		}
		if (result == null) {
			throw new JudgeException("AI 판정 서버 응답이 비어 있습니다");
		}
		return result;
	}

	private static Metrics errorMetrics(RestClientResponseException e) {
		try {
			ErrorBody body = e.getResponseBodyAs(ErrorBody.class);
			return body == null ? null : body.metrics();
		} catch (RuntimeException notJson) {
			return null;
		}
	}

	private static boolean isTimeout(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof SocketTimeoutException timeout) {
				// 연결 단계(3초)의 시간 초과는 "AI가 28초 안에 답하지 않음"이 아니다. judge_error로 센다.
				String message = timeout.getMessage();
				return message == null || !message.toLowerCase(java.util.Locale.ROOT).contains("connect");
			}
		}
		return false;
	}
}
