import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.content import ContentRepository
from app.main import app
from app.prompts import ANSWERS, QUESTION_RULES, QUESTION_SCHEMA, HistoryItem, JudgePrompts

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
        assert s.key_points, f"{s.slug} 핵심 포인트"
        assert s.hints, f"{s.slug} 힌트"


def test_every_uses_id_exists_in_world(content):
    assert content.missing_uses() == {}


def test_prompt_carries_truth_and_used_details(content):
    s = content.scenario("last-rent")
    prompt = JudgePrompts(content, 40).question(s, [], "질문")
    assert s.truth in prompt.system
    assert "(syndicate.sweepers)" in prompt.system
    assert "## 출처" not in prompt.system
    assert "<question>\n질문\n</question>" in prompt.user


def test_fake_judge_flow(client):
    assert client.get("/health").json()["fake"] is True

    r = client.post("/judge/question", json={"scenario": "last-rent", "question": "세입자들은 죽었나요?"})
    assert r.status_code == 200
    assert r.json() == {"answer": "IRRELEVANT_UNRELATED"}

    r = client.post("/judge/guess", json={"scenario": "last-rent", "guess": "모르겠어요"})
    assert r.status_code == 200
    assert r.json()["matched"] == 0
    assert r.json()["total"] > 0


def test_unknown_scenario_is_404(client):
    r = client.post("/judge/question", json={"scenario": "nope", "question": "질문"})
    assert r.status_code == 404


def test_answers_are_nine_codes():
    assert ANSWERS == CODES
    assert QUESTION_SCHEMA["required"] == ["reason", "answer"]
    assert "key" not in QUESTION_SCHEMA["properties"]


def test_rules_explain_every_code():
    for code in CODES:
        assert code in QUESTION_RULES


def test_history_uses_new_codes(content):
    s = content.scenario("last-rent")
    prompt = JudgePrompts(content, 40).question(s, [HistoryItem("건물주는 몇 살?", "IRRELEVANT_UNDECIDED")], "q")
    assert "- 건물주는 몇 살? → IRRELEVANT_UNDECIDED" in prompt.user


def test_old_answer_code_is_rejected(client):
    class OldJudge:
        name, fake = "old", True

        async def complete_json(self, prompt):
            # 예전 형식 그대로(key 포함). 코드만 목록에 없어야 거부된다.
            return {"reason": "r", "answer": "PARTIAL", "key": False}

    saved = app.state.judge
    app.state.judge = OldJudge()
    try:
        r = client.post("/judge/question", json={"scenario": "last-rent", "question": "q"})
        assert r.status_code == 502
    finally:
        app.state.judge = saved
