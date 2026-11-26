"""바다거북 사무소 AI 판정 서버.

자바 게임 서버가 질문과 추리를 보내면, 세계관과 시나리오를 근거로 판정해 돌려준다.
게임 상태는 갖지 않는다. 필요한 기록(지금까지의 질문)은 요청마다 받는다.
"""

import logging
import time
from contextlib import asynccontextmanager
from typing import Literal

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, ValidationError

from .config import get_settings
from .content import ContentRepository, Scenario
from .judges import Completion, Judge, JudgeError, make_judge
from .prompts import HistoryItem, JudgePrompts

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("judge")

Answer = Literal[
    "YES",
    "NO",
    "PARTIAL_SOME",
    "PARTIAL_CONDITION",
    "IRRELEVANT_UNRELATED",
    "IRRELEVANT_UNDECIDED",
    "INVALID_FORM",
    "INVALID_TRUTH",
    "INVALID_RULE",
]


class HistoryEntry(BaseModel):
    text: str
    answer: Answer


class QuestionRequest(BaseModel):
    scenario: str
    question: str = Field(min_length=1, max_length=200)
    history: list[HistoryEntry] = []


class Metrics(BaseModel):
    """판정 하나의 비용. 시간은 ms. aiMs ⊇ llmMs."""

    model: str
    aiMs: int
    llmMs: int
    inputTokens: int
    outputTokens: int
    cachedTokens: int


class QuestionResult(BaseModel):
    answer: Answer
    metrics: Metrics


class GuessRequest(BaseModel):
    scenario: str
    guess: str = Field(min_length=1, max_length=1000)


class GuessResult(BaseModel):
    """번호는 1부터. 백엔드는 이 번호로 얼마나 가까운지만 계산해 화면에 보낸다.
    story: 답안 전체가 진상과 맞는 이야기인가. 핵심을 다 맞혀도 이것이 거짓이면 정답이 아니다."""

    matched: list[int]
    total: int
    bonus: list[int]
    bonusTotal: int
    story: bool
    metrics: Metrics


class QuestionJudgement(BaseModel):
    reason: str
    answer: Answer


class GuessJudgement(BaseModel):
    reason: str
    matched: list[int]
    bonus: list[int]
    story: bool


class Health(BaseModel):
    judge: str
    fake: bool
    scenarios: int


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    content = ContentRepository(settings.game_content_dir)
    app.state.content = content
    app.state.prompts = JudgePrompts(content, settings.history_limit)
    app.state.judge = make_judge(settings)
    yield


app = FastAPI(title="바다거북 사무소 AI 판정 서버", lifespan=lifespan)


@app.exception_handler(JudgeError)
async def judge_failed(request: Request, exc: JudgeError):
    spent = _with_ai_ms(exc.metrics, request) if exc.metrics is not None else None
    log.error("판정 실패: %s", exc, extra=_log_fields(spent))
    # 토큰을 쓴 뒤 실패해도 백엔드가 기록할 수 있게 지표를 함께 보낸다.
    return JSONResponse(status_code=502, content={"detail": "판정 중 문제가 생겼습니다.", "metrics": spent})


def _with_ai_ms(spent: dict, request: Request) -> dict:
    """엔드포인트에 들어온 뒤 지금까지의 시간(aiMs)을 더한다."""
    started = getattr(request.state, "started", None)
    ai_ms = round((time.perf_counter() - started) * 1000) if started is not None else 0
    return {**spent, "aiMs": ai_ms}


def _log_fields(spent: dict | None) -> dict:
    if not spent:
        return {}
    return {"model": spent["model"], "ai_ms": spent.get("aiMs"), "llm_ms": spent["llmMs"],
            "input_tokens": spent["inputTokens"], "output_tokens": spent["outputTokens"],
            "cached_tokens": spent["cachedTokens"]}


def _scenario(request: Request, slug: str) -> Scenario:
    scenario = request.app.state.content.scenario(slug)
    if scenario is None:
        raise HTTPException(status_code=404, detail=f"시나리오를 찾을 수 없습니다: {slug}")
    return scenario


def _validate(model: type[BaseModel], completion: Completion):
    try:
        return model.model_validate(completion.data)
    except ValidationError as e:
        raise JudgeError(f"판정 결과 형식이 맞지 않습니다: {completion.data}", completion.metrics()) from e


@app.get("/health")
async def health(request: Request) -> Health:
    judge: Judge = request.app.state.judge
    return Health(judge=judge.name, fake=judge.fake, scenarios=len(request.app.state.content.scenarios))


@app.post("/judge/question")
async def judge_question(body: QuestionRequest, request: Request) -> QuestionResult:
    request.state.started = time.perf_counter()
    scenario = _scenario(request, body.scenario)
    history = [HistoryItem(h.text, h.answer) for h in body.history]
    prompt = request.app.state.prompts.question(scenario, history, body.question.strip())
    completion = await request.app.state.judge.complete(prompt)
    result = _validate(QuestionJudgement, completion)
    spent = _with_ai_ms(completion.metrics(), request)
    # 판정 근거는 로그에만 남긴다. 플레이어에게 새지 않게 응답에는 넣지 않는다.
    log.info("[%s] Q: %s → %s / %s", scenario.slug, body.question, result.answer, result.reason,
             extra={"scenario": scenario.slug, "answer": result.answer, **_log_fields(spent)})
    return QuestionResult(answer=result.answer, metrics=Metrics(**spent))


@app.post("/judge/guess")
async def judge_guess(body: GuessRequest, request: Request) -> GuessResult:
    request.state.started = time.perf_counter()
    scenario = _scenario(request, body.scenario)
    prompt = request.app.state.prompts.guess(scenario, body.guess.strip())
    completion = await request.app.state.judge.complete(prompt)
    result = _validate(GuessJudgement, completion)
    spent = _with_ai_ms(completion.metrics(), request)
    total, bonus_total = len(scenario.keywords), len(scenario.bonuses)
    matched = _clean(result.matched, total)
    bonus = _clean(result.bonus, bonus_total)
    log.info("[%s] 답안 핵심 %s/%d 보너스 %s/%d 이야기 %s : %s / %s",
             scenario.slug, matched, total, bonus, bonus_total, result.story, body.guess, result.reason,
             extra={"scenario": scenario.slug, **_log_fields(spent)})
    return GuessResult(matched=matched, total=total, bonus=bonus, bonusTotal=bonus_total, story=result.story,
                       metrics=Metrics(**spent))


def _clean(numbers: list[int], total: int) -> list[int]:
    """AI가 준 번호에서 중복과 범위 밖 값을 버린다."""
    return sorted({n for n in numbers if 1 <= n <= total})
