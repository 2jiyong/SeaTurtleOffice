package com.seaturtle.office.llm;

/** API 키 없이 화면을 돌려 볼 때 쓰는 가짜 판정기. 항상 같은 답을 한다. */
public class FakeLlmClient implements LlmClient {

	@Override
	public String completeJson(LlmPrompt prompt) {
		return switch (prompt.schemaName()) {
			case "question_judgement" -> """
					{"reason":"가짜 판정기","answer":"IRRELEVANT","key":false}""";
			case "guess_judgement" -> """
					{"reason":"가짜 판정기","matched":[]}""";
			default -> throw new LlmException("알 수 없는 스키마: " + prompt.schemaName());
		};
	}

	@Override
	public String describe() {
		return "가짜 판정기";
	}

	@Override
	public boolean fake() {
		return true;
	}
}
