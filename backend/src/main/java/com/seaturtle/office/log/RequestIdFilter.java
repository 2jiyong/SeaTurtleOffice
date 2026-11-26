package com.seaturtle.office.log;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * /api 요청마다 요청 ID를 정해 MDC(로그), 응답 헤더, AI 서버 요청 헤더에 싣는다. 두 서버 로그와 events를 이 값으로 잇는다.
 * 들어온 X-Request-Id가 영숫자와 -로 된 8~64자면 그대로 쓰고(앞단 프록시가 붙인 값), 아니면 새 UUID.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	private final IpHasher ipHasher;

	public RequestIdFilter(IpHasher ipHasher) {
		this.ipHasher = ipHasher;
	}

	public static final String HEADER = "X-Request-Id";
	public static final String MDC_KEY = "requestId";
	/** 요청한 IP의 해시(IpHasher). 원문 IP는 MDC에도 넣지 않는다. */
	public static final String MDC_IP = "clientIpHash";
	private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String sent = request.getHeader(HEADER);
		String id = sent != null && VALID.matcher(sent).matches() ? sent : UUID.randomUUID().toString();
		MDC.put(MDC_KEY, id);
		MDC.put(MDC_IP, ipHasher.hash(request.getRemoteAddr()));
		response.setHeader(HEADER, id);
		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove(MDC_KEY);
			MDC.remove(MDC_IP);
		}
	}
}
