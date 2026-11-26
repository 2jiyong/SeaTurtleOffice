package com.seaturtle.office.game;

import java.util.List;

import com.seaturtle.office.content.Scenario;
import com.seaturtle.office.content.ScenarioImages;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** API 요청과 응답. 진상, 핵심 키워드, 맞힌 보너스는 게임이 끝났을 때만 내보낸다. */
public final class GameDtos {

	private GameDtos() {
	}

	static final String NO_NUL = "(?s)[^\\x00]*";
	static final String MSG_NUL = "보낼 수 없는 글자가 들어 있습니다";

	public record StartRequest(@NotBlank String scenario) {
	}

	/** NUL(\\u0000)은 DB(text)에 저장할 수 없어 판정 전에 막는다. */
	public record QuestionRequest(@NotBlank @Size(max = 200) @Pattern(regexp = NO_NUL, message = MSG_NUL) String text) {
	}

	public record GuessRequest(@NotBlank @Size(max = 1000) @Pattern(regexp = NO_NUL, message = MSG_NUL) String text) {
	}

	/** cover: 대표 이미지 주소. 그림이 없으면 null. */
	/** keywordCount·bonusCount: 핵심·보너스 키워드의 개수만 알린다(내용은 게임이 끝나야 공개). */
	public record ScenarioSummary(String slug, String title, String difficulty, String problem, int hintCount,
			String cover, int keywordCount, int bonusCount) {

		static ScenarioSummary of(Scenario s, ScenarioImages images) {
			return new ScenarioSummary(s.slug(), s.title(), s.difficulty(), s.problem(), s.hints().size(),
					images.url(s.slug(), "cover"), s.keywords().size(), s.bonuses().size());
		}
	}

	/** 이 기기의 판 하나. 메인 페이지의 진행 상태 표시에 쓴다. grade는 끝난 판에만 있다(진행 중이면 null). */
	public record MyGame(String id, String scenario, GameStatus status, int used, int limit, Grade grade) {

		static MyGame of(Game game) {
			return new MyGame(game.id(), game.scenario().slug(), game.status(), game.used(), Game.LIMIT, game.grade());
		}
	}

	/** 열린 힌트(계시). seq는 열린 순간의 순번, image는 힌트 그림 주소(없으면 null). */
	public record HintView(int no, int seq, String text, String image) {
	}

	/** found: 이번 게임에서 낸 추리 중 하나라도 이 핵심을 맞혔는가 */
	public record KeywordView(String name, String description, boolean found) {
	}

	public record BonusView(String name, String description, boolean found) {
	}

	/** 결과 페이지에서 보는 계시. 게임 중에 열지 않은 것도 담는다. opened: 게임 중에 열었는가 */
	public record RevealHint(int no, String text, String image, boolean opened) {
	}

	/**
	 * grade: 끝난 판의 등급(Grade 참고).
	 * bonusSaved: 맞힌 보너스가 덜어 준 사용 횟수(해결한 판만), adjustedUsed: 등급을 매긴 사용 횟수.
	 * hints: 시나리오의 모든 계시(끝난 판이라 열지 않은 것도 보여 준다).
	 */
	public record Reveal(String truth, List<KeywordView> keywords, List<BonusView> bonuses, Grade grade,
			int bonusSaved, int adjustedUsed, List<RevealHint> hints) {

		private static List<KeywordView> keywords(com.seaturtle.office.content.Scenario s, java.util.Set<Integer> found) {
			List<KeywordView> result = new java.util.ArrayList<>();
			for (int i = 0; i < s.keywords().size(); i++) {
				var k = s.keywords().get(i);
				result.add(new KeywordView(k.name(), k.description(), found.contains(i + 1)));
			}
			return result;
		}

		static Reveal of(Game game, ScenarioImages images) {
			Scenario s = game.scenario();
			var found = game.bonusFound();
			List<BonusView> bonuses = new java.util.ArrayList<>();
			for (int i = 0; i < s.bonuses().size(); i++) {
				var b = s.bonuses().get(i);
				bonuses.add(new BonusView(b.name(), b.description(), found.contains(i + 1)));
			}
			return new Reveal(s.truth(),
					keywords(s, game.keywordFound()),
					List.copyOf(bonuses), game.grade(), game.bonusSaved(), game.adjustedUsed(), hints(game, images));
		}

		private static List<RevealHint> hints(Game game, ScenarioImages images) {
			Scenario s = game.scenario();
			int opened = game.hints().size();
			List<RevealHint> result = new java.util.ArrayList<>();
			for (int no = 1; no <= s.hints().size(); no++) {
				result.add(new RevealHint(no, s.hints().get(no - 1), images.url(s.slug(), "hint-" + no), no <= opened));
			}
			return result;
		}
	}

	public record GameView(
			String id,
			ScenarioSummary scenario,
			GameStatus status,
			List<Game.QuestionRecord> questions,
			List<HintView> hints,
			List<Game.GuessRecord> guesses,
			int used,
			int limit,
			List<Integer> hintSteps,
			boolean finalChance,
			Integer nextHintAt,
			Reveal reveal) {

		public static GameView of(Game game, ScenarioImages images) {
			Scenario s = game.scenario();
			GameStatus status = game.status();
			Reveal reveal = status == GameStatus.PLAYING ? null : Reveal.of(game, images);
			List<HintView> hints = game.hints().stream()
					.map(h -> new HintView(h.no(), h.seq(), h.text(), images.url(s.slug(), "hint-" + h.no())))
					.toList();
			return new GameView(game.id(), ScenarioSummary.of(s, images), status, game.questions(),
					hints, game.guesses(), game.used(), Game.LIMIT,
					game.hintSteps(), game.finalChance(), game.nextHintAt(), reveal);
		}
	}
}
