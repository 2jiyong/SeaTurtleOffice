package com.seaturtle.office.content;

/**
 * 정답 조건 하나. 백엔드는 화면에 쓸 것만 읽는다(판정 기준인 필수 용어·인정·불인정은 AI 서버가 읽는다).
 *
 * @param missing 이 키워드를 못 맞혔을 때 진행자가 하는 말. 문제에 나온 내용만 담는다.
 */
public record Keyword(String name, String description, String missing) {
}
