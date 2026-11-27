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
| `OPENAI_QUESTION_REASONING_EFFORT` | `low` | 질문 판정에서 AI가 생각하는 양(약 3초). `minimal`(약 2초)은 판정 규칙을 잘 따르지 못한다. 추론 모델이 아니면 빈 값으로 둔다 |
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
- 반려: 답이 `INVALID_FORM`·`INVALID_TRUTH`·`INVALID_RULE`이면 사용 횟수를 쓰지 않고 힌트도 열지 않는다. 기록에는 번호 없이(`no: null`) 남고 화면에는 '반려'로 보인다. 다음 질문 번호는 반려를 빼고 센다. 이 규칙 전에 쌓인 형식 오류 기록은 번호와 횟수가 그대로다.
- 질문·추리·힌트·포기 API(`/api/games/{id}/...`)는 모두 게임 상태 전체(`GameView`: `used`, `limit`, `hintSteps`, `finalChance`, `nextHintAt`, `reveal.grade` 등)를 돌려준다.
- 기기 구분: 로그인 대신 `/api/games` 요청에 쿠키 `sto_client`(UUID, HttpOnly, 1년)를 준다. 기기 하나에 시나리오 하나당 한 판이다.
  `/api/info`, `/api/scenarios`, 그림 요청은 쿠키를 주지 않는다. 첫 화면에서 함께 나간 요청이 늦게 와서 판을 만든 뒤의 쿠키를 덮어쓰지 않게 하려는 것이다. 같은 이유로 메인 페이지는 내 진행 상태(`GET /api/games`) 응답을 받은 뒤에 시작 요청을 보낸다.
  `/api/games/**`는 그 기기의 판만 다룬다. 남의 판 id는 404다. 배포(HTTPS)에서는 `COOKIE_SECURE=true`로 쿠키에 Secure를 붙인다.
- `POST /api/games`는 이 기기에 그 시나리오의 판이 있으면 새로 만들지 않고 그 판을 돌려준다(200, 새 판은 201).
  `GET /api/games`는 이 기기의 판 목록(`id`, `scenario`, `status`, `used`, `limit`, `grade`(끝난 판만)), `DELETE /api/games/{id}`는 초기화(판을 숨긴다. 판과 기록은 DB에 남고 `games.hidden_at`에 숨긴 때가 적힌다. 204).
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
  | `INVALID_FORM` | 다시 물어보렴 (반려) | 예·아니오로 답할 수 있게 바꿔 물어보세요. 질문 횟수는 쓰지 않았어요. |
  | `INVALID_TRUTH` | 다시 물어보렴 (반려) | 진상은 직접 알려 줄 수 없어요. 질문 횟수는 쓰지 않았어요. |
  | `INVALID_RULE` | 다시 물어보렴 (반려) | 규칙을 벗어난 요청이에요. 질문 횟수는 쓰지 않았어요. |

  반려 문구는 `REJECTED_TEXT`에 있다. 번호가 있는 예전 형식 오류 기록은 "말해줄 수 없단다"(`ANSWER_TEXT`)로 보인다.
  모든 질문은 이 사건에 대한 질문으로 읽는다. 진상에 없는 일은 일어나지 않은 것으로 답하고, 진상이 정하지 않은 세부만 `IRRELEVANT_UNDECIDED`, 사건에 나오지 않는 대상의 성질은 `IRRELEVANT_UNRELATED`다(자세한 규칙은 `QUESTION_RULES`).
  판정 메모의 "예 / 아니오 / 일부만 맞다 / 조건이 붙으면 예다 / 상관없음 / 정해지지 않음"은 `YES` / `NO` / `PARTIAL_SOME` / `PARTIAL_CONDITION` / `IRRELEVANT_*` / `IRRELEVANT_UNDECIDED`로 읽는다. 예전 말 "예로 볼 수 있다"는 `PARTIAL_*`다.
- 명제와 판정 근거: AI는 질문을 평서문 명제 하나로 바꿔 쓴 뒤(부정은 부정 그대로, 질문에 없는 말은 넣지 않는다) 그 명제를 판정한다. 명제(`proposition`)는 답 아래에 작게 보인다(반려는 없음). 다음 판정의 앞 질문 기록에도 함께 간다.
  판정 근거(`reason`, 질문·추리 모두)는 `game_entries.reason`에만 남는다. 진상이 담겨 있어 API 응답(`QuestionView`, `GuessView`)에는 넣지 않는다.
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

