import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.content import ContentRepository, parse_keywords
from app.judges import Completion
from app.main import app
from app.prompts import ANSWERS, GUESS_RULES, GUESS_SCHEMA, QUESTION_RULES, QUESTION_SCHEMA, HistoryItem, JudgePrompts

CODES = ["YES", "NO", "PARTIAL_SOME", "PARTIAL_CONDITION", "IRRELEVANT_UNRELATED",
         "IRRELEVANT_UNDECIDED", "INVALID_FORM", "INVALID_TRUTH", "INVALID_RULE"]


@pytest.fixture(scope="module")
def content():
    return ContentRepository(get_settings().game_content_dir)


@pytest.fixture(scope="module")
def client(monkeypatch_module):
    monkeypatch_module.setenv("LLM_PROVIDER", "fake")
    get_settings.cache_clear()
    with TestClient(app) as c:
        yield c
    get_settings.cache_clear()


@pytest.fixture(scope="module")
def monkeypatch_module():
    mp = pytest.MonkeyPatch()
    yield mp
    mp.undo()


def test_loads_base_and_scenarios(content):
    assert len(content.base) == 10
    assert content.scenarios


def test_every_scenario_is_complete(content):
    for s in content.scenarios.values():
        assert s.title and s.problem and s.truth, s.slug
        assert s.keywords, f"{s.slug} 핵심 키워드"
        assert s.hints, f"{s.slug} 힌트"


def test_every_uses_id_exists_in_world(content):
    assert content.missing_uses() == {}


def test_prompt_carries_truth_and_used_details(content):
    s = content.scenario("boys-order")
    prompt = JudgePrompts(content, 40).question(s, [], "질문")
    assert s.truth in prompt.system
    assert "(syndicate.fingers.thumb)" in prompt.system
    assert "## 출처" not in prompt.system
    assert "<question>\n질문\n</question>" in prompt.user


def test_fake_judge_flow(client):
    assert client.get("/health").json()["fake"] is True

    r = client.post("/judge/question", json={"scenario": "boys-order", "question": "세입자들은 죽었나요?"})
    assert r.status_code == 200
    assert r.json()["answer"] == "IRRELEVANT_UNRELATED"
    assert r.json()["proposition"] == "" and r.json()["reason"] == "가짜 판정기"

    r = client.post("/judge/guess", json={"scenario": "boys-order", "guess": "모르겠어요"})
    assert r.status_code == 200
    assert {k: v for k, v in r.json().items() if k != "metrics"} == {
        "matched": [], "total": 3, "bonus": [], "bonusTotal": 1, "story": False, "reason": "가짜 판정기"}


def test_unknown_scenario_is_404(client):
    r = client.post("/judge/question", json={"scenario": "nope", "question": "질문"})
    assert r.status_code == 404


def test_answers_are_nine_codes():
    assert ANSWERS == CODES
    # 명제를 먼저 쓰고 판정한다.
    assert QUESTION_SCHEMA["required"] == ["proposition", "reason", "answer"]
    assert "key" not in QUESTION_SCHEMA["properties"]


def test_rules_explain_every_code():
    for code in CODES:
        assert code in QUESTION_RULES


def test_history_uses_new_codes(content):
    s = content.scenario("boys-order")
    prompt = JudgePrompts(content, 40).question(s, [HistoryItem("건물주는 몇 살?", "IRRELEVANT_UNDECIDED")], "q")
    assert "- 건물주는 몇 살? → IRRELEVANT_UNDECIDED" in prompt.user


def test_history_carries_proposition_when_known(content):
    s = content.scenario("boys-order")
    history = [HistoryItem("꼬마는 엄지가 아닌가요?", "NO", "꼬마는 엄지가 아니다"),
               HistoryItem("왜 잘랐나요?", "INVALID_FORM")]
    user = JudgePrompts(content, 40).question(s, history, "q").user
    assert "- 꼬마는 엄지가 아닌가요? (명제: 꼬마는 엄지가 아니다) → NO" in user
    assert "- 왜 잘랐나요? → INVALID_FORM" in user


def test_history_accepts_entries_without_proposition(client):
    r = client.post("/judge/question", json={"scenario": "boys-order", "question": "q",
                                             "history": [{"text": "예전 질문", "answer": "YES"},
                                                         {"text": "새 질문", "answer": "NO", "proposition": "명제"}]})
    assert r.status_code == 200


def _answering(data):
    class Fixed:
        name, fake = "fixed", True

        async def complete(self, prompt):
            return Completion(data, "fixed", 0)

    return Fixed()


def test_question_returns_proposition_and_reason(client):
    saved = app.state.judge
    app.state.judge = _answering({"proposition": " 꼬마는 엄지가 아니다 ", "reason": "근거", "answer": "NO"})
    try:
        body = client.post("/judge/question", json={"scenario": "boys-order", "question": "꼬마는 엄지가 아닌가요?"}).json()
        assert (body["answer"], body["proposition"], body["reason"]) == ("NO", "꼬마는 엄지가 아니다", "근거")
    finally:
        app.state.judge = saved


def test_rejected_answer_has_no_proposition(client):
    saved = app.state.judge
    app.state.judge = _answering({"proposition": "왜 잘랐다", "reason": "왜 질문", "answer": "INVALID_FORM"})
    try:
        body = client.post("/judge/question", json={"scenario": "boys-order", "question": "왜 잘랐나요?"}).json()
        assert body["answer"] == "INVALID_FORM" and body["proposition"] == ""
    finally:
        app.state.judge = saved


