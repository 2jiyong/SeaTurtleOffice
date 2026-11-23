package com.seaturtle.office.judge;

/** AI 서버에서 판정을 받지 못했다. */
public class JudgeException extends RuntimeException {

	public JudgeException(String message, Throwable cause) {
		super(message, cause);
	}

	public JudgeException(String message) {
		super(message);
	}
}
