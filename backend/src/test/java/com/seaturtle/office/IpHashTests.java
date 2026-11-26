package com.seaturtle.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.seaturtle.office.game.ClientIdFilter;
import com.seaturtle.office.log.IpHasher;

import jakarta.servlet.http.Cookie;

/** IP 원문 대신 비밀 값을 섞은 해시를 남긴다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IpHashTests {

	@Autowired
	IpHasher hasher;

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Test
	void hashIsStableAndNotRaw() {
		String a = hasher.hash("203.0.113.7");
		assertThat(a).hasSize(32).matches("[0-9a-f]{32}").isEqualTo(hasher.hash("203.0.113.7"));
		assertThat(a).doesNotContain("203");
		assertThat(hasher.hash("203.0.113.8")).isNotEqualTo(a);
		assertThat(hasher.hash(null)).isNull();
	}

	@Test
	void differentSecretDifferentHash() {
		assertThat(new IpHasher("other").hash("203.0.113.7")).isNotEqualTo(hasher.hash("203.0.113.7"));
	}

	@Test
	void eventStoresIpHash() throws Exception {
		String client = UUID.randomUUID().toString();
		mvc.perform(post("/api/games").cookie(new Cookie(ClientIdFilter.COOKIE, client))
				.with(r -> {
					r.setRemoteAddr("203.0.113.7");
					return r;
				})
				.contentType(APPLICATION_JSON).content("{\"scenario\":\"boys-order\"}"))
				.andExpect(status().isCreated());
		var rows = jdbc.sql("select * from events where client_id = ?::uuid and type = 'game_started'").param(client)
				.query().listOfRows();
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst().get("ip_hash")).isEqualTo(hasher.hash("203.0.113.7"));
		assertThat(rows.getFirst().values().toString()).doesNotContain("203.0.113.7");
	}
}
