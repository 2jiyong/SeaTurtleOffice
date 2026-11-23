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
    # 추론 모델에만 보낸다. 비우면 보내지 않는다.
    openai_reasoning_effort: str = "low"
    openai_timeout: float = 60.0

    # 판정 프롬프트에 넣을 최근 질문 수
    history_limit: int = 40


@lru_cache
def get_settings() -> Settings:
    return Settings()
