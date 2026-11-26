package com.seaturtle.office.judge;

/**
 * AI 서버에서 판정을 받지 못했다. 토큰을 쓴 뒤 실패했으면 metrics에 그 비용이 있다(없으면 null).
 * timeout: AI 서버가 제한 시간 안에 답하지 않았다(이때는 비용을 알 수 없다).
 */
public class JudgeException extends RuntimeException {

	private final transient Judge.Metrics metrics;
	private final boolean timeout;

	public JudgeException(String message) {
		this(message, null, null, false);
	}

	public JudgeException(String message, Throwable cause) {
		this(message, cause, null, false);
	}

	public JudgeException(String message, Judge.Metrics metrics) {
		this(message, null, metrics, false);
	}

	public JudgeException(String message, Throwable cause, Judge.Metrics metrics, boolean timeout) {
		super(message, cause);
		this.metrics = metrics;
		this.timeout = timeout;
	}

	public Judge.Metrics metrics() {
		return metrics;
	}

	public boolean timeout() {
		return timeout;
	}
}
