package com.seaturtle.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.seaturtle.office.game.Answer;
import com.seaturtle.office.game.GameEndedException;
import com.seaturtle.office.game.GameService;
import com.seaturtle.office.game.GameStatus;
import com.seaturtle.office.judge.Judge;

/** AI 서버 없이 게임 흐름만 본다. 판정기는 정해진 답을 하는 가짜로 바꾼다. */
@SpringBootTest
class GameFlowTests {

	static class StubJudge implements Judge {
		final List<List<HistoryEntry>> histories = new ArrayList<>();

		@Override
		public Verdict question(String scenario, List<HistoryEntry> history, String question) {
			histories.add(history);
			return new Verdict(Answer.YES, true);
		}

		@Override
		public GuessVerdict guess(String scenario, String guess) {
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

	@Test
	void playThroughAndGiveUp() {
		var game = games.start("last-rent");

		var q1 = games.ask(game.id(), "세입자들은 죽었나요?");
		var q2 = games.ask(game.id(), "건물주가 했나요?");
		assertThat(q1.no()).isEqualTo(1);
		assertThat(q2.answer()).isEqualTo(Answer.YES);
		assertThat(q2.key()).isTrue();
		// 두 번째 질문 때 첫 질문의 기록이 함께 간다.
		assertThat(judge.histories.getLast()).extracting(Judge.HistoryEntry::text).containsExactly("세입자들은 죽었나요?");

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
}
