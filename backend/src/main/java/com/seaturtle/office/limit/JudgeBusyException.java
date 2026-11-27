package com.seaturtle.office.limit;

/** 서버 전체에서 동시에 판정 중인 건수가 상한에 닿았다(503). 한도 직전에 몰려 예산을 넘기는 것을 막는다. */
public class JudgeBusyException extends RuntimeException {

	public JudgeBusyException() {
		super("응시자가 다른 교신으로 바쁩니다. 잠시 뒤 다시 시도해 주세요.");
	}
}
