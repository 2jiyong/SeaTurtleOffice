package com.seaturtle.office.llm;

import java.util.Map;

/**
 * @param system     세계관과 시나리오처럼 게임 동안 바뀌지 않는 앞부분(공급자 프롬프트 캐시에 유리하다)
 * @param user       이번 요청
 * @param schemaName 출력 스키마 이름
 * @param schema     JSON 스키마(strict 모드 규칙: 모든 속성 required, additionalProperties=false)
 */
public record LlmPrompt(String system, String user, String schemaName, Map<String, Object> schema) {
}
