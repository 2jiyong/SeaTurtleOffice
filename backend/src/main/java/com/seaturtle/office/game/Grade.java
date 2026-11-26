package com.seaturtle.office.game;

/**
 * 끝난 판의 등급.
 * 해결한 판은 보정 사용(사용 − 맞힌 보너스 × 5)으로 S~C를 매긴다.
 * 못 푼 판(실패·포기)은 사용 횟수와 상관없이, 핵심을 하나라도 밝혔으면 D, 하나도 못 밝혔으면 F다.
 */
public enum Grade {
	S, A, B, C, D, F;

	/** 보정 사용이 이 값 이하이면 그 등급이다(S 10, A 20, B 30). 그 위는 C. */
	public static final int S_MAX = 10;
	public static final int A_MAX = 20;
	public static final int B_MAX = 30;

	static Grade solved(int adjustedUsed) {
		if (adjustedUsed <= S_MAX) {
			return S;
		}
		if (adjustedUsed <= A_MAX) {
			return A;
		}
		return adjustedUsed <= B_MAX ? B : C;
	}

	static Grade unsolved(int keywordsFound) {
		return keywordsFound > 0 ? D : F;
	}
}
