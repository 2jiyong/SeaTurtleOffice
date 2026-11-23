package com.seaturtle.office.game;

public class GameNotFoundException extends RuntimeException {

	public GameNotFoundException(String what) {
		super(what + "을(를) 찾을 수 없습니다");
	}
}
