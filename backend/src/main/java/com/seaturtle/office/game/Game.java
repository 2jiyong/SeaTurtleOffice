package com.seaturtle.office.game;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.seaturtle.office.content.Scenario;

/** 한 판의 진행 상태. 메모리에만 있다. */
public class Game {

	public record QuestionRecord(int no, int seq, String text, Answer answer, boolean key) {
	}

	public record GuessRecord(int seq, String text, int matched, int total, boolean correct) {
	}

	private final String id;
	private final Scenario scenario;
	private final List<QuestionRecord> questions = new ArrayList<>();
	private final List<GuessRecord> guesses = new ArrayList<>();
	private GameStatus status = GameStatus.PLAYING;
	private int hintsUsed;
	/** 질문과 답안을 한 줄로 늘어놓기 위한 순번 */
	private int seq;
	private volatile Instant lastActive = Instant.now();

	public Game(String id, Scenario scenario) {
		this.id = id;
		this.scenario = scenario;
	}

	public String id() {
		return id;
	}

	public Scenario scenario() {
		return scenario;
	}

	public Instant lastActive() {
		return lastActive;
	}

	public void touch() {
		lastActive = Instant.now();
	}

	public synchronized GameStatus status() {
		return status;
	}

	public synchronized int hintsUsed() {
		return hintsUsed;
	}

	public synchronized List<QuestionRecord> questions() {
		return List.copyOf(questions);
	}

	public synchronized List<GuessRecord> guesses() {
		return List.copyOf(guesses);
	}

	public synchronized void requirePlaying() {
		if (status != GameStatus.PLAYING) {
			throw new GameEndedException();
		}
	}

	public synchronized QuestionRecord addQuestion(String text, Answer answer, boolean key) {
		requirePlaying();
		var record = new QuestionRecord(questions.size() + 1, ++seq, text, answer, key);
		questions.add(record);
		return record;
	}

	public synchronized GuessRecord addGuess(String text, int matched, int total) {
		requirePlaying();
		boolean correct = total > 0 && matched == total;
		var record = new GuessRecord(++seq, text, matched, total, correct);
		guesses.add(record);
		if (correct) {
			status = GameStatus.SOLVED;
		}
		return record;
	}

	public synchronized void useHint() {
		requirePlaying();
		if (hintsUsed >= scenario.hints().size()) {
			throw new IllegalStateException("남은 힌트가 없습니다");
		}
		hintsUsed++;
	}

	public synchronized void giveUp() {
		requirePlaying();
		status = GameStatus.GAVE_UP;
	}
}
