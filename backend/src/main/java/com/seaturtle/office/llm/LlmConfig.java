package com.seaturtle.office.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class LlmConfig {

	private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

	@Bean
	LlmClient llmClient(LlmProperties props, RestClient.Builder builder) {
		String provider = props.provider() == null ? "auto" : props.provider().toLowerCase();
		boolean hasKey = props.openai().apiKey() != null && !props.openai().apiKey().isBlank();
		if (provider.equals("auto")) {
			provider = hasKey ? "openai" : "fake";
		}
		return switch (provider) {
			case "openai" -> {
				if (!hasKey) {
					throw new IllegalStateException(
							"OPENAI_API_KEY가 없습니다. 키 없이 화면만 보려면 LLM_PROVIDER=fake로 실행하세요.");
				}
				log.info("LLM: OpenAI {} ({})", props.openai().model(), props.openai().baseUrl());
				yield new OpenAiLlmClient(builder, props.openai());
			}
			case "fake" -> {
				log.warn("LLM: 가짜 판정기를 씁니다. 모든 질문에 '상관없음'으로 답합니다.");
				yield new FakeLlmClient();
			}
			default -> throw new IllegalStateException("알 수 없는 llm.provider: " + props.provider());
		};
	}
}
