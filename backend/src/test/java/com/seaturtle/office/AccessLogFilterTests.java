package com.seaturtle.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.seaturtle.office.log.AccessLogFilter;

/** 처리되지 않은 예외가 컨트롤러 밖으로 나가면, 응답이 아직 200이어도 500으로 찍는다. */
@ExtendWith(OutputCaptureExtension.class)
class AccessLogFilterTests {

	@Test
	void unhandledExceptionIsLoggedAs500(CapturedOutput output) {
		var request = new MockHttpServletRequest("POST", "/api/games/x/questions");
		var response = new MockHttpServletResponse();
		assertThatThrownBy(() -> new AccessLogFilter().doFilter(request, response, (req, res) -> {
			throw new IllegalStateException("터짐");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(output.getOut()).contains("POST /api/games/x/questions 500");
	}
}
