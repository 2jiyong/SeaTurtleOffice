package com.seaturtle.office;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.seaturtle.office.content.ContentRepository;
import com.seaturtle.office.content.Scenario;

@SpringBootTest(properties = "llm.provider=fake")
class ContentRepositoryTests {

	@Autowired
	ContentRepository content;

	@Test
	void loadsBaseAndScenarios() {
		assertThat(content.base()).hasSize(10);
		assertThat(content.scenarios()).isNotEmpty();
	}

	@Test
	void everyScenarioIsComplete() {
		for (Scenario s : content.scenarios()) {
			assertThat(s.title()).as(s.slug()).isNotBlank();
			assertThat(s.problem()).as(s.slug()).isNotBlank();
			assertThat(s.truth()).as(s.slug()).isNotBlank();
			assertThat(s.keyPoints()).as(s.slug() + " 핵심 포인트").isNotEmpty();
			assertThat(s.hints()).as(s.slug() + " 힌트").isNotEmpty();
		}
	}

	@Test
	void everyUsesIdExistsInWorld() {
		assertThat(content.missingUses()).isEmpty();
	}
}
