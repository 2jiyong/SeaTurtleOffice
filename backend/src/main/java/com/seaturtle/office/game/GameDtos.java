package com.seaturtle.office.game;

import java.util.List;

import com.seaturtle.office.content.Scenario;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** API 요청과 응답. 진상과 핵심 포인트는 게임이 끝났을 때만 내보낸다. */
public final class GameDtos {

	private GameDtos() {
	}

	public record StartRequest(@NotBlank String scenario) {
	}

	public record QuestionRequest(@NotBlank @Size(max = 200) String text) {
	}

	public record GuessRequest(@NotBlank @Size(max = 1000) String text) {
	}

	public record ScenarioSummary(String slug, String title, String difficulty, String problem, int hintCount) {

		static ScenarioSummary of(Scenario s) {
			return new ScenarioSummary(s.slug(), s.title(), s.difficulty(), s.problem(), s.hints().size());
		}
	}

	public record Reveal(String truth, List<String> keyPoints) {
	}

	public record GameView(
			String id,
			ScenarioSummary scenario,
			GameStatus status,
			List<Game.QuestionRecord> questions,
			List<String> hints,
			List<Game.GuessRecord> guesses,
			Reveal reveal) {

		static GameView of(Game game) {
			Scenario s = game.scenario();
			GameStatus status = game.status();
			Reveal reveal = status == GameStatus.PLAYING ? null : new Reveal(s.truth(), s.keyPoints());
			return new GameView(game.id(), ScenarioSummary.of(s), status, game.questions(),
					s.hints().subList(0, game.hintsUsed()), game.guesses(), reveal);
		}
	}
}