- `outcome`: `ok`, `limited`(사용량 한도에 막혀 판정하지 않음, `detail.reason`), `judge_error`(AI 서버 오류나 믿을 수 없는 판정), `timeout`(AI 서버가 28초 안에 답하지 않음. 연결조차 못 한 경우는 `judge_error`), `conflict`(판정은 됐는데 다른 창과 겹쳐 저장 실패).
- `ip_hash`: 요청 IP의 해시(원문은 남기지 않는다). 아래 사용량 제한에서 IP별 판정 수를 센다.
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

## 사용량 제한
판정(질문·추리)은 AI 서버에 보내기 **전에** 아래 한도를 검사한다. 넘으면 판정하지 않고(토큰을 쓰지 않고) 429를 돌려준다.
기준은 `events`라 서버를 다시 켜도 오늘 쓴 양이 유지된다. 하루는 한국 시간 자정 기준.

| 한도 | 기본값 | 환경 변수 | 넘으면 |
|---|---|---|---|
| 하루 전체 판정 비용 | 6달러 | `LIMIT_DAILY_BUDGET_USD` | 429 `reason: budget`, 화면 상단 안내 |
| 기기 하나, 하루 판정 | 200번 | `LIMIT_DEVICE_PER_DAY` | 429 `reason: device` |
| IP 하나, 하루 판정 | 600번 | `LIMIT_IP_PER_DAY` | 429 `reason: ip` |
| 한 판의 동시 판정 | 1건 | — | 409 "이전 판정을 기다리는 중입니다." |
| 서버 전체 동시 판정 | 20건 | `LIMIT_MAX_IN_FLIGHT` | 503 "응시자가 다른 교신으로 바쁩니다. 잠시 뒤 다시 시도해 주세요." |

- 0 이하로 두면 그 검사를 끈다. 힌트·포기·조회·초기화는 막지 않는다. 판정이 실패한 요청(`judge_error`, `timeout`, `conflict`)도 센다. `limited`는 세지 않는다.
- 비용 = `(입력 − 캐시) × 0.45 + 캐시 × 0.045 + 출력 × 3.6`(100만 토큰당 달러, `gpt-5-mini` 우선 처리). AI 서버는 기본으로 `service_tier: priority`(Fast mode)를 보낸다(`OPENAI_SERVICE_TIER`, 비우면 표준 처리이고 단가는 0.25 / 0.025 / 2.0). 모델이나 처리 등급을 바꾸면 `application.yml`의 `limits.price`와 `deploy/usage.sql`을 함께 고친다.
  **하루 도중에 단가를 바꾸면 오늘 쓴 토큰 전체에 새 단가가 적용된다**(이벤트에 단가를 남기지 않는다). 모델은 자정 무렵에 바꾸는 편이 낫다.
- 한도는 판정이 끝난 뒤 기록되는 이벤트로 센다. 한도 직전에 요청이 몰리면 그 순간 판정 중인 만큼(최대 `LIMIT_MAX_IN_FLIGHT`건) 넘을 수 있다.
- 시간 초과로 응답을 잃은 판정과 OpenAI 자동 재시도 1회의 토큰은 예산에 들어가지 않는다(드물다). 최종 안전망은 OpenAI 충전 잔액이다.
- IPv6는 앞 64비트(/64)로 묶어 센다(그 안에서 주소를 사실상 무한히 바꿀 수 있어서).
- 한도에 걸린 요청도 매번 검사 쿼리와 `limited` 기록이 생긴다. 배포에서는 Caddy의 초당 요청 제한을 앞에 함께 둔다(④).
- 검사 쿼리가 실패하거나 2초 안에 끝나지 않으면 **판정을 막지 않는다**(경고 로그). 최종 안전망은 OpenAI 충전 잔액이다.
- 예산의 50%, 80%, 100%를 처음 넘을 때 백엔드 로그에 경고가 한 줄 남는다. `/api/info`는 `budgetExhausted`만 알려 준다(금액은 공개하지 않는다).
- IP 해시의 비밀 값은 `IP_HASH_SECRET`(배포에서 반드시 바꾼다, 로컬 기본값 `dev-only`).
- **배포(Caddy 뒤)에서는** `SERVER_FORWARD_HEADERS_STRATEGY=native`로 `X-Forwarded-For`를 믿게 해야 진짜 IP로 센다. 안 켜면 모든 요청이 프록시 IP 하나로 보여 IP 한도(600)에 다 같이 걸린다.

