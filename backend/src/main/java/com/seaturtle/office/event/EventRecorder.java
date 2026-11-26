package com.seaturtle.office.event;

import java.time.OffsetDateTime;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.seaturtle.office.judge.Judge;
import com.seaturtle.office.log.RequestIdFilter;

import tools.jackson.databind.ObjectMapper;

/**
 * events에 한 줄 쓴다. 게임 요청을 깨뜨리지 않게, 실패하면 경고 로그만 남기고 넘어간다.
 * GameService에는 트랜잭션이 없으므로 INSERT는 바로 커밋된다(게임 저장의 성공·실패와 상관없이 남는다).
 */
@Component
public class EventRecorder {

	private static final Logger log = LoggerFactory.getLogger(EventRecorder.class);

	private final JdbcClient jdbc;
	private final ObjectMapper json;
	private final String appVersion;

	public EventRecorder(DataSource dataSource, ObjectMapper json, @Value("${APP_VERSION:dev}") String appVersion) {
		// events가 잠겨 있어도 게임 응답을 오래 붙잡지 않게 2초에서 포기한다(기록만 빠진다).
		var template = new JdbcTemplate(dataSource);
		template.setQueryTimeout(2);
		this.jdbc = JdbcClient.create(template);
		this.json = json;
		this.appVersion = appVersion;
	}

	public void record(Event e) {
		try {
			Judge.Metrics m = e.metrics();
			Integer aiMs = m == null ? null : m.aiMs();
			// 두 서버의 ms 반올림 차이로 음수가 되지 않게 0에서 멈춘다.
			Integer backendMs = e.totalMs() != null && aiMs != null ? Math.max(0, e.totalMs() - aiMs) : null;
			jdbc.sql("""
					insert into events (at, type, client_id, game_id, scenario, request_id, outcome, answer, used_after,
					                    text_length, total_ms, backend_ms, ai_ms, llm_ms, model, input_tokens,
					                    output_tokens, cached_tokens, app_version, detail, ip_hash)
					values (?, ?, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
					""")
					.params(OffsetDateTime.now(), e.type(), e.clientId(), e.gameId(), e.scenario(),
							MDC.get(RequestIdFilter.MDC_KEY), e.outcome(), e.answer(), e.usedAfter(), e.textLength(),
							e.totalMs(), backendMs, aiMs, m == null ? null : m.llmMs(), m == null ? null : m.model(),
							m == null ? null : m.inputTokens(), m == null ? null : m.outputTokens(),
							m == null ? null : m.cachedTokens(), appVersion,
							e.detail() == null ? null : json.writeValueAsString(e.detail()),
							MDC.get(RequestIdFilter.MDC_IP))
					.update();
		} catch (RuntimeException ex) {
			log.warn("이벤트 기록 실패: {}", e.type(), ex);
		}
	}
}
