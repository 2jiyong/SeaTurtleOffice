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

## 시나리오를 고쳤을 때
두 서버 모두 시작할 때 `scenarios/`를 한 번 읽어 둔다. 시나리오나 세계관 문서를 고치면 **AI 서버와 백엔드를 모두 다시 켠다.**
한쪽만 다시 켜면 두 서버의 진상이 어긋난다(핵심 포인트 개수가 달라진 경우만 추리 제출 때 오류로 잡힌다).

## AI 서버 API
| 요청 | 본문 | 응답 |
|---|---|---|
| `GET /health` | | `{judge, fake, scenarios}` |
| `POST /judge/question` | `{scenario, question, history: [{text, answer}]}` | `{answer}` |
| `POST /judge/guess` | `{scenario, guess}` | `{matched: [번호], total, bonus: [번호], bonusTotal}` |

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
  답은 아래 9가지 코드 중 하나다. 서버끼리는 코드만 주고받고, 진행자의 말과 안내 문장은 `frontend/src/api.ts`의 `ANSWER_TEXT`에만 있다.

  | 코드 | 진행자의 말 | 안내 문장 |
  |---|---|---|
  | `YES` | 그렇단다 | 진상에 비추어 맞는 내용입니다. |
  | `NO` | 아니란다 | 진상에 비추어 틀린 내용입니다. |
  | `PARTIAL_SOME` | 어느정도는 그렇단다 | 일부만 맞는 내용입니다. |
  | `PARTIAL_CONDITION` | 어느정도는 그렇단다 | 조건을 붙이면 맞는 내용입니다. |
  | `IRRELEVANT_UNRELATED` | 중요하지 않단다 | 이 사건과 관련이 없는 내용입니다. |
  | `IRRELEVANT_UNDECIDED` | 중요하지 않단다 | 이 사건에서 정하지 않은 세부사항입니다. |
  | `INVALID_FORM` | 말해줄 수 없단다 | 예·아니오로 답할 수 없는 질문입니다. |
  | `INVALID_TRUTH` | 말해줄 수 없단다 | 진상을 직접 묻는 질문입니다. |
  | `INVALID_RULE` | 말해줄 수 없단다 | 규칙을 벗어난 요청입니다. |

  도시의 일반 상식을 묻는 질문은 이 사건과 이어질 때만 진상과 세계관에 따라 답하고, 아니면 `IRRELEVANT_UNRELATED`다.
  판정 메모의 "예 / 아니오 / 상관없음 / 예로 볼 수 있다"는 `YES` / `NO` / `IRRELEVANT_*` / `PARTIAL_*`로 읽는다.
- 정답 제출: 답안 하나가 시나리오의 **핵심 키워드**를 모두 충족하면 정답이다(형식은 `scenarios/README.md`). 필수 용어가 있는 키워드는 그 세계관 용어를 써야 충족이다.
  오답이면 "핵심 n/m"과 함께 빠진 키워드마다 시나리오의 "빠졌을 때" 문장을 보여 준다. AI는 키워드 번호만 판정하고 문장은 쓰지 않는다.
- 보너스: 시나리오의 **보너스 키워드**(곁가지 1~2개)도 추리마다 판정해 모아 두고, 게임이 끝날 때 진상 공개에서만 보여 준다. 정답 여부와는 상관없다.
- 판정은 JSON 스키마로 받으므로 자유 문장이 플레이어에게 새지 않는다. 판정 근거(`reason`)는 AI 서버 로그에만 남는다.

## 테스트
```bash
cd ai && uv run pytest        # 세계관·시나리오 읽기, uses ID 검사, 프롬프트, API
cd backend && ./gradlew test  # 시나리오 읽기, 게임 흐름(가짜 판정기), AI 서버와 핵심 포인트 수 대조
cd frontend && npm test       # 요청 진행 중 버튼 잠금
```
