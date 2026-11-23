package com.seaturtle.office.llm;

/**
 * 구조화 출력(JSON 스키마)을 지원하는 LLM. 공급자를 바꾸려면 이 인터페이스를 구현하고
 * {@link LlmConfig}에 등록한다.
 */
public interface LlmClient {

	/** 스키마에 맞는 JSON 문자열을 돌려준다. */
	String completeJson(LlmPrompt prompt);

	/** 화면에 보여 줄 판정기 이름. */
	String describe();

	/** 실제 판정을 하지 않는 가짜 판정기인가. */
	default boolean fake() {
		return false;
	}
}
