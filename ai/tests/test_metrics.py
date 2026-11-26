"""판정 응답의 metrics(시간, 모델, 토큰). 백엔드가 이것으로 events를 쓴다."""

import asyncio
from types import SimpleNamespace

import httpx
import openai
import pytest
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.judges import Completion, JudgeError, OpenAiJudge
from app.main import app
from app.prompts import QUESTION_SCHEMA, Prompt

METRIC_KEYS = {"model", "aiMs", "llmMs", "inputTokens", "outputTokens", "cachedTokens"}


@pytest.fixture(scope="module")
def client():
    mp = pytest.MonkeyPatch()
    mp.setenv("LLM_PROVIDER", "fake")
    get_settings.cache_clear()
    with TestClient(app) as c:
        yield c
    get_settings.cache_clear()
    mp.undo()


def test_fake_question_has_metrics(client):
    r = client.post("/judge/question", json={"scenario": "boys-order", "question": "질문"})
    metrics = r.json()["metrics"]
    assert set(metrics) == METRIC_KEYS
    assert metrics["model"] == "fake"
    assert metrics["llmMs"] == 0
    assert metrics["inputTokens"] == metrics["outputTokens"] == metrics["cachedTokens"] == 0
    assert isinstance(metrics["aiMs"], int) and metrics["aiMs"] >= 0


def test_fake_guess_has_metrics(client):
    r = client.post("/judge/guess", json={"scenario": "boys-order", "guess": "모르겠어요"})
    assert set(r.json()["metrics"]) == METRIC_KEYS


def _openai_judge(create):
    judge = OpenAiJudge(Settings(openai_api_key="test"))
    judge.client.chat.completions.create = create
    return judge


def _response(content, usage):
    message = SimpleNamespace(refusal=None, content=content)
    return SimpleNamespace(choices=[SimpleNamespace(message=message)], usage=usage)


USAGE = SimpleNamespace(prompt_tokens=5210, completion_tokens=38,
                        prompt_tokens_details=SimpleNamespace(cached_tokens=4096))
PROMPT = Prompt("s", "u", "question_judgement", QUESTION_SCHEMA)


def test_openai_usage_is_read():
    async def create(**kwargs):
        return _response('{"reason": "r", "answer": "YES"}', USAGE)

    result = asyncio.run(_openai_judge(create).complete(PROMPT))
    assert isinstance(result, Completion)
    assert result.data == {"reason": "r", "answer": "YES"}
    assert (result.input_tokens, result.output_tokens, result.cached_tokens) == (5210, 38, 4096)
    assert result.model == "gpt-5-mini"
    assert result.llm_ms >= 0


def test_missing_usage_is_zero():
    async def create(**kwargs):
        return _response('{"reason": "r", "answer": "YES"}', None)

    result = asyncio.run(_openai_judge(create).complete(PROMPT))
    assert (result.input_tokens, result.output_tokens, result.cached_tokens) == (0, 0, 0)


def test_bad_json_error_carries_tokens():
    async def create(**kwargs):
        return _response("not json", USAGE)

    with pytest.raises(JudgeError) as e:
        asyncio.run(_openai_judge(create).complete(PROMPT))
    assert e.value.metrics["inputTokens"] == 5210
    assert e.value.metrics["model"] == "gpt-5-mini"


def test_openai_failure_error_has_zero_tokens():
    async def create(**kwargs):
        raise openai.APIConnectionError(request=httpx.Request("POST", "https://example.invalid"))

    with pytest.raises(JudgeError) as e:
        asyncio.run(_openai_judge(create).complete(PROMPT))
    assert e.value.metrics["inputTokens"] == 0
    assert e.value.metrics["llmMs"] >= 0


def test_502_body_has_metrics(client):
    class Broken:
        name, fake = "broken", True

        async def complete(self, prompt):
            # answer가 빠진 판정: 형식 오류로 502. 토큰은 이미 썼다.
            return Completion({"reason": "r"}, "broken-model", 7, 321, 5, 0)

    saved = app.state.judge
    app.state.judge = Broken()
    try:
        r = client.post("/judge/question", json={"scenario": "boys-order", "question": "q"})
    finally:
        app.state.judge = saved
    assert r.status_code == 502
    body = r.json()
    assert body["detail"] == "판정 중 문제가 생겼습니다."
    assert body["metrics"]["inputTokens"] == 321
    assert body["metrics"]["model"] == "broken-model"
    assert set(body["metrics"]) == METRIC_KEYS