```sql
-- 오늘(한국 시간) 쓴 판정 비용(달러)
select sum((coalesce(input_tokens,0) - coalesce(cached_tokens,0)) * 0.45
         + coalesce(cached_tokens,0) * 0.045 + coalesce(output_tokens,0) * 3.6) / 1e6 usd
from events
where type in ('question_asked', 'guess_submitted') and outcome <> 'limited'
  and at >= date_trunc('day', now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul';

-- 오늘(한국 시간) 한도에 막힌 요청
select detail->>'reason' reason, count(*) from events
where outcome = 'limited'
  and at >= date_trunc('day', now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul'
group by 1;
```

## 배포

서버 한 대(EC2 `t4g.small`, 서울, Ubuntu 24.04)에서 `compose.prod.yaml`로 다섯 컨테이너를 띄운다. 이미지는 서버에서 직접 빌드한다. 설계: `docs/superpowers/specs/2026-11-27-deploy-design.md`.

```
사용자 ─HTTPS─▶ caddy (인증서 자동, IP별 요청 제한)
                ├ www.seaturtleoffice.site   → 프론트 + /api → backend → ai → OpenAI
                ├ seaturtleoffice.site       → www로 301
                └ stats.seaturtleoffice.site → umami (방문 통계)
               postgres (게임 DB seaturtle, Umami DB umami)
```

밖으로 열린 포트는 80/443(caddy)과 22(내 IP만)뿐이다. 비밀 값은 서버의 `/opt/seaturtle/.env`에만 있다(틀: `.env.example`).

### 처음 한 번

1. AWS에 서버·고정 IP·보안 그룹(`seaturtle-office`)을 만든다. 키는 `~/.ssh/seaturtle-office.pem`.
2. 가비아 DNS에 A 레코드 `@`, `www`, `stats` → 고정 IP. `dig +short www.seaturtleoffice.site`로 퍼졌는지 본다(퍼지기 전에 Caddy를 켜면 인증서 발급이 잠시 막힐 수 있다).
3. 접속: `ssh -i ~/.ssh/seaturtle-office.pem ubuntu@www.seaturtleoffice.site`
4. `curl -fsSLO https://raw.githubusercontent.com/2jiyong/SeaTurtleOffice/main/deploy/bootstrap.sh && sudo bash bootstrap.sh` 뒤 다시 로그인.
5. `cd /opt/seaturtle && cp .env.example .env && chmod 600 .env` → 비밀 값은 `openssl rand -hex 32`로 만들어 채운다. `OPENAI_API_KEY`는 직접 붙여 넣는다(비우면 가짜 판정기로 돈다 — smoke가 잡는다).
6. **Umami만 먼저 켜고 관리자 비밀번호를 바꾼다.** 처음 계정은 누구나 아는 `admin` / `umami`라서, 통계 주소가 인터넷에 열리기 전에 바꿔야 한다.
   - 서버: `docker compose -f compose.prod.yaml up -d umami` (postgres도 함께 뜬다. Caddy는 아직 안 켠다)
   - 내 컴퓨터: `ssh -i ~/.ssh/seaturtle-office.pem -L 3001:127.0.0.1:3001 ubuntu@<고정 IP>` 를 켜 둔 채 `http://localhost:3001` 접속
   - `admin` / `umami`로 들어가 비밀번호를 바꾸고, 웹사이트(`www.seaturtleoffice.site`)를 등록해 ID를 `.env`의 `VITE_UMAMI_WEBSITE_ID`에 넣는다.
7. `deploy/deploy.sh` (이때 Caddy가 켜지고 인증서를 받는다)
8. 내 컴퓨터에서 `deploy/smoke.sh https://www.seaturtleoffice.site https://seaturtleoffice.site https://stats.seaturtleoffice.site` → 모두 `통과`. 브라우저 개발자 도구에서 `stats…/api/send` 요청 하나를 열어 `url`에 `#/game/…`이 없는지 본다.

DNS에는 A 레코드만 둔다. AAAA(IPv6)를 넣으면 도커가 IPv6 접속을 내부 주소 하나로 바꿔서, IPv6 사용자가 모두 같은 IP 한도·해시를 나눠 쓰게 된다.

