package com.seaturtle.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.seaturtle.office.game.Answer;
import com.seaturtle.office.game.Game;
import com.seaturtle.office.game.GameEndedException;
import com.seaturtle.office.game.GameService;
import com.seaturtle.office.game.Closeness;
import com.seaturtle.office.game.GameStatus;
import com.seaturtle.office.judge.Judge;
import com.seaturtle.office.judge.JudgeException;

/** AI 서버 없이 게임 흐름만 본다. 판정기는 정해진 답을 하는 가짜로 바꾼다. */
@SpringBootTest
@AutoConfigureMockMvc
class GameFlowTests {

	static class StubJudge implements Judge {
		final List<List<HistoryEntry>> histories = new ArrayList<>();

		@Override
		public Verdict question(String scenario, List<HistoryEntry> history, String question) {
			if (question.contains("오류")) {
				throw new JudgeException("테스트용 판정 실패");
			}
			histories.add(history);
			return new Verdict(Answer.IRRELEVANT_UNDECIDED);
		}

		@Override
		public GuessVerdict guess(String scenario, String guess) {
			if (guess.contains("개수 다름")) {
				return new GuessVerdict(List.of(1), 5, List.of(), 1);
			}
			if (guess.contains("보너스 다름")) {
				return new GuessVerdict(List.of(), 3, List.of(), 9);
			}
			if (guess.contains("정답")) {
				return new GuessVerdict(List.of(1, 2, 3), 3, List.of(1), 1);
			}
			// 오답: 둘째 핵심 키워드가 빠졌고 보너스를 맞혔다.
			return new GuessVerdict(List.of(1, 3), 3, List.of(1), 1);
		}

		@Override
		public Status status() {
			return new Status("테스트 판정기", true, true);
		}
	}

	@TestConfiguration
	static class Config {
		@Bean
		@Primary
		StubJudge stubJudge() {
			return new StubJudge();
		}
	}

	@Autowired
	GameService games;

	@Autowired
	StubJudge judge;

	@Autowired
	MockMvc mvc;

	@Test
	void playThroughAndGiveUp() {
		var game = games.start("boys-order");

		var q1 = games.ask(game.id(), "세입자들은 죽었나요?");
		var q2 = games.ask(game.id(), "건물주가 했나요?");
		assertThat(q1.no()).isEqualTo(1);
		assertThat(q2.answer()).isEqualTo(Answer.IRRELEVANT_UNDECIDED);
		// 두 번째 질문 때 첫 질문의 기록이 새 코드 그대로 함께 간다.
		assertThat(judge.histories.getLast())
				.containsExactly(new Judge.HistoryEntry("세입자들은 죽었나요?", Answer.IRRELEVANT_UNDECIDED));

		games.hint(game.id());
		assertThat(game.hintsOpen()).isEqualTo(1);

		games.guess(game.id(), "모르겠어요");
		assertThat(game.guesses().getFirst().correct()).isFalse();
		assertThat(game.status()).isEqualTo(GameStatus.PLAYING);

		games.giveUp(game.id());
		assertThat(game.status()).isEqualTo(GameStatus.GAVE_UP);
		assertThatThrownBy(() -> games.ask(game.id(), "또 질문")).isInstanceOf(GameEndedException.class);
	}

	@Test
	void correctGuessSolves() {
		var game = games.start("boys-order");
		games.guess(game.id(), "정답입니다");
		assertThat(game.status()).isEqualTo(GameStatus.SOLVED);
	}

	@Test
	void guessIsRejectedWhenAiServerKeyPointCountDiffers() {
		// AI 서버가 다른 버전의 시나리오를 읽고 있으면(한쪽만 재시작 등) 판정을 믿을 수 없다.
		var game = games.start("boys-order");
		assertThatThrownBy(() -> games.guess(game.id(), "개수 다름")).isInstanceOf(JudgeException.class);
		assertThatThrownBy(() -> games.guess(game.id(), "보너스 다름")).isInstanceOf(JudgeException.class);
		assertThat(game.guesses()).isEmpty();
		assertThat(game.status()).isEqualTo(GameStatus.PLAYING);
	}

	@Test
	void questionResponseHasNoKeyField() throws Exception {
		var game = games.start("boys-order");
		// 응답 본문에 answer는 있고 key는 없다.
		mvc.perform(post("/api/games/" + game.id() + "/questions").contentType(APPLICATION_JSON).content("{\"text\":\"q\"}"))
				.andExpect(jsonPath("$.questions[0].answer").value("IRRELEVANT_UNDECIDED"))
				.andExpect(jsonPath("$.questions[0].key").doesNotExist());
	}

