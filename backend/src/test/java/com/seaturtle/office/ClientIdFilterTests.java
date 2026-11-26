package com.seaturtle.office;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.seaturtle.office.game.ClientIdFilter;
import com.seaturtle.office.game.GameProperties;

import jakarta.servlet.http.Cookie;

/** 기기 ID 쿠키(sto_client) */
class ClientIdFilterTests {

	MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/games");
	MockHttpServletResponse response = new MockHttpServletResponse();

	void run(boolean secure) throws Exception {
		new ClientIdFilter(new GameProperties(secure)).doFilter(request, response, new MockFilterChain());
	}

	String attribute() {
		return (String) request.getAttribute(ClientIdFilter.ATTRIBUTE);
	}

	@Test
	void issuesCookieWhenMissing() throws Exception {
		run(false);
		String header = response.getHeader("Set-Cookie");
		String id = attribute();
		assertThat(UUID.fromString(id).toString()).isEqualTo(id);
		assertThat(header).startsWith("sto_client=" + id + ";")
				.contains("HttpOnly", "SameSite=Lax", "Path=/", "Max-Age=31536000")
				.doesNotContain("Secure");
	}

	@Test
	void keepsValidCookieAndRefreshes() throws Exception {
		String id = "1b4e28ba-2fa1-11d2-883f-0016d3cca427";
		request.setCookies(new Cookie(ClientIdFilter.COOKIE, id));
		run(false);
		assertThat(attribute()).isEqualTo(id);
		assertThat(response.getHeader("Set-Cookie")).startsWith("sto_client=" + id + ";").contains("Max-Age=31536000");
	}

	@Test
	void replacesInvalidCookie() throws Exception {
		request.setCookies(new Cookie(ClientIdFilter.COOKIE, "hello"));
		run(false);
		assertThat(attribute()).isNotEqualTo("hello");
		assertThat(UUID.fromString(attribute()).toString()).isEqualTo(attribute());
	}

	@Test
	void replacesNonCanonicalCookie() throws Exception {
		// UUID.fromString은 "1-1-1-1-1"도 받아 준다. 표준형이 아니면 새로 준다.
		request.setCookies(new Cookie(ClientIdFilter.COOKIE, "1-1-1-1-1"));
		run(false);
		assertThat(attribute()).isNotEqualTo("1-1-1-1-1");
	}

	@Test
	void secureWhenConfigured() throws Exception {
		run(true);
		assertThat(response.getHeader("Set-Cookie")).contains("Secure");
	}

	@Test
	void skipsNonApi() throws Exception {
		request = new MockHttpServletRequest("GET", "/index.html");
		run(false);
		assertThat(response.getHeader("Set-Cookie")).isNull();
		assertThat(attribute()).isNull();
	}

	@Test
	void skipsApiThatDoesNotNeedDevice() throws Exception {
		// 첫 화면의 /info, /scenarios가 늦게 와서 쿠키를 덮어쓰지 않게 /api/games에서만 준다.
		for (String uri : List.of("/api/info", "/api/scenarios", "/api/scenarios/boys-order/images/cover", "/api/gamesx")) {
			request = new MockHttpServletRequest("GET", uri);
			response = new MockHttpServletResponse();
			run(false);
			assertThat(response.getHeader("Set-Cookie")).as(uri).isNull();
		}
	}

	@Test
	void coversGamePaths() throws Exception {
		for (String uri : List.of("/api/games", "/api/games/abc/questions")) {
			request = new MockHttpServletRequest("POST", uri);
			response = new MockHttpServletResponse();
			run(false);
			assertThat(response.getHeader("Set-Cookie")).as(uri).startsWith("sto_client=");
		}
	}
}
