package com.seaturtle.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.seaturtle.office.log.RequestIdFilter;

/** 요청 ID(X-Request-Id)와 접근 로그 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class RequestIdTests {

	@Autowired
	MockMvc mvc;

	static boolean isUuid(String value) {
		try {
			return UUID.fromString(value).toString().equals(value);
		} catch (IllegalArgumentException | NullPointerException e) {
			return false;
		}
	}

	String responseId(String sent) throws Exception {
		var request = get("/api/scenarios");
		if (sent != null) {
			request.header(RequestIdFilter.HEADER, sent);
		}
		return mvc.perform(request).andReturn().getResponse().getHeader(RequestIdFilter.HEADER);
	}

	@Test
	void echoesValidRequestId() throws Exception {
		assertThat(responseId("abcd-1234-efgh")).isEqualTo("abcd-1234-efgh");
	}

	@Test
	void replacesBadRequestId() throws Exception {
		for (String bad : new String[] { "bad id", "x", "a".repeat(65), "abc_defgh!" }) {
			String id = responseId(bad);
			assertThat(id).as(bad).isNotEqualTo(bad);
			assertThat(isUuid(id)).as(bad).isTrue();
		}
	}

	@Test
	void generatesWhenMissing() throws Exception {
		assertThat(isUuid(responseId(null))).isTrue();
	}

	@Test
	void accessLogNormalizesId(CapturedOutput output) throws Exception {
		String gameId = "1b4e28ba-2fa1-11d2-883f-0016d3cca427";
		mvc.perform(get("/api/games/" + gameId).header(RequestIdFilter.HEADER, "log-test-0001"));
		assertThat(output.getOut()).contains("GET /api/games/{id} 404").contains("log-test-0001");
		assertThat(output.getOut()).doesNotContain(gameId);
	}
}