	@Test
	void wrongGuessShowsOnlyCloseness() throws Exception {
		var game = games.start("boys-order");
		// 핵심 하나가 빠진 추리: 얼마나 가까운지만 나가고, 개수와 빠진 항목은 나가지 않는다.
		mvc.perform(post("/api/games/" + game.id() + "/guesses").contentType(APPLICATION_JSON)
				.content("{\"text\":\"모르겠어요\"}"))
				.andExpect(jsonPath("$.guesses[0].closeness").value("ONE_SHORT"))
				.andExpect(jsonPath("$.guesses[0].correct").value(false))
				.andExpect(jsonPath("$.guesses[0].matched").doesNotExist())
				.andExpect(jsonPath("$.guesses[0].total").doesNotExist())
				.andExpect(jsonPath("$.guesses[0].missing").doesNotExist());
	}

	@Test
	void closenessByMissingCount() {
		assertThat(Closeness.of(0)).isEqualTo(Closeness.CORRECT);
		assertThat(Closeness.of(1)).isEqualTo(Closeness.ONE_SHORT);
		assertThat(Closeness.of(2)).isEqualTo(Closeness.TWO_SHORT);
		assertThat(Closeness.of(3)).isEqualTo(Closeness.FAR);
		assertThat(Closeness.of(5)).isEqualTo(Closeness.FAR);
	}

	@Test
	void revealMarksKeywordsFoundAcrossGuesses() throws Exception {
		var game = games.start("boys-order");
		// 오답 추리는 핵심 1·3을 맞힌다. 포기하면 진상 공개에서 맞힌 핵심이 표시된다.
		mvc.perform(post("/api/games/" + game.id() + "/guesses").contentType(APPLICATION_JSON).content("{\"text\":\"모르겠어요\"}"));
		mvc.perform(post("/api/games/" + game.id() + "/give-up"))
				.andExpect(jsonPath("$.reveal.keywords[0].found").value(true))
				.andExpect(jsonPath("$.reveal.keywords[1].found").value(false))
				.andExpect(jsonPath("$.reveal.keywords[2].found").value(true));
	}

	@Test
	void scenarioShowsKeywordAndBonusCounts() throws Exception {
		// 열 개의 손목: 핵심 3개, 보너스 1개. 목록과 게임 화면 모두에서 개수만 보인다.
		mvc.perform(get("/api/scenarios"))
				.andExpect(jsonPath("$[?(@.slug == 'boys-order')].keywordCount").value(3))
				.andExpect(jsonPath("$[?(@.slug == 'boys-order')].bonusCount").value(1));
		var game = games.start("boys-order");
		mvc.perform(get("/api/games/" + game.id()))
				.andExpect(jsonPath("$.scenario.keywordCount").value(3))
				.andExpect(jsonPath("$.scenario.bonusCount").value(1))
				.andExpect(jsonPath("$.scenario.keywords").doesNotExist());
	}

