# 개발 가이드

## 구조
```
브라우저 ──/api──▶ backend (Java, 8080) ──HTTP──▶ ai (Python, 8000) ──▶ OpenAI
                   게임 진행·시나리오 목록·힌트·진상     판정 프롬프트·세계관·LLM 호출
                      │
                      ▼
                   PostgreSQL (5433) 게임 진행 저장
```

- `frontend/` — React + Vite + TypeScript (5173). `/api` 요청은 개발 서버가 백엔드(8080)로 넘긴다.
- `backend/` — Spring Boot 4.2 (Java 25, Gradle). 게임 진행을 맡는다. 시작할 때 `scenarios/`를 읽는다.
  판정은 직접 하지 않고 AI 서버에 요청한다. OpenAI 키를 모른다.
- `ai/` — FastAPI (Python, uv). 판정을 통째로 맡는다. 시작할 때 `world/`와 `scenarios/`를 읽어
  프롬프트를 만들고 OpenAI에 묻는다. 게임 상태는 갖지 않고, 지금까지의 질문 기록은 요청마다 받는다.
- 게임 진행은 PostgreSQL에 저장된다. 백엔드를 다시 켜도 남는다. 테이블은 Flyway가 만든다(`backend/src/main/resources/db/migration/`).

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

DB는 따로 켜지 않아도 된다. `bootRun`이 저장소 루트의 `compose.yaml`로 PostgreSQL 컨테이너를 켜고 연결한다(Docker 필요, 이 저장소는 OrbStack을 쓴다).
백엔드를 꺼도 컨테이너는 멈추기만 하고 데이터는 `pgdata` 볼륨에 남는다. 직접 켜려면 루트에서 `docker compose up -d`. 다른 프로젝트의 Postgres(5432)와 부딪히지 않게 호스트 포트는 5433이다.
배포에서는 `DB_URL`, `DB_USER`, `DB_PASSWORD`로 넘긴다(기본값은 로컬 compose와 같다).

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
| `POST /judge/question` | `{scenario, question, history: [{text, answer}]}` | `{answer, metrics}` |
| `POST /judge/guess` | `{scenario, guess}` | `{matched: [번호], total, bonus: [번호], bonusTotal, story, metrics}` |

`metrics`는 판정 하나의 비용이다: `{model, aiMs, llmMs, inputTokens, outputTokens, cachedTokens}`(시간은 ms, 가짜 판정기는 `model: "fake"`에 토큰 0).
판정이 실패한 502 본문에도 그때까지 쓴 `metrics`가 실린다. 요청 헤더 `X-Request-Id`를 받으면 그 값을 로그에 찍고 응답 헤더로 돌려준다.

`scenario`는 시나리오 파일 이름(확장자 없이)이다. `http://localhost:8000/docs`에서 직접 호출해 볼 수 있다.

## 판정기(LLM) 설정
`ai/.env` 또는 환경 변수로 정한다.

| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `LLM_PROVIDER` | `auto` | `auto`(키가 있으면 openai), `openai`, `fake` |
| `OPENAI_API_KEY` | | |
| `OPENAI_MODEL` | `gpt-5-mini` | |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | OpenAI 호환 서버도 쓸 수 있다 |
| `OPENAI_QUESTION_REASONING_EFFORT` | `minimal` | 질문 판정에서 AI가 생각하는 양. 빠르게(약 2초). 추론 모델이 아니면 빈 값으로 둔다 |
| `OPENAI_GUESS_REASONING_EFFORT` | `low` | 추리 채점에서 AI가 생각하는 양. 꼼꼼하게(약 3초) |
| `HISTORY_LIMIT` | `40` | 프롬프트에 넣을 최근 질문 수 |
| `OPENAI_TIMEOUT` | `12` | OpenAI 요청 하나를 기다리는 시간(초) |
| `OPENAI_MAX_RETRIES` | `1` | 실패했을 때 다시 시도하는 횟수 |

### 타임아웃
안쪽이 먼저 포기하도록 맞춘다. 바깥이 먼저 포기하면 안쪽이 받은 결과가 버려지고 비용만 나간다.

