"""LLM 판정기. 공급자를 바꾸려면 Judge를 구현하고 make_judge에 등록한다."""

import json
import logging
import time
from dataclasses import dataclass
from typing import Protocol

from openai import AsyncOpenAI, OpenAIError

from .config import Settings
from .prompts import Prompt

log = logging.getLogger(__name__)


@dataclass
class Completion:
    """LLM 한 번의 결과와 그 비용. 시간은 ms, 토큰은 OpenAI usage 그대로(없으면 0)."""

    data: dict
    model: str
    llm_ms: int
    input_tokens: int = 0
    output_tokens: int = 0
    cached_tokens: int = 0

    def metrics(self) -> dict:
        return metrics(self.model, self.llm_ms, self.input_tokens, self.output_tokens, self.cached_tokens)


def metrics(model: str, llm_ms: int, input_tokens: int = 0, output_tokens: int = 0, cached_tokens: int = 0) -> dict:
    """응답의 metrics 모양(aiMs는 엔드포인트가 더한다)."""
    return {"model": model, "llmMs": llm_ms, "inputTokens": input_tokens,
            "outputTokens": output_tokens, "cachedTokens": cached_tokens}


class JudgeError(Exception):
    """판정을 받지 못했다(요청 실패, 거부, 형식 오류). 그때까지 쓴 시간·토큰을 metrics로 함께 넘긴다."""

    def __init__(self, message: str, metrics: dict | None = None):
        super().__init__(message)
        self.metrics = metrics


class Judge(Protocol):
    name: str
    fake: bool

    async def complete(self, prompt: Prompt) -> Completion: ...


class FakeJudge:
    """API 키 없이 화면을 돌려 볼 때 쓰는 가짜 판정기. 항상 같은 답을 한다."""

    name = "가짜 판정기"
    fake = True

    async def complete(self, prompt: Prompt) -> Completion:
        if prompt.schema_name == "question_judgement":
            return Completion({"reason": "가짜 판정기", "answer": "IRRELEVANT_UNRELATED"}, "fake", 0)
        if prompt.schema_name == "guess_judgement":
            return Completion({"reason": "가짜 판정기", "matched": [], "bonus": [], "story": False}, "fake", 0)
        raise JudgeError(f"알 수 없는 스키마: {prompt.schema_name}", metrics("fake", 0))


class OpenAiJudge:
    """OpenAI Chat Completions API(호환 서버 포함)의 structured outputs를 쓴다."""

    fake = False

    def __init__(self, settings: Settings):
        self.settings = settings
        self.name = f"OpenAI {settings.openai_model}"
        self.client = AsyncOpenAI(
            api_key=settings.openai_api_key,
            base_url=settings.openai_base_url,
            timeout=settings.openai_timeout,
            max_retries=settings.openai_max_retries,
        )

    async def complete(self, prompt: Prompt) -> Completion:
        model = self.settings.openai_model
        extra = {}
        effort = (
            self.settings.openai_question_reasoning_effort
            if prompt.schema_name == "question_judgement"
            else self.settings.openai_guess_reasoning_effort
        )
        if effort:
            extra["reasoning_effort"] = effort
        started = time.perf_counter()
        try:
            response = await self.client.chat.completions.create(
                model=model,
                messages=[
                    {"role": "system", "content": prompt.system},
                    {"role": "user", "content": prompt.user},
                ],
                response_format={
                    "type": "json_schema",
                    "json_schema": {"name": prompt.schema_name, "strict": True, "schema": prompt.schema},
                },
                **extra,
            )
        except OpenAIError as e:
            raise JudgeError(f"OpenAI 요청이 실패했습니다: {e}", metrics(model, _ms_since(started))) from e
        llm_ms = _ms_since(started)
        usage = _usage(response)
        spent = metrics(model, llm_ms, *usage)

        message = response.choices[0].message if response.choices else None
        if message is None:
            raise JudgeError("OpenAI 응답이 비어 있습니다", spent)
        if message.refusal:
            raise JudgeError(f"모델이 응답을 거부했습니다: {message.refusal}", spent)
        if not message.content:
            raise JudgeError("OpenAI 응답에 내용이 없습니다", spent)
        try:
            data = json.loads(message.content)
        except json.JSONDecodeError as e:
            raise JudgeError(f"판정 결과를 읽을 수 없습니다: {message.content}", spent) from e
        return Completion(data, model, llm_ms, *usage)


def _ms_since(started: float) -> int:
    return round((time.perf_counter() - started) * 1000)


def _usage(response) -> tuple[int, int, int]:
    """(입력, 출력, 캐시된 입력) 토큰. usage나 하위 값이 없으면 0."""
    usage = getattr(response, "usage", None)
    if usage is None:
        return 0, 0, 0
    details = getattr(usage, "prompt_tokens_details", None)
    return (getattr(usage, "prompt_tokens", None) or 0,
            getattr(usage, "completion_tokens", None) or 0,
            (getattr(details, "cached_tokens", None) or 0) if details is not None else 0)


def make_judge(settings: Settings) -> Judge:
    provider = (settings.llm_provider or "auto").lower()
    has_key = bool(settings.openai_api_key.strip())
    if provider == "auto":
        provider = "openai" if has_key else "fake"
    if provider == "openai":
        if not has_key:
            raise RuntimeError("OPENAI_API_KEY가 없습니다. 키 없이 화면만 보려면 LLM_PROVIDER=fake로 실행하세요.")
        log.info("LLM: OpenAI %s (%s)", settings.openai_model, settings.openai_base_url)
        return OpenAiJudge(settings)
    if provider == "fake":
        log.warning("LLM: 가짜 판정기를 씁니다. 모든 질문에 '상관없음'으로 답합니다.")
        return FakeJudge()
    raise RuntimeError(f"알 수 없는 LLM_PROVIDER: {settings.llm_provider}")
