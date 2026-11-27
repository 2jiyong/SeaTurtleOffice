package com.seaturtle.office.limit;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 판정 전에 한도를 검사한다. 기준은 events(2단계 기록)라 서버를 다시 켜도 오늘 쓴 양이 유지된다.
 * 검사 쿼리가 실패하면 판정을 막지 않는다(최종 안전망은 OpenAI 충전 잔액).
 */
@Component
public class UsageLimiter {

	private static final Logger log = LoggerFactory.getLogger(UsageLimiter.class);

	/** 한도를 세는 판정 이벤트. 한도에 막힌 것(limited)은 세지 않는다. */
	private static final String JUDGED = "type in ('question_asked', 'guess_submitted') and outcome <> 'limited' and at >= ?";

	/** try-with-resources로 쓰는 판정 자리. close는 예외를 던지지 않는다. */
	public interface Slot extends AutoCloseable {
		@Override
		void close();
	}

	private final JdbcClient jdbc;
	private final LimitProperties props;
	private final Set<String> judging = ConcurrentHashMap.newKeySet();

	public UsageLimiter(DataSource dataSource, LimitProperties props) {
		// events가 잠겨 있어도 판정을 오래 붙잡지 않게 2초에서 포기한다(검사 실패 = 통과).
		var template = new JdbcTemplate(dataSource);
		template.setQueryTimeout(2);
		this.jdbc = JdbcClient.create(template);
		this.props = props;
	}

	/** 한국 시간(설정 zone) 오늘 0시 */
	public OffsetDateTime todayStart() {
		return LocalDate.now(props.zone()).atStartOfDay(props.zone()).toOffsetDateTime();
	}

	/** 넘으면 UsageLimitException. 순서: 전체 예산 → 기기 → IP. ipHash가 없으면 IP 검사를 건너뛴다. */
	public void check(String clientId, String ipHash) {
		String reason;
		try {
			reason = exceeded(clientId, ipHash);
		} catch (RuntimeException e) {
			log.warn("한도 검사 실패, 판정을 그대로 진행합니다", e);
			return;
		}
		if (reason != null) {
			throw new UsageLimitException(reason);
		}
	}

	private String exceeded(String clientId, String ipHash) {
		if (props.dailyBudgetUsd() > 0 && spentTodayUsd() >= props.dailyBudgetUsd()) {
			return "budget";
		}
		OffsetDateTime today = todayStart();
		if (props.devicePerDay() > 0 && count("client_id = ?::uuid", clientId, today) >= props.devicePerDay()) {
			return "device";
		}
		if (props.ipPerDay() > 0 && ipHash != null && count("ip_hash = ?", ipHash, today) >= props.ipPerDay()) {
			return "ip";
		}
		return null;
	}

	private long count(String where, String value, OffsetDateTime today) {
		return jdbc.sql("select count(*) from events where " + where + " and " + JUDGED)
				.params(value, today).query(Long.class).single();
	}

	/** 오늘 판정에 쓴 비용(달러). 토큰 × 단가, null 토큰은 0. */
	public double spentTodayUsd() {
		LimitProperties.Price p = props.price();
		Double usd = jdbc.sql("""
				select coalesce(sum(
				  (coalesce(input_tokens, 0) - coalesce(cached_tokens, 0)) * ?
				  + coalesce(cached_tokens, 0) * ?
				  + coalesce(output_tokens, 0) * ?), 0) / 1000000.0
				from events where""" + " " + JUDGED)
				.params(p.input(), p.cachedInput(), p.output(), todayStart()).query(Double.class).single();
		return usd == null ? 0 : usd;
	}

	/** 이 판이 이미 판정 중이면 IllegalStateException(409). 끝나면(성공·실패 모두) close로 비운다. */
	public Slot enter(String gameId) {
		if (!judging.add(gameId)) {
			throw new IllegalStateException("이전 판정을 기다리는 중입니다.");
		}
		return () -> judging.remove(gameId);
	}
}