| 구간 | 타임아웃 | 설정 |
|---|---|---|
| AI 서버 → OpenAI | 12초 × 최대 2번 = 24초 | `ai/.env`의 `OPENAI_TIMEOUT`, `OPENAI_MAX_RETRIES` |
| 백엔드 → AI 서버 | 28초 | `backend/src/main/resources/application.yml`의 `judge.timeout` |
| 화면 → 백엔드 | 30초 | `frontend/src/api.ts`의 `REQUEST_TIMEOUT_MS` |

판정 요청이 실패하면 사용 횟수는 늘지 않는다.

다른 공급자를 붙이려면 `ai/app/judges.py`에 `Judge`를 구현하고 `make_judge`에 등록한다.

## 게임 규칙
규칙은 백엔드 `Game`이 사용 횟수(`used`) 하나로 계산한다. 화면은 받은 값을 그리기만 한다.

- **사용 한도 40**: 질문 1회 = 1, 틀린 추리 1회 = 5(맞힌 추리는 쓰지 않는다). 사용 38에서 추리하면 40에서 멈춘다.
- **힌트**: 사용 10, 20, 30에서 하나씩 자동으로 열린다. "힌트 받기"를 누르면 다음 힌트가 바로 열리고 사용 횟수가 그 자리(10, 20, 30)로 올라간다.
- **마지막 추리**: 사용 40이 되면 질문은 막히고(409), 횟수를 쓰지 않는 추리 한 번만 남는다. 틀리면 `FAILED`로 끝난다.
- **등급**(`Grade`): 게임이 끝나면 S·A·B·C·D·F 중 하나를 준다. 결과 페이지 머리와 메인 카드에 보인다.
  - 해결: 보정 사용(`사용 − 5 × 맞힌 보너스 수`, 0 아래로는 안 감)이 10 이하 S, 20 이하 A, 30 이하 B, 그 위 C.
  - 실패·포기: 사용 횟수와 상관없이 핵심을 하나라도 밝혔으면 D, 하나도 못 밝혔으면 F. (빨리 포기해 높은 등급을 받는 길을 막으려고 사용 횟수를 보지 않는다.)
- **결과 페이지**: 게임이 끝나면(정답, 마지막 추리 실패, 포기) 신호 단절 연출(3.2초: 교신 종료와 결과 한 줄 "사건 해결 // 진상이 밝혀졌다", "마지막 추리 실패 // 사건은 미해결로 남는다", "조사 중단 // 사건은 미해결로 남는다". 움직임 줄이기 설정이면 생략) 뒤 `#/game/<id>/result`로 넘어간다. 판정·등급 아래 [진상] 탭과 [기록] 탭(사건 기록과 수신 기록을 함께, 읽기 전용)이 있다. 결과 페이지에서는 게임 중에 열지 않은 계시도 모두 볼 수 있다(진상 탭의 계시 칸, 기록 탭의 사용 막대 눈금). 진상 공개(`reveal.hints`)가 모든 계시와 연 여부(`opened`)를 보낸다. 끝난 게임의 게임 주소로 오면 결과 페이지로, 진행 중인 게임의 결과 주소로 오면 게임 화면으로 보낸다.
- 판정 요청(AI 서버)이 실패하면 사용 횟수는 늘지 않는다.
- 질문·추리·힌트·포기 API(`/api/games/{id}/...`)는 모두 게임 상태 전체(`GameView`: `used`, `limit`, `hintSteps`, `finalChance`, `nextHintAt`, `reveal.grade` 등)를 돌려준다.
- 기기 구분: 로그인 대신 `/api/games` 요청에 쿠키 `sto_client`(UUID, HttpOnly, 1년)를 준다. 기기 하나에 시나리오 하나당 한 판이다.
  `/api/info`, `/api/scenarios`, 그림 요청은 쿠키를 주지 않는다. 첫 화면에서 함께 나간 요청이 늦게 와서 판을 만든 뒤의 쿠키를 덮어쓰지 않게 하려는 것이다. 같은 이유로 메인 페이지는 내 진행 상태(`GET /api/games`) 응답을 받은 뒤에 시작 요청을 보낸다.
  `/api/games/**`는 그 기기의 판만 다룬다. 남의 판 id는 404다. 배포(HTTPS)에서는 `COOKIE_SECURE=true`로 쿠키에 Secure를 붙인다.
