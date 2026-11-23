"""LLM 판정기. 공급자를 바꾸려면 Judge를 구현하고 make_judge에 등록한다."""

import json
import logging
from typing import Protocol

from openai import AsyncOpenAI, OpenAIError

from .config import Settings
from .prompts import Prompt

log = logging.getLogger(__name__)


class JudgeError(Exception):
    """판정을 받지 못했다(요청 실패, 거부, 형식 오류)."""


class Judge(Protocol):
    name: str
    fake: bool

    async def complete_json(self, prompt: Prompt) -> dict: ...


class FakeJudge:
    """API 키 없이 화면을 돌려 볼 때 쓰는 가짜 판정기. 항상 같은 답을 한다."""

    name = "가짜 판정기"
    fake = True

    async def complete_json(self, prompt: Prompt) -> dict:
        if prompt.schema_name == "question_judgement":
            return {"reason": "가짜 판정기", "answer": "IRRELEVANT_UNRELATED"}
        if prompt.schema_name == "guess_judgement":
            return {"reason": "가짜 판정기", "matched": []}
        raise JudgeError(f"알 수 없는 스키마: {prompt.schema_name}")


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
        )

    async def complete_json(self, prompt: Prompt) -> dict:
        extra = {}
        if self.settings.openai_reasoning_effort:
            extra["reasoning_effort"] = self.settings.openai_reasoning_effort
        try:
            response = await self.client.chat.completions.create(
                model=self.settings.openai_model,
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
            raise JudgeError(f"OpenAI 요청이 실패했습니다: {e}") from e

        message = response.choices[0].message if response.choices else None
        if message is None:
            raise JudgeError("OpenAI 응답이 비어 있습니다")
        if message.refusal:
            raise JudgeError(f"모델이 응답을 거부했습니다: {message.refusal}")
        if not message.content:
            raise JudgeError("OpenAI 응답에 내용이 없습니다")
        try:
            return json.loads(message.content)
        except json.JSONDecodeError as e:
            raise JudgeError(f"판정 결과를 읽을 수 없습니다: {message.content}") from e


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
