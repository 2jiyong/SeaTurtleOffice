package com.seaturtle.office;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.seaturtle.office.content.ContentRepository;
import com.seaturtle.office.content.Scenario;

/** 세계관 문서와 uses 검사는 AI 서버 테스트(ai/tests)에서 한다. */
@SpringBootTest
class ContentRepositoryTests {

	@Autowired
	ContentRepository content;

	@Test
	void everyScenarioIsComplete() {
		assertThat(content.scenarios()).isNotEmpty();
		for (Scenario s : content.scenarios()) {
			assertThat(s.title()).as(s.slug()).isNotBlank();
			assertThat(s.problem()).as(s.slug()).isNotBlank();
			assertThat(s.truth()).as(s.slug()).isNotBlank();
			assertThat(s.keywords()).as(s.slug() + " 핵심 키워드").isNotEmpty();
			assertThat(s.bonuses()).as(s.slug() + " 보너스 키워드").hasSizeBetween(1, 2);
			assertThat(s.hints()).as(s.slug() + " 힌트").isNotEmpty();
		}
	}

	@Test
	void readsKeywordsAndBonuses() {
		Scenario s = content.scenario("last-rent").orElseThrow();
		assertThat(s.keywords()).extracting(com.seaturtle.office.content.Keyword::name)
				.containsExactly("거주구역 등록 만료", "청소부", "건물주가 노림");
		assertThat(s.keywords().get(1).missing()).isEqualTo("건물이 어떻게 하룻밤 사이에 비었는지가 빠져 있구나.");
		assertThat(s.bonuses()).extracting(com.seaturtle.office.content.Bonus::name)
				.containsExactly("월세 체납", "증거가 남지 않음");
	}
}