- `POST /api/games`는 이 기기에 그 시나리오의 판이 있으면 새로 만들지 않고 그 판을 돌려준다(200, 새 판은 201).
  `GET /api/games`는 이 기기의 판 목록(`id`, `scenario`, `status`, `used`, `limit`, `grade`(끝난 판만)), `DELETE /api/games/{id}`는 초기화(판과 기록 삭제, 204).
- 판정을 기다리는 사이 다른 창에서 같은 판이 바뀌면 저장하지 않고 409("다른 창에서 진행이 바뀌었습니다. 새로고침해 주세요.")를 돌려준다(`games.version`).

## 이미지 자리
그림 파일을 정해진 자리에 넣으면 화면에 나온다. 없으면 지금 모습 그대로다.

| 그림 | 자리 |
|---|---|
| 전체 배경 | `frontend/public/backgrounds/room.webp` (어둡게 덮어 깐다) |
| 사이트 상징(머리말 로고) | `frontend/public/brand/logo.webp` (없으면 육각형 SVG). 탭 아이콘은 `frontend/public/favicon.svg` |
| 기본 사건 표지 | `frontend/public/covers/default.webp` (문제별 `cover`가 없을 때) |
| 문제별 대표·힌트 그림 | `scenarios/images/<slug>/cover`, `hint-1~3` (`scenarios/README.md`) |

문제별 그림은 백엔드 `GET /api/scenarios/{slug}/images/{name}`이 내보낸다(`name`은 `cover`, `hint-1~3`만, 그 밖은 404).

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
- 정답 제출: 답안 하나가 시나리오의 **핵심 키워드**를 모두 충족하고 **이야기 판정**(`story`)도 참이면 정답이다(형식은 `scenarios/README.md`). 필수 용어가 있는 키워드는 그 세계관 용어를 써야 충족이다.
  - 이야기 판정: AI가 답안 전체를 진상과 견준다. 단어만 늘어놓았거나, 진상의 주요 사실(누가, 무엇을, 왜)과 어긋나거나, 키워드 사이를 다른 이유로 이으면 거짓이다. 진상의 세부가 빠진 것은 괜찮다.
  - 핵심은 다 맞혔는데 이야기가 거짓이면 핵심 하나가 모자랄 때와 같은 답("거의 다 왔구나.")을 보여 준다. 무엇이 틀렸는지는 알리지 않는다.
  - `story`를 보내지 않는 예전 AI 서버와 만나면 판정 오류로 끝낸다(사용 횟수를 쓰지 않음). 두 서버를 모두 다시 켜면 된다.
  오답이면 빠진 핵심 개수에 따라 응시자의 한마디만 보여 준다(1개: "거의 다 왔구나. 조금만 더 들여다보렴.", 2개: "아직 부족하구나.", 3개 이상: "아직 멀었단다."). 맞힌 개수와 어떤 키워드가 빠졌는지는 알려 주지 않는다. 백엔드도 화면에 단계(`closeness`)만 보낸다. AI는 키워드 번호만 판정한다.
- 보너스: 시나리오의 **보너스 키워드**(곁가지 1~2개)도 추리마다 판정해 모아 두고, 게임이 끝날 때 진상 공개에서만 보여 준다. 정답 여부와는 상관없다.
- 맞힌 핵심: 추리마다 맞힌 핵심 키워드 번호도 모아 두고(`Game.keywordFound`), 게임이 끝나면 진상 공개의 `keywords[].found`로 보낸다. 결과 페이지는 밝힌 핵심과 맞힌 보너스를 초록 카드로 보여 준다. 게임 중에는 보내지 않는다.
- 판정은 JSON 스키마로 받으므로 자유 문장이 플레이어에게 새지 않는다. 판정 근거(`reason`)는 AI 서버 로그에만 남는다.

