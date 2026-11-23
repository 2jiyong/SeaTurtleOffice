package com.seaturtle.office.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.JsonNode;

/** OpenAI Chat Completions API(호환 서버 포함)의 structured outputs를 쓴다. */
public class OpenAiLlmClient implements LlmClient {

	private final RestClient http;
	private final LlmProperties.OpenAi props;

	public OpenAiLlmClient(RestClient.Builder builder, LlmProperties.OpenAi props) {
		this.props = props;
		var factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(10_000);
		factory.setReadTimeout(props.timeout());
		this.http = builder
				.baseUrl(props.baseUrl())
				.defaultHeader("Authorization", "Bearer " + props.apiKey())
				.requestFactory(factory)
				.build();
	}

	@Override
	public String completeJson(LlmPrompt prompt) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", props.model());
		body.put("messages", List.of(
				Map.of("role", "system", "content", prompt.system()),
				Map.of("role", "user", "content", prompt.user())));
		body.put("response_format", Map.of(
				"type", "json_schema",
				"json_schema", Map.of("name", prompt.schemaName(), "strict", true, "schema", prompt.schema())));
		if (props.reasoningEffort() != null && !props.reasoningEffort().isBlank()) {
			body.put("reasoning_effort", props.reasoningEffort());
		}

		JsonNode response;
		try {
			response = http.post().uri("/chat/completions").body(body).retrieve().body(JsonNode.class);
		} catch (RestClientException e) {
			throw new LlmException("OpenAI 요청이 실패했습니다: " + e.getMessage(), e);
		}
		if (response == null) {
			throw new LlmException("OpenAI 응답이 비어 있습니다");
		}
		JsonNode message = response.path("choices").path(0).path("message");
		if (message.hasNonNull("refusal")) {
			throw new LlmException("모델이 응답을 거부했습니다: " + message.get("refusal").asString());
		}
		String content = message.path("content").asString(null);
		if (content == null || content.isBlank()) {
			throw new LlmException("OpenAI 응답에 내용이 없습니다");
		}
		return content;
	}

	@Override
	public String describe() {
		return "OpenAI " + props.model();
	}
}
