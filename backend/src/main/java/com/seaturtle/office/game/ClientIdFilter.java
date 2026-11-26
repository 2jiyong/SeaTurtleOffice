package com.seaturtle.office.game;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 로그인 대신 브라우저마다 기기 ID(UUID)를 쿠키로 준다. 기기가 필요한 /api/games 요청에만 동작한다.
 * 첫 화면에서 /api/info 같은 요청이 함께 쿠키를 주면, 늦게 온 응답이 판을 만든 뒤의 쿠키를 덮어쓸 수 있다.
 * 쿠키가 없거나 UUID 표준형이 아니면 새로 만들고, 있으면 만료를 1년으로 다시 늘린다.
 * 컨트롤러는 @RequestAttribute(ClientIdFilter.ATTRIBUTE)로 받는다.
 */
@Component
public class ClientIdFilter extends OncePerRequestFilter {

	public static final String COOKIE = "sto_client";
	public static final String ATTRIBUTE = "clientId";
	private static final Duration MAX_AGE = Duration.ofDays(365);

	private final boolean secure;

	public ClientIdFilter(GameProperties props) {
		this.secure = props.cookieSecure();
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return !(uri.equals("/api/games") || uri.startsWith("/api/games/"));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String id = existing(request).orElseGet(() -> UUID.randomUUID().toString());
		request.setAttribute(ATTRIBUTE, id);
		ResponseCookie cookie = ResponseCookie.from(COOKIE, id)
				.httpOnly(true)
				.secure(secure)
				.sameSite("Lax")
				.path("/")
				.maxAge(MAX_AGE)
				.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
		chain.doFilter(request, response);
	}

	private static Optional<String> existing(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return Optional.empty();
		}
		for (Cookie c : cookies) {
			if (COOKIE.equals(c.getName()) && canonicalUuid(c.getValue())) {
				return Optional.of(c.getValue());
			}
		}
		return Optional.empty();
	}

	private static boolean canonicalUuid(String value) {
		try {
			return UUID.fromString(value).toString().equals(value);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
