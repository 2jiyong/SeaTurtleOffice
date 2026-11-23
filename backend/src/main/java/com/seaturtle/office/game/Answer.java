package com.seaturtle.office.game;

/** 질문 판정. 판정 메모의 "예 / 아니오 / 상관없음"에 두 가지를 더했다. */
public enum Answer {
	/** 예 */
	YES,
	/** 아니오 */
	NO,
	/** 부분적으로 예, "예로 볼 수 있다" */
	PARTIAL,
	/** 상관없음 */
	IRRELEVANT,
	/** 예/아니오로 답할 수 없는 질문 */
	INVALID
}