postgres 초기화 스크립트(`deploy/postgres-init/`)는 볼륨이 처음 만들어질 때만 돈다. 나중에 `UMAMI_DB_PASSWORD`를 바꾸면 DB에서도 바꾼다: `docker compose -f compose.prod.yaml exec postgres psql -U seaturtle -c "alter role umami password '<새 값>'"`.

### 새 버전 올리기

main에 푸시한 뒤 서버에서:

```bash
cd /opt/seaturtle && deploy/deploy.sh
```

`git pull` → 이미지를 하나씩 빌드(2~4분, 그동안 서버가 느리다) → 다시 띄우기 → 옛 이미지·빌드 캐시 정리. 백엔드가 뜨는 10~30초 동안 끊긴다. 시나리오만 바꿔도 같은 명령이다(두 서버가 내용을 시작할 때만 읽어서 늘 다시 띄운다).

### 점검 안내 켜기·끄기

서버에서:

```bash
cd /opt/seaturtle
deploy/maintenance.sh on       # 점검 안내 켬
deploy/maintenance.sh off      # 끔
deploy/maintenance.sh status   # 지금 상태
```

켜면 사이트의 모든 요청이 503을 받는다. 화면은 점검 안내 페이지(`deploy/caddy/maintenance.html`), `/api/*`는 `{"detail":"잠시 점검 중입니다…"}`라서 이미 열어 둔 화면에도 그 문장이 뜬다.
Caddy가 요청마다 `maintenance/on` 파일을 보므로 다시 띄울 필요 없이 바로 바뀐다. 백엔드·AI 서버는 그대로 돈다. 아예 멈추려면 켠 뒤 `docker compose -f compose.prod.yaml stop backend ai`.
`deploy.sh`로 새 버전을 올려도 점검 안내는 `off` 할 때까지 그대로라, 점검 중에 배포·확인한 뒤 끄면 된다. 통계 주소(`stats.`)는 영향을 받지 않는다.

### 사용량 보기

내 컴퓨터에서:

```bash
deploy/usage.sh        # 최근 7일
deploy/usage.sh 30     # 최근 30일
```

SSH로 서버 DB에 읽기 쿼리(`deploy/usage.sql`)만 보내 표 네 개를 찍는다. 날짜는 한국 시간 자정 기준(한도와 같다).

| 표 | 내용 |
|---|---|
| 오늘 | 기기(이벤트를 남긴 기기 수), 새 판, 질문·추리(성공), 힌트, 해결·미해결, 비용($)과 예산 |
| 날짜별 | 기기, 판정(질문+추리, 한도초과 제외), 비용, 응답 p50·p95(성공한 판정), 오류(judge_error·timeout), 한도초과 |
| 버전별 | 버전·모델마다 판정 수, p50·p95, LLM p50, 평균 입력·출력 토큰 — 배포·모델 바꾸기 전후 비교 |
| 사건별 | 시작, 해결, 해결률, 해결한 판의 평균 사용 횟수 |

비용은 한도와 같은 식(입력-캐시 × 0.45, 캐시 × 0.045, 출력 × 3.6, 100만 토큰당 달러, 우선 처리)이다. `application.yml`의 단가·예산을 바꾸면 `usage.sql`도 고친다. 방문만 하고 간 사람 수와 페이지 그래프는 Umami(`stats.`)에서 본다. 쿼리를 고치면 `deploy/usage-test.sh`(일회용 postgres 컨테이너, 도커 필요)로 확인한다.

### 로그와 상태

```bash
cd /opt/seaturtle
docker compose -f compose.prod.yaml ps
docker compose -f compose.prod.yaml logs -f --no-log-prefix backend | jq -c 'select(.message)'   # JSON 한 줄씩
docker compose -f compose.prod.yaml logs --no-log-prefix ai | grep <요청 ID>                       # 두 서버 로그 잇기
docker stats --no-stream    # 컨테이너별 CPU·메모리(상한: backend 640m, ai 256m, postgres 384m, umami 320m, caddy 128m)
free -h; df -h /            # 서버 메모리·디스크
```

로그는 컨테이너마다 10MB × 5개까지만 남는다. 오래 비교할 숫자(응답 시간, 토큰)는 `events` 테이블에 있다(위 "자주 쓸 SQL"). DB에 들어가기: `docker compose -f compose.prod.yaml exec postgres psql -U seaturtle`.

