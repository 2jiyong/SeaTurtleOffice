# 배포용 AI 판정 서버 이미지. 서버에서 직접 빌드한다(compose.prod.yaml).
FROM python:3.12-slim
COPY --from=ghcr.io/astral-sh/uv:0.13.8 /uv /bin/uv
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never
WORKDIR /app
# 의존성을 먼저 깔아 두면 코드만 바뀐 배포에서 이 층을 다시 쓴다.
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev
COPY app app
RUN useradd --system --uid 10001 app
USER app
EXPOSE 8000
# 접속 로그는 끈다. 요청마다 백엔드 접속 로그와 판정 로그(요청 ID 포함)가 이미 남는다.
CMD ["/app/.venv/bin/uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
