package com.seaturtle.office;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.seaturtle.office.judge.JudgeProperties;

/**
 * 타임아웃은 안쪽이 먼저 포기하게 맞춘다: OpenAI 최대 40초(20초 × 2번) < 백엔드 45초 < 화면 50초.
 * 백엔드가 AI 서버보다 먼저 포기하면 AI 서버가 받은 결과가 버려지고 비용만 나간다.
 */
@SpringBootTest
class JudgeTimeoutTests {

	@Autowired
	JudgeProperties props;

	@Test
	void backendWaitsLongerThanAiServerButLessThanBrowser() {
		assertThat(props.timeout()).isEqualTo(Duration.ofSeconds(45));
	}
}
