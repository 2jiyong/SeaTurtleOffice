package com.seaturtle.office.game;

public class GameEndedException extends RuntimeException {

	public GameEndedException() {
		super("이미 끝난 게임입니다");
	}
}
