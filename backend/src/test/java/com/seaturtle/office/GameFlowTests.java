package com.seaturtle.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.seaturtle.office.content.ContentRepository;
import com.seaturtle.office.content.Scenario;
import com.seaturtle.office.game.Answer;
import com.seaturtle.office.game.GameEndedException;
import com.seaturtle.office.game.GameService;
import com.seaturtle.office.game.GameStatus;
import com.seaturtle.office.game.JudgePrompts;

@SpringBootTest(properties = "llm.provider=fake")
class GameFlowTests {

	@Autowired
	GameService games;

	@Autowired
	ContentRepository content;

	@Autowired
	JudgePrompts prompts;

	@Test
	void playThroughWithFakeJudge() {
		var game = games.start("last-rent");

		var q = games.ask(game.id(), "세입자들은 죽었나요?");
		assertThat(q.no()).isEqualTo(1);
		assertThat(q.answer()).isEqualTo(Answer.IRRELEVANT);

		games.hint(game.id());
		assertThat(game.hintsUsed()).isEqualTo(1);

		games.guess(game.id(), "모르겠어요");
		assertThat(game.guesses()).hasSize(1);
		assertThat(game.guesses().getFirst().correct()).isFalse();
		assertThat(game.status()).isEqualTo(GameStatus.PLAYING);

		games.giveUp(game.id());
		assertThat(game.status()).isEqualTo(GameStatus.GAVE_UP);
		assertThatThrownBy(() -> games.ask(game.id(), "또 질문")).isInstanceOf(GameEndedException.class);
	}

	@Test
	void promptCarriesTruthAndUsedDetails() {
		Scenario s = content.scenario("last-rent").orElseThrow();
		var prompt = prompts.question(s, java.util.List.of(), "질문");
		assertThat(prompt.system()).contains(s.truth()).contains("(syndicate.sweepers)").doesNotContain("## 출처");
		assertThat(prompt.user()).contains("<question>\n질문\n</question>");
	}
}
