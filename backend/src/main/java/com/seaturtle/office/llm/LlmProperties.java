package com.seaturtle.office.llm;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("llm")
public record LlmProperties(String provider, OpenAi openai) {

	public record OpenAi(String apiKey, String baseUrl, String model, String reasoningEffort, Duration timeout) {
	}
}
