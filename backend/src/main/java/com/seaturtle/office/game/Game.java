package com.seaturtle.office.game;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.seaturtle.office.content.Scenario;

/** 한 판의 진행 상태. 메모리에만 있다. */
public class Game {

	/** 한 판에 쓸 수 있는 사용 횟수. 질문 1, 추리 5. */
	public static final int LIMIT = 40;
	public static final int GUESS_COST = 5;
	/** 사용 10, 20, 30에서 힌트가 하나씩 열린다. */
	public static final int HINT_STEP = 10;
	/** 힌트는 한도 전까지만(10, 20, 30). 40에는 힌트가 없다. */
	public static final int MAX_HINTS = (LIMIT - 1) / HINT_STEP;

	public record QuestionRecord(int no, int seq, String text, Answer answer) {
	}

	/** 힌트가 열린 순간의 순번(seq)과 함께 남긴다. 화면은 이 순서대로 질문 사이에 끼워 넣는다. */
	public record HintRecord(int no, int seq, String text) {
	}

	/** 추리 하나. 얼마나 가까운지만 남긴다. 맞힌 개수, 빠진 항목, 보너스는 담지 않는다. */
	public record GuessRecord(int seq, String text, boolean correct, Closeness closeness) {
	}

	private final String id;
	private final Scenario scenario;
	private final List<QuestionRecord> questions = new ArrayList<>();
	private final List<GuessRecord> guesses = new ArrayList<>();
	private final List<HintRecord> hints = new ArrayList<>();
	/** 여러 추리에 걸쳐 맞힌 보너스 번호(1부터) */
	private final Set<Integer> bonusFound = new TreeSet<>();
	/** 여러 추리에 걸쳐 맞힌 핵심 키워드 번호(1부터). 게임이 끝나야 공개한다. */
	private final Set<Integer> keywordFound = new TreeSet<>();
	private GameStatus status = GameStatus.PLAYING;
	/** 사용 횟수. 열린 힌트 수와 점수는 모두 이 값에서 계산한다. */
	private int used;
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

	public synchronized int used() {
		return used;
	}

	/** 힌트가 열리는 사용 횟수 목록. 힌트가 2개면 [10, 20]. */
	public static List<Integer> hintStepsFor(int hintCount) {
		List<Integer> steps = new ArrayList<>();
		for (int i = 1; i <= Math.min(hintCount, MAX_HINTS); i++) {
			steps.add(i * HINT_STEP);
		}
		return List.copyOf(steps);
	}

	public List<Integer> hintSteps() {
		return hintStepsFor(scenario.hints().size());
	}

	/** 이 게임에서 쓸 수 있는 힌트 수(시나리오 힌트 수, 최대 MAX_HINTS) */
	private int hintCount() {
		return Math.min(scenario.hints().size(), MAX_HINTS);
	}

	public synchronized int hintsOpen() {
		return hints.size();
	}

	public synchronized List<HintRecord> hints() {
		return List.copyOf(hints);
	}

	/** 사용 횟수가 힌트 자리를 넘었으면, 아직 열리지 않은 힌트를 지금 순번으로 남긴다. */
	private void openHints() {
		int due = Math.min(hintCount(), used / HINT_STEP);
		while (hints.size() < due) {
			int no = hints.size() + 1;
			hints.add(new HintRecord(no, ++seq, scenario.hints().get(no - 1)));
		}
	}

	/** 힌트 받기를 누르면 올라갈 사용 횟수. 남은 힌트가 없으면 null. */
	public synchronized Integer nextHintAt() {
		int open = hintsOpen();
		return open < hintCount() ? (open + 1) * HINT_STEP : null;
	}

	/** 사용 횟수를 다 써서 마지막 추리 한 번만 남은 상태. */
	public synchronized boolean finalChance() {
		return status == GameStatus.PLAYING && used >= LIMIT;
	}

	/** 해결하면 100 − 사용 + 맞힌 보너스 × 10. 실패·포기·진행 중은 0. */
	public synchronized int score() {
		return status == GameStatus.SOLVED ? 100 - used + bonusPoints() : 0;
	}

	public synchronized int bonusPoints() {
		return status == GameStatus.SOLVED ? 10 * bonusFound.size() : 0;
	}

	/** 질문할 수 있는지 판정 전에 확인한다(한도를 넘으면 판정 비용을 쓰지 않는다). */
	public synchronized void requireQuestionAllowed() {
		requirePlaying();
		if (used >= LIMIT) {
			throw new IllegalStateException("질문을 모두 썼습니다. 마지막 추리만 남았습니다.");
		}
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

	public synchronized QuestionRecord addQuestion(String text, Answer answer) {
		requireQuestionAllowed();
		used++;
		var record = new QuestionRecord(questions.size() + 1, ++seq, text, answer);
		questions.add(record);
		openHints();
		return record;
	}

	public synchronized GuessRecord addGuess(String text, List<Integer> matched, List<Integer> bonus) {
		requirePlaying();
		boolean last = used >= LIMIT;
		int total = scenario.keywords().size();
		Set<Integer> hit = new TreeSet<>();
		matched.stream().filter(n -> n >= 1 && n <= total).forEach(hit::add);
		keywordFound.addAll(hit);
		bonus.stream().filter(n -> n >= 1 && n <= scenario.bonuses().size()).forEach(bonusFound::add);
		boolean correct = total > 0 && hit.size() == total;
		var closeness = correct ? Closeness.CORRECT : Closeness.of(Math.max(1, total - hit.size()));
		var record = new GuessRecord(++seq, text, correct, closeness);
		guesses.add(record);
		if (!last) {
			used = Math.min(LIMIT, used + GUESS_COST);
			openHints();
		}
		if (correct) {
			status = GameStatus.SOLVED;
		} else if (last) {
			// 마지막 추리까지 틀렸다.
			status = GameStatus.FAILED;
		}
		return record;
	}

	public synchronized void useHint() {
		requirePlaying();
		Integer next = nextHintAt();
		if (next == null) {
			throw new IllegalStateException("남은 힌트가 없습니다");
		}
		used = next;
		openHints();
	}

	public synchronized void giveUp() {
		requirePlaying();
		status = GameStatus.GAVE_UP;
	}

	public synchronized Set<Integer> keywordFound() {
		return Set.copyOf(keywordFound);
	}

	public synchronized Set<Integer> bonusFound() {
		return Set.copyOf(bonusFound);
	}
}
