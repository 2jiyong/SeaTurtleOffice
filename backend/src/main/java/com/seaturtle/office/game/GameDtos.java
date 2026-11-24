package com.seaturtle.office.game;

import java.util.List;

import com.seaturtle.office.content.Scenario;
import com.seaturtle.office.content.ScenarioImages;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** API 요청과 응답. 진상, 핵심 키워드, 맞힌 보너스는 게임이 끝났을 때만 내보낸다. */
public final class GameDtos {

	private GameDtos() {
	}

	public record StartRequest(@NotBlank String scenario) {
	}

	public record QuestionRequest(@NotBlank @Size(max = 200) String text) {
	}

	public record GuessRequest(@NotBlank @Size(max = 1000) String text) {
	}

	/** cover: 대표 이미지 주소. 그림이 없으면 null. */
	public record ScenarioSummary(String slug, String title, String difficulty, String problem, int hintCount,
			String cover) {

		static ScenarioSummary of(Scenario s, ScenarioImages images) {
			return new ScenarioSummary(s.slug(), s.title(), s.difficulty(), s.problem(), s.hints().size(),
					images.url(s.slug(), "cover"));
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

	/** score: 해결하면 100 − 사용 + bonusPoints, 실패·포기는 0. */
	public record Reveal(String truth, List<KeywordView> keywords, List<BonusView> bonuses, int score,
			int bonusPoints) {

		private static List<KeywordView> keywords(com.seaturtle.office.content.Scenario s, java.util.Set<Integer> found) {
			List<KeywordView> result = new java.util.ArrayList<>();
			for (int i = 0; i < s.keywords().size(); i++) {
				var k = s.keywords().get(i);
				result.add(new KeywordView(k.name(), k.description(), found.contains(i + 1)));
			}
			return result;
		}

		static Reveal of(Game game) {
			Scenario s = game.scenario();
			var found = game.bonusFound();
			List<BonusView> bonuses = new java.util.ArrayList<>();
			for (int i = 0; i < s.bonuses().size(); i++) {
				var b = s.bonuses().get(i);
				bonuses.add(new BonusView(b.name(), b.description(), found.contains(i + 1)));
			}
			return new Reveal(s.truth(),
					keywords(s, game.keywordFound()),
					List.copyOf(bonuses), game.score(), game.bonusPoints());
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

		static GameView of(Game game, ScenarioImages images) {
			Scenario s = game.scenario();
			GameStatus status = game.status();
			Reveal reveal = status == GameStatus.PLAYING ? null : Reveal.of(game);
			List<HintView> hints = game.hints().stream()
					.map(h -> new HintView(h.no(), h.seq(), h.text(), images.url(s.slug(), "hint-" + h.no())))
					.toList();
			return new GameView(game.id(), ScenarioSummary.of(s, images), status, game.questions(),
					hints, game.guesses(), game.used(), Game.LIMIT,
					game.hintSteps(), game.finalChance(), game.nextHintAt(), reveal);
		}
	}
}
