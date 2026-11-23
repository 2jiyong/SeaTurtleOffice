package com.seaturtle.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
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
import com.seaturtle.office.game.GameEndedException;
import com.seaturtle.office.game.GameService;
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
			histories.add(history);
			return new Verdict(Answer.IRRELEVANT_UNDECIDED);
		}

		@Override
		public GuessVerdict guess(String scenario, String guess) {
			if (guess.contains("개수 다름")) {
				return new GuessVerdict(2, 5);
			}
			return guess.contains("정답") ? new GuessVerdict(3, 3) : new GuessVerdict(1, 3);
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
		var game = games.start("last-rent");

		var q1 = games.ask(game.id(), "세입자들은 죽었나요?");
		var q2 = games.ask(game.id(), "건물주가 했나요?");
		assertThat(q1.no()).isEqualTo(1);
		assertThat(q2.answer()).isEqualTo(Answer.IRRELEVANT_UNDECIDED);
		// 두 번째 질문 때 첫 질문의 기록이 새 코드 그대로 함께 간다.
		assertThat(judge.histories.getLast())
				.containsExactly(new Judge.HistoryEntry("세입자들은 죽었나요?", Answer.IRRELEVANT_UNDECIDED));

		games.hint(game.id());
		assertThat(game.hintsUsed()).isEqualTo(1);

		games.guess(game.id(), "모르겠어요");
		assertThat(game.guesses().getFirst().correct()).isFalse();
		assertThat(game.status()).isEqualTo(GameStatus.PLAYING);

		games.giveUp(game.id());
		assertThat(game.status()).isEqualTo(GameStatus.GAVE_UP);
		assertThatThrownBy(() -> games.ask(game.id(), "또 질문")).isInstanceOf(GameEndedException.class);
	}

	@Test
	void correctGuessSolves() {
		var game = games.start("last-rent");
		games.guess(game.id(), "정답입니다");
		assertThat(game.status()).isEqualTo(GameStatus.SOLVED);
	}

	@Test
	void guessIsRejectedWhenAiServerKeyPointCountDiffers() {
		// AI 서버가 다른 버전의 시나리오를 읽고 있으면(한쪽만 재시작 등) 판정을 믿을 수 없다.
		var game = games.start("last-rent");
		assertThatThrownBy(() -> games.guess(game.id(), "개수 다름")).isInstanceOf(JudgeException.class);
		assertThat(game.guesses()).isEmpty();
		assertThat(game.status()).isEqualTo(GameStatus.PLAYING);
	}

	@Test
	void questionResponseHasNoKeyField() throws Exception {
		var game = games.start("last-rent");
		// 응답 본문에 answer는 있고 key는 없다.
		mvc.perform(post("/api/games/" + game.id() + "/questions").contentType(APPLICATION_JSON).content("{\"text\":\"q\"}"))
				.andExpect(jsonPath("$.answer").value("IRRELEVANT_UNDECIDED"))
				.andExpect(jsonPath("$.key").doesNotExist());
	}
}