def test_old_answer_code_is_rejected(client):
    class OldJudge:
        name, fake = "old", True

        async def complete(self, prompt):
            # 예전 형식 그대로(key 포함). 코드만 목록에 없어야 거부된다.
            return Completion({"proposition": "", "reason": "r", "answer": "PARTIAL", "key": False}, "old", 0)

    saved = app.state.judge
    app.state.judge = OldJudge()
    try:
        r = client.post("/judge/question", json={"scenario": "boys-order", "question": "q"})
        assert r.status_code == 502
    finally:
        app.state.judge = saved


def test_parses_keywords_and_bonuses(content):
    s = content.scenario("boys-order")
    assert [k.name for k in s.keywords] == ["아버지의 권위", "해낼 수 없는 지시", "엄지의 규율"]
    assert s.keywords[0].required == []
    assert s.keywords[2].required == ["엄지"]
    assert "꼬마가 무서웠다" in s.keywords[0].reject
    assert s.keywords[1].missing == "꼬마가 울면서 한 한마디가 무엇이었는지가 빠져 있구나."
    assert [b.name for b in s.bonuses] == ["검지의 습격"]


def test_keyword_lines_in_any_order():
    ks = parse_keywords("1. **가**: 설명\n   - 빠졌을 때: 문장.\n   - 인정: a, b\n")
    assert ks[0].accept == ["a", "b"] and ks[0].required == [] and ks[0].missing == "문장."


def test_materials_carry_keywords_not_missing_lines(content):
    m = JudgePrompts(content, 40).materials("boys-order")
    assert "## [핵심 키워드]" in m and "## [보너스 키워드]" in m
    assert "필수 용어: 엄지" in m and "불인정:" in m
    assert "빠졌을 때" not in m and "## [핵심 포인트]" not in m


def test_guess_schema_has_bonus():
    assert GUESS_SCHEMA["required"] == ["reason", "matched", "bonus", "story"]


def test_guess_rules_mention_required_terms():
    for word in ["필수 용어", "인정", "불인정", "[보너스 키워드]"]:
        assert word in GUESS_RULES


def test_guess_numbers_are_cleaned(client):
    class Messy:
        name, fake = "messy", True

        async def complete(self, prompt):
            return Completion({"reason": "r", "matched": [3, 3, 0, 99, 1], "bonus": [1, 5], "story": True}, "messy", 0)

    saved = app.state.judge
    app.state.judge = Messy()
    try:
        r = client.post("/judge/guess", json={"scenario": "boys-order", "guess": "g"})
        assert {k: v for k, v in r.json().items() if k != "metrics"} == {
            "matched": [1, 3], "total": 3, "bonus": [1], "bonusTotal": 1, "story": True, "reason": "r"}
    finally:
        app.state.judge = saved


def test_every_scenario_uses_new_format(content):
    for s in content.scenarios.values():
        assert s.keywords and 1 <= len(s.bonuses) <= 2, s.slug
        for k in s.keywords:
            assert k.missing and k.missing != "아직 빠진 것이 있구나.", (s.slug, k.name)
            assert k.name not in k.missing, (s.slug, k.name)
            for term in k.required:
                assert term not in k.missing, (s.slug, term)


def test_required_terms_are_not_too_strict(content):
    # 두 용어를 함께 요구하는 키워드(R사+부화장)는 팬에게도 깐깐하다.
    assert content.scenario("one-week").keywords[0].required == ["R사"]


def test_openai_timeout_and_retries():
    # 평소 판정은 3초 안팎. 12초씩 최대 2번(재시도 1번) = 24초 안에 끝나야
    # 백엔드(28초)보다 먼저 포기해서 버려지는 요청이 없다.
    from app.config import Settings
    from app.judges import OpenAiJudge

    s = Settings(openai_api_key="test")
    assert s.openai_timeout == 12.0 and s.openai_max_retries == 1
    judge = OpenAiJudge(s)
    assert judge.client.max_retries == 1
    assert judge.client.timeout == 12.0


def test_question_and_guess_use_their_own_effort():
    # 기본값은 둘 다 low다. 질문과 추리 채점은 각자의 설정을 보낸다.
    import asyncio
    from types import SimpleNamespace

    from app.config import Settings
    from app.judges import OpenAiJudge
    from app.prompts import GUESS_SCHEMA, QUESTION_SCHEMA, Prompt

    defaults = Settings(openai_api_key="test")
    assert defaults.openai_question_reasoning_effort == "low"
    assert defaults.openai_guess_reasoning_effort == "low"

    s = Settings(openai_api_key="test", openai_question_reasoning_effort="minimal",
                 openai_guess_reasoning_effort="medium")
    judge = OpenAiJudge(s)
    sent = []

    async def fake_create(**kwargs):
        sent.append(kwargs.get("reasoning_effort"))
        message = SimpleNamespace(refusal=None, content='{"reason": "r", "answer": "YES"}')
        return SimpleNamespace(choices=[SimpleNamespace(message=message)])

    judge.client.chat.completions.create = fake_create
    asyncio.run(judge.complete(Prompt("s", "u", "question_judgement", QUESTION_SCHEMA)))
    asyncio.run(judge.complete(Prompt("s", "u", "guess_judgement", GUESS_SCHEMA)))
    assert sent == ["minimal", "medium"]
