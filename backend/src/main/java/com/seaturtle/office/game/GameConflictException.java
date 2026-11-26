package com.seaturtle.office.game;

/** 읽은 뒤 저장하기 전에 다른 요청(다른 창)이 같은 판을 바꿨다. */
public class GameConflictException extends RuntimeException {

	public GameConflictException() {
		super("다른 창에서 진행이 바뀌었습니다. 새로고침해 주세요.");
	}
}