	@Test
	void bonusHiddenUntilEndThenMerged() throws Exception {
		var game = games.start("boys-order");
		String guessUrl = "/api/games/" + game.id() + "/guesses";
		// 오답 추리(보너스를 맞힘): 게임 중에는 보너스 정보가 나가지 않는다.
		mvc.perform(post(guessUrl).contentType(APPLICATION_JSON).content("{\"text\":\"모르겠어요\"}"));
		mvc.perform(get("/api/games/" + game.id()))
				.andExpect(jsonPath("$.reveal").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.guesses[0].bonus").doesNotExist());
		// 정답 추리: 끝나면 여러 추리에서 맞힌 보너스를 합쳐 보여 준다.
		mvc.perform(post(guessUrl).contentType(APPLICATION_JSON).content("{\"text\":\"정답\"}"))
				.andExpect(jsonPath("$.reveal.keywords[0].name").value("카포의 권위"))
				.andExpect(jsonPath("$.reveal.bonuses[0].found").value(true))
				.andExpect(jsonPath("$.reveal.bonuses.length()").value(1));
	}

	// ── 사용 횟수, 힌트, 마지막 추리, 점수 ──

	private Game playing(int questions) {
		var game = games.start("boys-order");
		for (int i = 0; i < questions; i++) {
			games.ask(game.id(), "q" + i);
		}
		return game;
	}

	@Test
	void questionsCostOneAndOpenHintAtTen() throws Exception {
		var game = playing(9);
		assertThat(game.used()).isEqualTo(9);
		assertThat(game.hintsOpen()).isZero();
		mvc.perform(post("/api/games/" + game.id() + "/questions").contentType(APPLICATION_JSON).content("{\"text\":\"열 번째\"}"))
				.andExpect(jsonPath("$.used").value(10))
				.andExpect(jsonPath("$.hints.length()").value(1));
		assertThat(game.hintsOpen()).isEqualTo(1);
	}

	@Test
	void hintButtonJumpsToNextStep() {
		var game = playing(5);
		assertThat(game.nextHintAt()).isEqualTo(10);
		games.hint(game.id());
		assertThat(game.used()).isEqualTo(10);
		assertThat(game.hintsOpen()).isEqualTo(1);
		for (int i = 0; i < 3; i++) {
			games.ask(game.id(), "q");
		}
		assertThat(game.nextHintAt()).isEqualTo(20);
		games.hint(game.id());
		assertThat(game.used()).isEqualTo(20);
		games.hint(game.id());
		assertThat(game.used()).isEqualTo(30);
		assertThat(game.hintsOpen()).isEqualTo(3);
		assertThat(game.nextHintAt()).isNull();
		assertThatThrownBy(() -> games.hint(game.id())).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void guessCostsFiveAndCapsAtForty() {
		var game = playing(38);
		games.guess(game.id(), "모르겠어요");
		assertThat(game.used()).isEqualTo(40);
		assertThat(game.finalChance()).isTrue();
		assertThat(game.status()).isEqualTo(GameStatus.PLAYING);
	}

	@Test
	void questionAtLimitIsRejected() throws Exception {
		var game = playing(40);
		assertThatThrownBy(() -> games.ask(game.id(), "41번째")).isInstanceOf(IllegalStateException.class);
		mvc.perform(post("/api/games/" + game.id() + "/questions").contentType(APPLICATION_JSON).content("{\"text\":\"q\"}"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
		assertThat(game.used()).isEqualTo(40);
	}

	@Test
	void finalGuessWrongFails() {
		var game = playing(40);
		games.guess(game.id(), "모르겠어요");
		assertThat(game.used()).isEqualTo(40);
		assertThat(game.status()).isEqualTo(GameStatus.FAILED);
		assertThat(game.score()).isZero();
	}

	@Test
	void scoreOnSolve() throws Exception {
		var game = playing(3);
		mvc.perform(post("/api/games/" + game.id() + "/guesses").contentType(APPLICATION_JSON).content("{\"text\":\"정답\"}"))
				.andExpect(jsonPath("$.used").value(8))
				.andExpect(jsonPath("$.reveal.score").value(102))
				.andExpect(jsonPath("$.reveal.bonusPoints").value(10));
		assertThat(game.status()).isEqualTo(GameStatus.SOLVED);
	}

	@Test
	void judgeErrorDoesNotSpend() {
		var game = playing(4);
		assertThatThrownBy(() -> games.ask(game.id(), "오류")).isInstanceOf(JudgeException.class);
		assertThat(game.used()).isEqualTo(4);
	}

	@Test
	void hintStepsFollowHintCount() {
		assertThat(Game.hintStepsFor(2)).containsExactly(10, 20);
		assertThat(Game.hintStepsFor(3)).containsExactly(10, 20, 30);
		// 힌트가 4개 이상이어도 40에는 힌트가 없다.
		assertThat(Game.hintStepsFor(4)).containsExactly(10, 20, 30);
	}

	// ── 힌트가 열린 순서 ──

	@Test
	void hintRecordedAtTheMomentItOpens() {
		var game = playing(8);
		games.guess(game.id(), "모르겠어요");
		assertThat(game.hints()).extracting(Game.HintRecord::no, Game.HintRecord::seq).containsExactly(org.assertj.core.groups.Tuple.tuple(1, 10));
		games.ask(game.id(), "q");
		games.ask(game.id(), "q");
		games.hint(game.id());
		assertThat(game.hints()).extracting(Game.HintRecord::seq).containsExactly(10, 13);
		assertThat(game.hints().get(0).text()).isEqualTo(game.scenario().hints().get(0));
	}

	@Test
	void hintButtonKeepsEarlierHintPositions() {
		var game = playing(25);
		assertThat(game.hints()).extracting(Game.HintRecord::seq).containsExactly(11, 22);
		games.hint(game.id());
		assertThat(game.hints()).extracting(Game.HintRecord::seq).containsExactly(11, 22, 28);
	}

	@Test
	void gameViewHintsCarrySeq() throws Exception {
		var game = playing(10);
		mvc.perform(get("/api/games/" + game.id()))
				.andExpect(jsonPath("$.hints[0].no").value(1))
				.andExpect(jsonPath("$.hints[0].seq").value(11))
				// scenarios/images/boys-order/에 그림이 있으면 주소가 붙는다.
				.andExpect(jsonPath("$.hints[0].image").value("/api/scenarios/boys-order/images/hint-1"))
				.andExpect(jsonPath("$.scenario.cover").value("/api/scenarios/boys-order/images/cover"));
	}

	@Test
	void imageEndpointRejectsMissingAndUnknownNames() throws Exception {
		mvc.perform(get("/api/scenarios/boys-order/images/cover"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
		mvc.perform(get("/api/scenarios/boys-order/images/hint-9"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
		mvc.perform(get("/api/scenarios/boys-order/images/secret"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
		mvc.perform(get("/api/scenarios/nope/images/cover"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
	}
}