Caddy는 접속 로그를 남기지 않는다(원본 IP가 남지 않게).

### 백업과 복원

매일 04:00 `deploy/backup.sh`가 `/opt/seaturtle-backups/날짜-seaturtle.dump`, `날짜-umami.dump`를 만들고 14일 지난 것을 지운다. 기록: `/var/log/seaturtle-backup.log`. 백업은 같은 디스크에 있으니 가끔 내 컴퓨터로 받아 둔다:

```bash
scp -i ~/.ssh/seaturtle-office.pem 'ubuntu@www.seaturtleoffice.site:/opt/seaturtle-backups/*.dump' ~/seaturtle-backups/
```

복원(서비스를 잠시 멈춘다). 한 트랜잭션이라 중간에 실패하면 원래대로 남는다. 백업 뒤에 새로 생긴 테이블은 지워지지 않는다.

```bash
cd /opt/seaturtle
docker compose -f compose.prod.yaml stop backend umami
docker compose -f compose.prod.yaml exec -T postgres pg_restore -U seaturtle -d seaturtle --clean --if-exists --single-transaction < /opt/seaturtle-backups/<날짜>-seaturtle.dump
docker compose -f compose.prod.yaml exec -T postgres pg_restore -U seaturtle -d umami --clean --if-exists --single-transaction < /opt/seaturtle-backups/<날짜>-umami.dump
docker compose -f compose.prod.yaml start backend umami
```

### 경보

CloudWatch 경보가 메일(SNS 주제 `seaturtle-alerts`)로 온다.

| 경보 | 뜻 |
|---|---|
| CPU 크레딧 < 30 (30분) | 크레딧이 곧 바닥난다. standard 모드라 바닥나면 CPU가 20%로 묶여 느려진다(요금은 늘지 않는다) |
| CPU > 80% (15분) | 무언가 CPU를 계속 쓴다. `docker stats`로 찾는다 |
| 시스템 상태 검사 실패 (2분) | AWS 하드웨어 문제. 자동 복구가 돈다 |
| 인스턴스 상태 검사 실패 (3분) | 서버 OS가 응답하지 않는다. 콘솔에서 재부팅 |

CPU 크레딧 잔량 직접 보기(내 컴퓨터): `aws cloudwatch get-metric-statistics --namespace AWS/EC2 --metric-name CPUCreditBalance --dimensions Name=InstanceId,Value=<인스턴스 ID> --start-time $(date -u -v-6H +%FT%TZ) --end-time $(date -u +%FT%TZ) --period 3600 --statistics Average`

### 이미지 보안 갱신

`deploy.sh`는 받아 둔 베이스 이미지(JDK, Python, Postgres 등)를 다시 받지 않는다. 한 달에 한 번쯤 `docker compose -f compose.prod.yaml build --pull && deploy/deploy.sh`로 보안 패치를 받는다. Caddy·플러그인·Umami는 버전이 고정돼 있어 올릴 때 파일을 고친다.

### SSH가 안 될 때

보안 그룹이 내 IP에서만 22번을 연다. 장소를 옮겨 IP가 바뀌면 내 컴퓨터에서 `deploy/ssh-allow-my-ip.sh`.

### 로컬에서 배포 구성 시험

맥(Apple Silicon)은 서버와 같은 arm64라 그대로 시험할 수 있다. 개발 DB와 프로젝트 이름(`sea-turtle-office-prod`)·볼륨이 달라 서로 건드리지 않는다. 80/443 포트를 쓴다.

```bash
cp deploy/local.env.example deploy/local.env
docker compose -f compose.prod.yaml --env-file deploy/local.env up -d --build
deploy/smoke.sh https://www.localhost https://localhost https://stats.localhost --insecure --allow-fake
docker compose -f compose.prod.yaml --env-file deploy/local.env down -v   # 시험 스택만 지운다
```

## 테스트
```bash
cd ai && uv run pytest        # 세계관·시나리오 읽기, uses ID 검사, 프롬프트, API
cd backend && ./gradlew test  # Docker 필요(Testcontainers로 PostgreSQL을 띄운다). 시나리오 읽기, 게임 흐름(가짜 판정기), AI 서버와 핵심 포인트 수 대조
cd frontend && npm test       # 요청 진행 중 버튼 잠금
```
