package com.seaturtle.office.content;

/**
 * 정답 조건 하나. 백엔드는 화면에 쓸 것만 읽는다(판정 기준인 필수 용어·인정·불인정은 AI 서버가 읽는다).
 * 진상 공개 때 이름과 설명을 보여 준다.
 */
public record Keyword(String name, String description) {
}