## 로그와 이벤트
판정 시간과 토큰, 게임의 주요 사건은 DB의 `events` 테이블에 영구로 남는다(게임 판과 따로, 초기화해도 남는다). 사람이 읽는 로그는 두 서버의 콘솔에 남는다.

### 이벤트
| 종류 | 언제 |
|---|---|
| `game_started` | 새 판을 만들었을 때(이어하기는 제외) |
| `question_asked` / `guess_submitted` | 질문·추리 판정을 시도한 뒤(실패 포함) |
| `hint_used` | 힌트 버튼 |
| `game_ended` | 해결·실패·포기로 끝났을 때. `detail`에 등급과 그 원재료(사용 횟수, 보정 사용 횟수, 맞힌 핵심·보너스 수) |
| `game_reset` | 초기화 |

- `outcome`: `ok`, `judge_error`(AI 서버 오류나 믿을 수 없는 판정), `timeout`(28초 초과), `conflict`(판정은 됐는데 다른 창과 겹쳐 저장 실패).
- 시간 칸: `total_ms` = `backend_ms` + `ai_ms`, `ai_ms` 안에 `llm_ms`(OpenAI 호출, 재시도 포함)가 들어 있다.
- 토큰은 실패한 판정에도 남는다(쓴 만큼). **시간 초과만은 AI 서버 응답이 없어 토큰을 모른다**(AI 서버 로그에는 남는다).
- 질문·추리 원문은 이벤트에 넣지 않는다(`text_length`만). 원문은 판이 있는 동안 `game_entries`와 AI 서버 로그에 있다.
- 규칙 위반으로 판정 전에 막힌 요청(한도 초과, 끝난 판)은 남기지 않는다. 이벤트 기록이 실패해도 게임은 그대로 되고 경고 로그만 남는다.

### 요청 ID로 두 서버 로그 잇기
백엔드는 `/api` 요청마다 요청 ID를 정해(들어온 `X-Request-Id`가 영숫자와 `-` 8~64자면 그대로) 응답 헤더, 로그, AI 서버 요청, `events.request_id`에 싣는다.
브라우저 개발자 도구에서 응답 헤더의 `X-Request-Id`를 보고 두 서버 로그에서 그 값을 찾으면 된다.
백엔드는 요청마다 접근 로그 한 줄(`access`: 메서드, 경로(게임 id는 `{id}`), 상태, ms, 기기 ID)을 남긴다.

### 환경 변수
| 변수 | 어디 | 뜻 |
|---|---|---|
| `APP_VERSION` | 백엔드 | `events.app_version`에 남는 배포 버전(배포에서 git 커밋). 없으면 `dev` |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` | 백엔드 | 로그를 JSON 한 줄로(배포). 없으면 사람이 읽는 형식 + `[요청 ID]` |
| `LOG_FORMAT=json` | AI 서버 | 로그를 JSON 한 줄로(배포). 기본 `text` |

### 자주 쓸 SQL
로컬 DB: `docker exec -it sea-turtle-office-postgres-1 psql -U seaturtle`

```sql
-- 오늘 질문 판정 시간 p50/p95(ms)
select percentile_cont(0.5) within group (order by total_ms) p50,
       percentile_cont(0.95) within group (order by total_ms) p95
from events where type = 'question_asked' and outcome = 'ok' and at >= current_date;

-- 모델·버전별 평균 시간과 토큰(배포·모델 바꾸기 전후 비교)
select model, app_version, count(*), avg(llm_ms)::int llm_ms, avg(input_tokens)::int input_tokens
from events where type in ('question_asked', 'guess_submitted') group by 1, 2 order by 2;

-- 날짜별 기기 수와 토큰 합
select date(at) d, count(distinct client_id) devices, sum(input_tokens) input, sum(output_tokens) output
from events group by 1 order by 1;
```

## 테스트
```bash
cd ai && uv run pytest        # 세계관·시나리오 읽기, uses ID 검사, 프롬프트, API
cd backend && ./gradlew test  # Docker 필요(Testcontainers로 PostgreSQL을 띄운다). 시나리오 읽기, 게임 흐름(가짜 판정기), AI 서버와 핵심 포인트 수 대조
cd frontend && npm test       # 요청 진행 중 버튼 잠금
```
