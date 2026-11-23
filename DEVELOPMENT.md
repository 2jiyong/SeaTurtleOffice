# 개발 가이드

## 구조
```
브라우저 ──/api──▶ backend (Java, 8080) ──HTTP──▶ ai (Python, 8000) ──▶ OpenAI
                   게임 진행·시나리오 목록·힌트·진상     판정 프롬프트·세계관·LLM 호출
```

- `frontend/` — React + Vite + TypeScript (5173). `/api` 요청은 개발 서버가 백엔드(8080)로 넘긴다.
- `backend/` — Spring Boot 4.2 (Java 25, Gradle). 게임 진행을 맡는다. 시작할 때 `scenarios/`를 읽는다.
  판정은 직접 하지 않고 AI 서버에 요청한다. OpenAI 키를 모른다.
- `ai/` — FastAPI (Python, uv). 판정을 통째로 맡는다. 시작할 때 `world/`와 `scenarios/`를 읽어
  프롬프트를 만들고 OpenAI에 묻는다. 게임 상태는 갖지 않고, 지금까지의 질문 기록은 요청마다 받는다.
- 게임 진행 상태는 백엔드 메모리에만 있다(DB 없음). 백엔드를 다시 켜면 진행 중인 게임은 사라진다.

## 실행
세 개를 각각 켠다.

AI 서버(포트 8000):

```bash
cd ai
cp .env.example .env   # 처음 한 번. .env에 OPENAI_API_KEY를 적는다.
uv run uvicorn app.main:app --port 8000 --reload
```

`ai/.env`는 `.gitignore`에 들어 있어 커밋되지 않는다. 환경 변수로 직접 넘겨도 되고, 둘 다 있으면 환경 변수가 이긴다.
키가 없으면 가짜 판정기로 켜진다(모든 질문에 "상관없음"). 화면 상단에 안내가 뜬다.

백엔드(포트 8080):

```bash
cd backend && ./gradlew bootRun
```

AI 서버 주소가 다르면 `AI_BASE_URL`로 바꾼다(기본 `http://localhost:8000`). AI 서버가 꺼져 있으면 화면 상단에 안내가 뜬다.

프런트엔드(포트 5173):

```bash
cd frontend && npm install && npm run dev
```

## AI 서버 API
| 요청 | 본문 | 응답 |
|---|---|---|
| `GET /health` | | `{judge, fake, scenarios}` |
| `POST /judge/question` | `{scenario, question, history: [{text, answer}]}` | `{answer, key}` |
| `POST /judge/guess` | `{scenario, guess}` | `{matched, total}` |

`scenario`는 시나리오 파일 이름(확장자 없이)이다. `http://localhost:8000/docs`에서 직접 호출해 볼 수 있다.

## 판정기(LLM) 설정
`ai/.env` 또는 환경 변수로 정한다.

| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `LLM_PROVIDER` | `auto` | `auto`(키가 있으면 openai), `openai`, `fake` |
| `OPENAI_API_KEY` | | |
| `OPENAI_MODEL` | `gpt-5-mini` | |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | OpenAI 호환 서버도 쓸 수 있다 |
| `OPENAI_REASONING_EFFORT` | `low` | 추론 모델이 아니면 빈 값으로 둔다 |
| `HISTORY_LIMIT` | `40` | 프롬프트에 넣을 최근 질문 수 |

다른 공급자를 붙이려면 `ai/app/judges.py`에 `Judge`를 구현하고 `make_judge`에 등록한다.

## 판정 방식
- 질문: 베이스 세계관 전체 + 시나리오 `uses`의 상세 항목 + 시나리오(문제·진상·핵심 포인트·판정 메모)를 프롬프트에 넣는다(`ai/app/prompts.py`).
  답은 `YES`(예), `NO`(아니오), `PARTIAL`(부분적으로 예), `IRRELEVANT`(상관없음), `INVALID`(예/아니오로 답할 수 없음) 중 하나이고,
  핵심 포인트에 닿는 질문이면 "핵심 질문" 표시를 붙인다.
- 정답 제출: 답안 하나가 핵심 포인트를 모두 충족하면 정답이다. 플레이어에게는 몇 개를 맞혔는지만 보여 준다.
- 판정은 JSON 스키마로 받으므로 자유 문장이 플레이어에게 새지 않는다. 판정 근거(`reason`)는 AI 서버 로그에만 남는다.

## 테스트
```bash
cd ai && uv run pytest        # 세계관·시나리오 읽기, uses ID 검사, 프롬프트, API
cd backend && ./gradlew test  # 시나리오 읽기, 게임 흐름(가짜 판정기)
```
