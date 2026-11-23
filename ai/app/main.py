"""바다거북 사무소 AI 판정 서버.

자바 게임 서버가 질문과 추리를 보내면, 세계관과 시나리오를 근거로 판정해 돌려준다.
게임 상태는 갖지 않는다. 필요한 기록(지금까지의 질문)은 요청마다 받는다.
"""

import logging
from contextlib import asynccontextmanager
from typing import Literal

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, ValidationError

from .config import get_settings
from .content import ContentRepository, Scenario
from .judges import Judge, JudgeError, make_judge
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


class QuestionResult(BaseModel):
    answer: Answer


class GuessRequest(BaseModel):
    scenario: str
    guess: str = Field(min_length=1, max_length=1000)


class GuessResult(BaseModel):
    matched: int
    total: int


class QuestionJudgement(BaseModel):
    reason: str
    answer: Answer


class GuessJudgement(BaseModel):
    reason: str
    matched: list[int]


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
    log.error("판정 실패: %s", exc)
    return JSONResponse(status_code=502, content={"detail": "판정 중 문제가 생겼습니다."})


def _scenario(request: Request, slug: str) -> Scenario:
    scenario = request.app.state.content.scenario(slug)
    if scenario is None:
        raise HTTPException(status_code=404, detail=f"시나리오를 찾을 수 없습니다: {slug}")
    return scenario


def _validate(model: type[BaseModel], raw: dict):
    try:
        return model.model_validate(raw)
    except ValidationError as e:
        raise JudgeError(f"판정 결과 형식이 맞지 않습니다: {raw}") from e


@app.get("/health")
async def health(request: Request) -> Health:
    judge: Judge = request.app.state.judge
    return Health(judge=judge.name, fake=judge.fake, scenarios=len(request.app.state.content.scenarios))


@app.post("/judge/question")
async def judge_question(body: QuestionRequest, request: Request) -> QuestionResult:
    scenario = _scenario(request, body.scenario)
    history = [HistoryItem(h.text, h.answer) for h in body.history]
    prompt = request.app.state.prompts.question(scenario, history, body.question.strip())
    result = _validate(QuestionJudgement, await request.app.state.judge.complete_json(prompt))
    # 판정 근거는 로그에만 남긴다. 플레이어에게 새지 않게 응답에는 넣지 않는다.
    log.info("[%s] Q: %s → %s / %s", scenario.slug, body.question, result.answer, result.reason)
    return QuestionResult(answer=result.answer)


@app.post("/judge/guess")
async def judge_guess(body: GuessRequest, request: Request) -> GuessResult:
    scenario = _scenario(request, body.scenario)
    prompt = request.app.state.prompts.guess(scenario, body.guess.strip())
    result = _validate(GuessJudgement, await request.app.state.judge.complete_json(prompt))
    total = len(scenario.key_points)
    matched = len({n for n in result.matched if 1 <= n <= total})
    log.info("[%s] 답안 %d/%d : %s / %s", scenario.slug, matched, total, body.guess, result.reason)
    return GuessResult(matched=matched, total=total)
