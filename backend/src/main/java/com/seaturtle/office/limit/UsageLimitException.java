package com.seaturtle.office.limit;

/** 오늘 판정 한도를 넘었다(429). reason: budget / device / ip. */
public class UsageLimitException extends RuntimeException {

	private final String reason;

	public UsageLimitException(String reason) {
		super("budget".equals(reason)
				? "오늘 응시자와의 교신량이 모두 찼습니다. 내일 다시 시도해 주세요."
				: "오늘 이 단말의 교신 한도를 넘었습니다. 내일 다시 시도해 주세요.");
		this.reason = reason;
	}

	public String reason() {
		return reason;
	}
}
