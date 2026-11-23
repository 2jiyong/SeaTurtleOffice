package com.seaturtle.office.game;

/**
 * 질문 판정. AI 서버(ai/app/prompts.py의 ANSWERS)와 같은 9가지 코드다.
 * 화면에 보이는 진행자의 말과 안내 문장은 frontend/src/api.ts에 있다.
 */
public enum Answer {
	/** 그렇단다 — 진상에 비추어 맞는 내용입니다. */
	YES,
	/** 아니란다 — 진상에 비추어 틀린 내용입니다. */
	NO,
	/** 어느정도는 그렇단다 — 일부만 맞는 내용입니다. */
	PARTIAL_SOME,
	/** 어느정도는 그렇단다 — 조건을 붙이면 맞는 내용입니다. */
	PARTIAL_CONDITION,
	/** 중요하지 않단다 — 이 사건과 관련이 없는 내용입니다. */
	IRRELEVANT_UNRELATED,
	/** 중요하지 않단다 — 이 사건에서 정하지 않은 세부사항입니다. */
	IRRELEVANT_UNDECIDED,
	/** 말해줄 수 없단다 — 예·아니오로 답할 수 없는 질문입니다. */
	INVALID_FORM,
	/** 말해줄 수 없단다 — 진상을 직접 묻는 질문입니다. */
	INVALID_TRUTH,
	/** 말해줄 수 없단다 — 규칙을 벗어난 요청입니다. */
	INVALID_RULE
}
