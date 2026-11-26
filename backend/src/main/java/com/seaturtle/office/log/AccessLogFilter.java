package com.seaturtle.office.log;

import java.io.IOException;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.seaturtle.office.game.ClientIdFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * /api 요청마다 한 줄: 메서드, 경로(게임 id는 {id}), 상태, 걸린 시간, 기기 ID. 배포에서는 JSON의 키로 나간다.
 * 요청 ID는 MDC로 함께 찍힌다(RequestIdFilter가 바깥에서 감싼다).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AccessLogFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger("access");
	private static final Pattern UUID_SEGMENT = Pattern
			.compile("(?<=/)[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?=/|$)");

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long started = System.nanoTime();
		boolean failed = true;
		try {
			chain.doFilter(request, response);
			failed = false;
		} finally {
			long ms = (System.nanoTime() - started) / 1_000_000;
			String path = normalize(request.getRequestURI());
			// 처리되지 않은 예외가 밖으로 나가면 응답은 아직 200일 수 있다. 컨테이너가 500으로 답하므로 500으로 찍는다.
			int status = failed && response.getStatus() < 400 ? 500 : response.getStatus();
			var event = log.atInfo()
					.addKeyValue("method", request.getMethod())
					.addKeyValue("path", path)
					.addKeyValue("status", status)
					.addKeyValue("ms", ms);
			Object clientId = request.getAttribute(ClientIdFilter.ATTRIBUTE);
			if (clientId != null) {
				event = event.addKeyValue("clientId", clientId);
			}
			event.log("{} {} {} {}ms", request.getMethod(), path, status, ms);
		}
	}

	static String normalize(String path) {
		return UUID_SEGMENT.matcher(path).replaceAll("{id}");
	}
}
