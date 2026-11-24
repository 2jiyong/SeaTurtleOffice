package com.seaturtle.office.game;

/**
 * 추리가 진상에 얼마나 가까운지. 화면은 이 값만 보고 응시자의 한마디를 고른다.
 * 맞힌 개수와 빠진 항목은 알려 주지 않는다(너무 쉬워지므로).
 */
public enum Closeness {
	/** 핵심을 모두 맞혔다 */
	CORRECT,
	/** 핵심 하나가 빠졌다 */
	ONE_SHORT,
	/** 핵심 둘이 빠졌다 */
	TWO_SHORT,
	/** 핵심 셋 이상이 빠졌다 */
	FAR;

	public static Closeness of(int missing) {
		return switch (missing) {
			case 0 -> CORRECT;
			case 1 -> ONE_SHORT;
			case 2 -> TWO_SHORT;
			default -> FAR;
		};
	}
}
