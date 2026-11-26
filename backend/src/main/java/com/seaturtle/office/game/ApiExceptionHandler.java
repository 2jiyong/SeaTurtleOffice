package com.seaturtle.office.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.seaturtle.office.judge.JudgeException;

@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(GameNotFoundException.class)
	ProblemDetail notFound(GameNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler(GameConflictException.class)
	ProblemDetail stale(GameConflictException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	@ExceptionHandler({ GameEndedException.class, IllegalStateException.class })
	ProblemDetail conflict(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	@ExceptionHandler(JudgeException.class)
	ProblemDetail judge(JudgeException e) {
		log.error("판정 실패", e);
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "판정 중 문제가 생겼습니다. 잠시 뒤 다시 시도해 주세요.");
	}
}
