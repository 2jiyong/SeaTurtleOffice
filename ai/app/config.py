from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

AI_DIR = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    """ai/.env 또는 환경 변수에서 읽는다. 둘 다 있으면 환경 변수가 이긴다."""

    model_config = SettingsConfigDict(env_file=AI_DIR / ".env", env_file_encoding="utf-8", extra="ignore")

    # world/, scenarios/가 있는 저장소 루트
    game_content_dir: Path = AI_DIR.parent

    # auto: OPENAI_API_KEY가 있으면 openai, 없으면 fake(모든 질문에 '상관없음')
    llm_provider: str = "auto"
    openai_api_key: str = ""
    openai_base_url: str = "https://api.openai.com/v1"
    openai_model: str = "gpt-5-mini"
    # AI가 생각하는 양. 추론 모델에만 보낸다. 비우면 보내지 않는다.
    # 둘 다 low(질문 ≈ 3초, 추리 채점 ≈ 3~7초). minimal(≈ 2초)은 판정 규칙을 잘 따르지 못해 운영 질문 재판정에서 오답이 많았다(2026-11-27).
    openai_question_reasoning_effort: str = "low"
    openai_guess_reasoning_effort: str = "low"
    # 요청 하나를 기다리는 시간(초)과 재시도 횟수. 최악 = timeout × (1 + max_retries) = 24초.
    # 백엔드(judge.timeout 28초)보다 먼저 끝나야 결과가 버려지지 않는다.
    openai_timeout: float = 12.0
    openai_max_retries: int = 1

    # OpenAI 처리 등급. priority(Fast mode)는 같은 추론을 더 빨리 돌려 질문 판정이 약 5초 → 2.4초가 된다(2026-11-28 측정).
    # 단가는 표준의 1.8배라 backend limits.price도 같은 등급 단가로 맞춘다. 비우면 보내지 않는다(표준).
    openai_service_tier: str = "priority"

    # text: 사람이 읽는 한 줄(개발), json: JSON 한 줄(배포)
    log_format: str = "text"

    # 판정 프롬프트에 넣을 최근 질문 수
    history_limit: int = 40


@lru_cache
def get_settings() -> Settings:
    return Settings()
