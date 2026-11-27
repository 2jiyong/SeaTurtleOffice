package com.seaturtle.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.seaturtle.office.game.Answer;
import com.seaturtle.office.game.ClientIdFilter;
import com.seaturtle.office.game.Game;
import com.seaturtle.office.game.GameEndedException;
import com.seaturtle.office.game.GameService;
import com.seaturtle.office.game.Closeness;
import com.seaturtle.office.game.GameStatus;
import com.seaturtle.office.game.Grade;
import com.seaturtle.office.judge.Judge;
import com.seaturtle.office.judge.JudgeException;

import jakarta.servlet.http.Cookie;

/** AI 서버 없이 게임 흐름만 본다. 판정기는 정해진 답을 하는 가짜로 바꾼다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class GameFlowTests {

	static class StubJudge implements Judge {
		final List<List<HistoryEntry>> histories = new ArrayList<>();
		/** 질문에 "느린"이 있으면 판정 도중에 이것을 실행한다(다른 창의 요청 흉내). */
		volatile Runnable duringSlowQuestion = () -> {
		};

		@Override
		public Verdict question(String scenario, List<HistoryEntry> history, String question) {
			if (question.contains("오류")) {
				throw new JudgeException("테스트용 판정 실패");
			}
			if (question.contains("느린")) {
				duringSlowQuestion.run();
			}
			histories.add(history);
			if (question.contains("반려")) {
				// 모델이 명제를 채워 보내도 반려된 질문에는 보이지 않아야 한다.
				return new Verdict(Answer.INVALID_FORM, "반려된 명제", "형식 근거", null);
			}
			if (question.contains("명제")) {
				return new Verdict(Answer.YES, question + "의 명제", "비밀 근거", null);
			}
			return new Verdict(Answer.IRRELEVANT_UNDECIDED, null);
		}

		@Override
		public GuessVerdict guess(String scenario, String guess) {
			if (guess.contains("개수 다름")) {
				return new GuessVerdict(List.of(1), 5, List.of(), 1, true, null);
			}
			if (guess.contains("보너스 다름")) {
				return new GuessVerdict(List.of(), 3, List.of(), 9, true, null);
			}
			if (guess.contains("예전 서버")) {
				return new GuessVerdict(List.of(1, 2, 3), 3, List.of(), 1, null, null);
			}
			if (guess.contains("어긋난 이야기")) {
				// 핵심은 다 들었지만 이야기가 진상과 어긋난다.
				return new GuessVerdict(List.of(1, 2, 3), 3, List.of(), 1, false, null);
			}
			if (guess.contains("정답")) {
				return new GuessVerdict(List.of(1, 2, 3), 3, List.of(1), 1, true, null);
			}
			// 오답: 둘째 핵심 키워드가 빠졌고 보너스를 맞혔다.
			return new GuessVerdict(List.of(1, 3), 3, List.of(1), 1, true, "비밀 채점 근거", null);
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

	/** 테스트마다 다른 기기 */
	final String client = UUID.randomUUID().toString();

	Cookie cookie() {
		return new Cookie(ClientIdFilter.COOKIE, client);
	}

	/** 이 기기의 쿠키를 실은 요청 */
	MockHttpServletRequestBuilder post(String url) {
		return MockMvcRequestBuilders.post(url).cookie(cookie());
	}

	MockHttpServletRequestBuilder get(String url) {
		return MockMvcRequestBuilders.get(url).cookie(cookie());
	}

	/** 서비스는 부를 때마다 DB에서 새로 읽는다. 지금 상태를 보려면 다시 읽는다. */
	Game reload(Game game) {
		return games.get(client, game.id());
	}

	@Test
	void rejectedQuestionUsesNothingAndHasNoNumber() {
		var game = games.start(client, "boys-order").game();

		var after = games.ask(client, game.id(), "반려될 질문");
		var rejected = after.questions().getLast();
		assertThat(rejected.no()).isNull();
		assertThat(rejected.answer()).isEqualTo(Answer.INVALID_FORM);
		assertThat(rejected.proposition()).isNull();
		assertThat(after.used()).isZero();

		var next = games.ask(client, game.id(), "다음 질문").questions().getLast();
		assertThat(next.no()).isEqualTo(1);
		assertThat(reload(game).used()).isEqualTo(1);
		// 반려된 질문도 다음 판정의 기록에 간다("그" 같은 말이 가리킬 수 있다).
		assertThat(judge.histories.getLast()).extracting(Judge.HistoryEntry::text).containsExactly("반려될 질문");
	}

	@Test
	void propositionGoesToHistoryAndScreenButReasonNever() throws Exception {
		var game = games.start(client, "boys-order").game();
		mvc.perform(post("/api/games/" + game.id() + "/questions").contentType(APPLICATION_JSON)
				.content("{\"text\":\"명제 질문\"}"))
				.andExpect(jsonPath("$.questions[0].proposition").value("명제 질문의 명제"))
				.andExpect(jsonPath("$.questions[0].reason").doesNotExist());
		mvc.perform(post("/api/games/" + game.id() + "/guesses").contentType(APPLICATION_JSON)
				.content("{\"text\":\"모르겠어요\"}"))
				.andExpect(jsonPath("$.guesses[0].reason").doesNotExist());
		String body = mvc.perform(get("/api/games/" + game.id())).andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("비밀 근거").doesNotContain("비밀 채점 근거");

		// 근거는 DB에 남는다.
		var saved = reload(game);
		assertThat(saved.questions().getFirst().reason()).isEqualTo("비밀 근거");
		assertThat(saved.guesses().getFirst().reason()).isEqualTo("비밀 채점 근거");

		games.ask(client, game.id(), "다음 질문");
		assertThat(judge.histories.getLast())
				.containsExactly(new Judge.HistoryEntry("명제 질문", Answer.YES, "명제 질문의 명제"));
	}

	@Test
	void playThroughAndGiveUp() {
		var game = games.start(client, "boys-order").game();

		var q1 = games.ask(client, game.id(), "세입자들은 죽었나요?").questions().getLast();
		var q2 = games.ask(client, game.id(), "건물주가 했나요?").questions().getLast();
		assertThat(q1.no()).isEqualTo(1);
		assertThat(q2.answer()).isEqualTo(Answer.IRRELEVANT_UNDECIDED);
		// 두 번째 질문 때 첫 질문의 기록이 새 코드 그대로 함께 간다.
		assertThat(judge.histories.getLast())
				.containsExactly(new Judge.HistoryEntry("세입자들은 죽었나요?", Answer.IRRELEVANT_UNDECIDED));

		games.hint(client, game.id());
		assertThat(reload(game).hintsOpen()).isEqualTo(1);

		games.guess(client, game.id(), "모르겠어요");
		assertThat(reload(game).guesses().getFirst().correct()).isFalse();
		assertThat(reload(game).status()).isEqualTo(GameStatus.PLAYING);

		games.giveUp(client, game.id());
		assertThat(reload(game).status()).isEqualTo(GameStatus.GAVE_UP);
		assertThatThrownBy(() -> games.ask(client, game.id(), "또 질문")).isInstanceOf(GameEndedException.class);
	}

	@Test
	void allKeywordsButWrongStoryIsNotSolved() {
		var game = games.start(client, "boys-order").game();
		games.guess(client, game.id(), "어긋난 이야기");
		var after = reload(game);
		assertThat(after.status()).isEqualTo(GameStatus.PLAYING);
		assertThat(after.guesses().getLast().correct()).isFalse();
		assertThat(after.guesses().getLast().closeness()).isEqualTo(Closeness.ONE_SHORT);
		assertThat(after.used()).isEqualTo(Game.GUESS_COST);
	}

	@Test
	void verdictWithoutStoryIsRejected() {
		var game = games.start(client, "boys-order").game();
		assertThatThrownBy(() -> games.guess(client, game.id(), "예전 서버")).isInstanceOf(JudgeException.class);
		assertThat(reload(game).used()).isZero();
	}

	@Test
	void correctGuessSolves() {
		var game = games.start(client, "boys-order").game();
		games.guess(client, game.id(), "정답입니다");
		assertThat(reload(game).status()).isEqualTo(GameStatus.SOLVED);
	}

	@Test
	void guessIsRejectedWhenAiServerKeyPointCountDiffers() {
		// AI 서버가 다른 버전의 시나리오를 읽고 있으면(한쪽만 재시작 등) 판정을 믿을 수 없다.
		var game = games.start(client, "boys-order").game();
		assertThatThrownBy(() -> games.guess(client, game.id(), "개수 다름")).isInstanceOf(JudgeException.class);
		assertThatThrownBy(() -> games.guess(client, game.id(), "보너스 다름")).isInstanceOf(JudgeException.class);
		assertThat(reload(game).guesses()).isEmpty();
		assertThat(reload(game).status()).isEqualTo(GameStatus.PLAYING);
	}

	@Test
	void questionResponseHasNoKeyField() throws Exception {
		var game = games.start(client, "boys-order").game();
		// 응답 본문에 answer는 있고 key는 없다.
		mvc.perform(post("/api/games/" + game.id() + "/questions").contentType(APPLICATION_JSON).content("{\"text\":\"q\"}"))
				.andExpect(jsonPath("$.questions[0].answer").value("IRRELEVANT_UNDECIDED"))
				.andExpect(jsonPath("$.questions[0].key").doesNotExist());
	}

	@Test
	void wrongGuessShowsOnlyCloseness() throws Exception {
		var game = games.start(client, "boys-order").game();
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
		var game = games.start(client, "boys-order").game();
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
		var game = games.start(client, "boys-order").game();
		mvc.perform(get("/api/games/" + game.id()))
				.andExpect(jsonPath("$.scenario.keywordCount").value(3))
				.andExpect(jsonPath("$.scenario.bonusCount").value(1))
				.andExpect(jsonPath("$.scenario.keywords").doesNotExist());
	}

	@Test
	void bonusHiddenUntilEndThenMerged() throws Exception {
		var game = games.start(client, "boys-order").game();
		String guessUrl = "/api/games/" + game.id() + "/guesses";
		// 오답 추리(보너스를 맞힘): 게임 중에는 보너스 정보가 나가지 않는다.
		mvc.perform(post(guessUrl).contentType(APPLICATION_JSON).content("{\"text\":\"모르겠어요\"}"));
		mvc.perform(get("/api/games/" + game.id()))
				.andExpect(jsonPath("$.reveal").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.guesses[0].bonus").doesNotExist());
		// 정답 추리: 끝나면 여러 추리에서 맞힌 보너스를 합쳐 보여 준다.
		mvc.perform(post(guessUrl).contentType(APPLICATION_JSON).content("{\"text\":\"정답\"}"))
				.andExpect(jsonPath("$.reveal.keywords[0].name").value("아버지의 권위"))
				.andExpect(jsonPath("$.reveal.bonuses[0].found").value(true))
				.andExpect(jsonPath("$.reveal.bonuses.length()").value(1));
	}

	// ── 사용 횟수, 힌트, 마지막 추리, 점수 ──

	private Game playing(int questions) {
		var game = games.start(client, "boys-order").game();
		for (int i = 0; i < questions; i++) {
			games.ask(client, game.id(), "q" + i);
		}
		return game;
	}

	@Test
	void questionsCostOneAndOpenHintAtTen() throws Exception {
		var game = playing(9);
		assertThat(reload(game).used()).isEqualTo(9);
		assertThat(reload(game).hintsOpen()).isZero();
		mvc.perform(post("/api/games/" + game.id() + "/questions").contentType(APPLICATION_JSON).content("{\"text\":\"열 번째\"}"))
				.andExpect(jsonPath("$.used").value(10))
				.andExpect(jsonPath("$.hints.length()").value(1));
		assertThat(reload(game).hintsOpen()).isEqualTo(1);
	}

	@Test
	void hintButtonJumpsToNextStep() {
		var game = playing(5);
		assertThat(reload(game).nextHintAt()).isEqualTo(10);
		games.hint(client, game.id());
		assertThat(reload(game).used()).isEqualTo(10);
		assertThat(reload(game).hintsOpen()).isEqualTo(1);
		for (int i = 0; i < 3; i++) {
			games.ask(client, game.id(), "q");
		}
		assertThat(reload(game).nextHintAt()).isEqualTo(20);
		games.hint(client, game.id());
		assertThat(reload(game).used()).isEqualTo(20);
		games.hint(client, game.id());
		assertThat(reload(game).used()).isEqualTo(30);
		assertThat(reload(game).hintsOpen()).isEqualTo(3);
		assertThat(reload(game).nextHintAt()).isNull();
		assertThatThrownBy(() -> games.hint(client, game.id())).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void wrongGuessCostsThree() {
		var game = playing(12);
		games.guess(client, game.id(), "모르겠어요");
		assertThat(reload(game).used()).isEqualTo(15);
	}

	@Test
	void bonusStillSavesFiveAfterGuessCostChange() {
		// 추리 비용(3)과 보너스(5)는 따로다. 이미 끝난 판의 등급이 바뀌지 않게 보너스는 5 그대로.
		assertThat(Game.GUESS_COST).isEqualTo(3);
		assertThat(Game.BONUS_SAVE).isEqualTo(5);
	}

	@Test
	void guessCapsAtForty() {
		var game = playing(38);
		games.guess(client, game.id(), "모르겠어요");
		assertThat(reload(game).used()).isEqualTo(40);
		assertThat(reload(game).finalChance()).isTrue();
		assertThat(reload(game).status()).isEqualTo(GameStatus.PLAYING);
	}

	@Test
	void correctGuessCostsNothing() {
		var game = playing(12);
		games.guess(client, game.id(), "정답");
		assertThat(reload(game).used()).isEqualTo(12);
		assertThat(reload(game).status()).isEqualTo(GameStatus.SOLVED);
	}

	@Test
	void questionAtLimitIsRejected() throws Exception {
		var game = playing(40);
		assertThatThrownBy(() -> games.ask(client, game.id(), "41번째")).isInstanceOf(IllegalStateException.class);
		mvc.perform(post("/api/games/" + game.id() + "/questions").contentType(APPLICATION_JSON).content("{\"text\":\"q\"}"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
		assertThat(reload(game).used()).isEqualTo(40);
	}

	@Test
	void finalGuessWrongFails() {
		var game = playing(40);
		games.guess(client, game.id(), "모르겠어요");
		assertThat(reload(game).used()).isEqualTo(40);
		assertThat(reload(game).status()).isEqualTo(GameStatus.FAILED);
		// 못 풀었지만 핵심 일부(1, 3번)를 밝혔다.
		assertThat(reload(game).grade()).isEqualTo(Grade.D);
	}

	@Test
	void revealListsEveryHintAfterEnd() throws Exception {
		var game = playing(10);
		games.giveUp(client, game.id());
		mvc.perform(get("/api/games/" + game.id()))
				.andExpect(jsonPath("$.hints.length()").value(1))
				.andExpect(jsonPath("$.reveal.hints.length()").value(3))
				.andExpect(jsonPath("$.reveal.hints[0].opened").value(true))
				.andExpect(jsonPath("$.reveal.hints[1].opened").value(false))
				.andExpect(jsonPath("$.reveal.hints[2].no").value(3))
				.andExpect(jsonPath("$.reveal.hints[2].image").value("/api/scenarios/boys-order/images/hint-3"));
	}

	@Test
	void giveUpWithoutKeywordsIsF() {
		var game = playing(5);
		games.giveUp(client, game.id());
		assertThat(reload(game).grade()).isEqualTo(Grade.F);
	}

	@Test
	void playingHasNoGrade() {
		assertThat(reload(playing(3)).grade()).isNull();
	}

	@Test
	void solvedGradeFollowsAdjustedUse() {
		// 보너스 하나(5)를 빼고 매긴다: 35 − 5 = 30 → B, 36 − 5 = 31 → C.
		var b = playing(35);
		games.guess(client, b.id(), "정답");
		assertThat(reload(b).adjustedUsed()).isEqualTo(30);
		assertThat(reload(b).grade()).isEqualTo(Grade.B);
		games.reset(client, b.id());
		var c = playing(36);
		games.guess(client, c.id(), "정답");
		assertThat(reload(c).grade()).isEqualTo(Grade.C);
	}

	@Test
	void gradeOnSolve() throws Exception {
		var game = playing(3);
		mvc.perform(post("/api/games/" + game.id() + "/guesses").contentType(APPLICATION_JSON).content("{\"text\":\"정답\"}"))
				.andExpect(jsonPath("$.used").value(3))
				.andExpect(jsonPath("$.reveal.bonusSaved").value(5))
				// 3 − 5는 0에서 멈춘다.
				.andExpect(jsonPath("$.reveal.adjustedUsed").value(0))
				.andExpect(jsonPath("$.reveal.grade").value("S"));
		assertThat(reload(game).status()).isEqualTo(GameStatus.SOLVED);
	}

	@Test
	void judgeErrorDoesNotSpend() {
		var game = playing(4);
		assertThatThrownBy(() -> games.ask(client, game.id(), "오류")).isInstanceOf(JudgeException.class);
		assertThat(reload(game).used()).isEqualTo(4);
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
		games.guess(client, game.id(), "모르겠어요");
		assertThat(reload(game).hints()).extracting(Game.HintRecord::no, Game.HintRecord::seq).containsExactly(org.assertj.core.groups.Tuple.tuple(1, 10));
		games.ask(client, game.id(), "q");
		games.ask(client, game.id(), "q");
		games.hint(client, game.id());
		assertThat(reload(game).hints()).extracting(Game.HintRecord::seq).containsExactly(10, 13);
		assertThat(reload(game).hints().get(0).text()).isEqualTo(game.scenario().hints().get(0));
	}

	@Test
	void hintButtonKeepsEarlierHintPositions() {
		var game = playing(25);
		assertThat(reload(game).hints()).extracting(Game.HintRecord::seq).containsExactly(11, 22);
		games.hint(client, game.id());
		assertThat(reload(game).hints()).extracting(Game.HintRecord::seq).containsExactly(11, 22, 28);
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

	// ── 기기별 저장, 이어하기, 초기화 ──

	private static org.springframework.test.web.servlet.ResultMatcher status(int code) {
		return org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(code);
	}

	@Test
	void stateSurvivesReload() {
		var game = games.start(client, "boys-order").game();
		games.ask(client, game.id(), "하나");
		games.ask(client, game.id(), "둘");
		games.hint(client, game.id());
		games.guess(client, game.id(), "모르겠어요");
		Game loaded = reload(game);
		assertThat(loaded.questions()).extracting(Game.QuestionRecord::text).containsExactly("하나", "둘");
		assertThat(loaded.hints()).hasSize(1);
		assertThat(loaded.guesses()).hasSize(1);
		assertThat(loaded.used()).isEqualTo(Game.HINT_STEP + Game.GUESS_COST);
	}

	@Test
	void startReturnsSameGameForSameScenario() throws Exception {
		var first = games.start(client, "boys-order");
		var second = games.start(client, "boys-order");
		assertThat(first.created()).isTrue();
		assertThat(second.created()).isFalse();
		assertThat(second.game().id()).isEqualTo(first.game().id());

		String other = UUID.randomUUID().toString();
		mvc.perform(MockMvcRequestBuilders.post("/api/games").cookie(new Cookie(ClientIdFilter.COOKIE, other))
				.contentType(APPLICATION_JSON).content("{\"scenario\":\"boys-order\"}"))
				.andExpect(status(201));
		mvc.perform(MockMvcRequestBuilders.post("/api/games").cookie(new Cookie(ClientIdFilter.COOKIE, other))
				.contentType(APPLICATION_JSON).content("{\"scenario\":\"boys-order\"}"))
				.andExpect(status(200));
	}

	@Test
	void otherDeviceGets404() throws Exception {
		var game = games.start(client, "boys-order").game();
		games.ask(client, game.id(), "내 질문");
		Cookie stranger = new Cookie(ClientIdFilter.COOKIE, UUID.randomUUID().toString());
		mvc.perform(MockMvcRequestBuilders.get("/api/games/" + game.id()).cookie(stranger)).andExpect(status(404));
		mvc.perform(MockMvcRequestBuilders.post("/api/games/" + game.id() + "/questions").cookie(stranger)
				.contentType(APPLICATION_JSON).content("{\"text\":\"q\"}")).andExpect(status(404));
		mvc.perform(MockMvcRequestBuilders.delete("/api/games/" + game.id()).cookie(stranger)).andExpect(status(404));
		assertThat(reload(game).questions()).hasSize(1);
	}

	@Test
	void myGamesListsStatus() throws Exception {
		var playing = games.start(client, "boys-order").game();
		for (int i = 0; i < 3; i++) {
			games.ask(client, playing.id(), "q");
		}
		var other = games.start(client, "one-week").game();
		games.giveUp(client, other.id());
		mvc.perform(get("/api/games"))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[?(@.scenario == 'boys-order')].status").value("PLAYING"))
				.andExpect(jsonPath("$[?(@.scenario == 'boys-order')].used").value(3))
				.andExpect(jsonPath("$[?(@.scenario == 'boys-order')].limit").value(40))
				.andExpect(jsonPath("$[?(@.scenario == 'boys-order')].id").value(playing.id()))
				.andExpect(jsonPath("$[?(@.scenario == 'one-week')].status").value("GAVE_UP"))
				.andExpect(jsonPath("$[?(@.scenario == 'one-week')].grade").value("F"));
	}

	@Test
	void resetThenStartGivesFreshGame() throws Exception {
		var game = games.start(client, "boys-order").game();
		games.ask(client, game.id(), "q");
		mvc.perform(MockMvcRequestBuilders.delete("/api/games/" + game.id()).cookie(cookie())).andExpect(status(204));
		mvc.perform(post("/api/games").contentType(APPLICATION_JSON).content("{\"scenario\":\"boys-order\"}"))
				.andExpect(status(201))
				.andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(game.id())))
				.andExpect(jsonPath("$.questions.length()").value(0));
	}

	@Test
	void staleWriteGives409() throws Exception {
		var game = games.start(client, "boys-order").game();
		judge.duringSlowQuestion = () -> games.giveUp(client, game.id());
		try {
			mvc.perform(post("/api/games/" + game.id() + "/questions").contentType(APPLICATION_JSON)
					.content("{\"text\":\"느린 질문\"}"))
					.andExpect(status(409))
					.andExpect(jsonPath("$.detail").value("다른 창에서 진행이 바뀌었습니다. 새로고침해 주세요."));
		} finally {
			judge.duringSlowQuestion = () -> {
			};
		}
		assertThat(reload(game).status()).isEqualTo(GameStatus.GAVE_UP);
		assertThat(reload(game).questions()).isEmpty();
	}

	@Test
	void judgeErrorKeepsVersion() {
		var game = games.start(client, "boys-order").game();
		assertThatThrownBy(() -> games.ask(client, game.id(), "오류")).isInstanceOf(JudgeException.class);
		assertThat(reload(game).used()).isZero();
		games.ask(client, game.id(), "정상 질문");
		assertThat(reload(game).used()).isEqualTo(1);
	}

	@Test
	void requestWithoutCookieStartsFresh() throws Exception {
		var result = mvc.perform(MockMvcRequestBuilders.post("/api/games").contentType(APPLICATION_JSON)
				.content("{\"scenario\":\"boys-order\"}"))
				.andExpect(status(201))
				.andReturn();
		assertThat(result.getResponse().getHeader("Set-Cookie")).startsWith("sto_client=");
	}

	@Test
	void resetUnknownGameIs404() throws Exception {
		mvc.perform(MockMvcRequestBuilders.delete("/api/games/not-a-uuid").cookie(cookie())).andExpect(status(404));
	}
}
