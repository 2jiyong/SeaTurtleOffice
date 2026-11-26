# 구조화 로그와 이벤트 기록 설계

## 왜
운영 준비 로드맵의 2단계다(① DB·기기별 세션 → **② 로그·이벤트** → ③ 남용 방지 → ④ EC2 배포 → ⑤ 사용량 보기).
사용자가 원하는 것: "로그를 잘 찍어 두면 나중에 내 서비스의 응답 속도 등 데이터를 비교할 때 활용할 수 있게."
비교의 중심은 **성능(응답 속도)**이다. 느린 곳은 사실상 질문·추리 판정이고, 그 대부분이 OpenAI 호출이다.
배포 첫날부터 기록이 쌓여야 하므로 배포(④) 전에 한다. 같은 기록을 ③(기기별 질문 수, 하루 토큰)과 ⑤(일일 사용자, 토큰)가 다시 쓴다.

## 지금 상태
- AI 서버: 텍스트 로그 한 줄(`[slug] Q: 질문 → 답 / 근거`). OpenAI 응답의 `usage`를 버리고, 걸린 시간을 재지 않는다.
- 백엔드: 시나리오 읽기와 판정 실패 외에는 로그가 없다. 요청별 시간, 기기 ID 기록 없음.
- 두 서버 로그를 짝지을 방법이 없다. 로그는 콘솔에만 있고, DB에는 게임 상태만 있다(초기화하면 사라짐).

## 목표 / 하지 않을 것 / 실패로 볼 것
- 목표
  - 판정 요청마다 구간별 시간(전체, 백엔드, AI 서버, OpenAI)과 모델, 토큰, 배포 버전, 결과를 DB에 영구로 남긴다.
  - 게임의 주요 사건(시작, 힌트, 종료, 초기화)도 같은 테이블에 남긴다.
  - 두 서버의 로그를 같은 요청 ID로 이어 볼 수 있다. 배포에서는 JSON 한 줄 로그.
- 하지 않을 것: 통계 화면·대시보드(⑤), 요청 제한(③), 로그 수집 서비스(CloudWatch 등, ④에서 결정), Prometheus·Grafana, 판정 품질 평가.
- 실패로 볼 것
  - 기록 때문에 게임이 느려지거나 실패한다(기록 실패가 게임 요청을 깨뜨림).
  - 토큰을 쓴 판정이 기록에서 빠진다(예: 판정은 됐는데 저장이 409로 실패한 경우).
  - 질문 원문이 이벤트에 영구로 남는다.

## 접근 방식
| 방식 | 내용 | 판단 |
|---|---|---|
| **A. DB `events` 테이블 + JSON 로그 (선택)** | AI 서버가 자기 구간 시간·모델·토큰을 응답에 실어 보내고, 백엔드가 전체 시간을 더해 `events`에 쓴다. 로그는 두 서버 모두 요청 ID를 찍는다. | 통계를 SQL로(`percentile_cont`로 p95까지) 뽑고, ③⑤가 같은 테이블을 쓴다. 지금 Postgres를 그대로 쓴다. 그래프는 ⑤에서. |
| B. JSON 로그 파일만 | 모든 걸 로그로 찍고 나중에 jq/Logs Insights로 분석 | 가장 단순하지만 분석마다 파싱, 로테이션으로 옛 기록이 사라짐, ③에서 결국 DB가 필요. |
| C. Prometheus + Grafana | Micrometer 지표와 대시보드 | 그래프는 좋지만 EC2 한 대에 컨테이너 2개 추가, 건별 기록(판마다 토큰) 없음. 나중에 A 위에 얹을 수 있다. |

사용자가 A를 골랐다. 이유: 비교 대상이 "배포 전후", "모델 바꾸기 전후"처럼 기준값이 붙은 건별 기록이고, 다음 단계들이 같은 데이터를 쓴다.

## 정한 것
- 이벤트 종류와 칸은 아래 표대로(사용자가 확인함).
- **질문·추리 원문은 이벤트에 넣지 않는다.** 글자 수(`text_length`)만 남긴다(사용자가 확인함).
- 이벤트는 게임 판과 따로 보관한다. 초기화해도 지워지지 않는다(외래키 없음).
- 이벤트를 쓰는 곳은 백엔드 한 곳. AI 서버는 DB를 모른다(지금 구조 유지).

## 설계

### 1. 이벤트 테이블 (Flyway `V2__events.sql`)
| 칸 | 타입 | 내용 |
|---|---|---|
| `id` | bigint identity PK | |
| `at` | timestamptz not null | 일어난 시각 |
| `type` | text not null | 아래 종류 |
| `client_id` | uuid not null | 기기 |
| `game_id` | uuid | 판(외래키 없음) |
| `scenario` | text | slug |
| `request_id` | text | 로그와 잇는 요청 ID |
| `outcome` | text not null | `ok` / `judge_error` / `timeout` / `conflict` |
| `answer` | text | 질문: `YES` 등 / 추리: `CORRECT`, `ONE_SHORT` 등 |
| `used_after` | int | 이 일이 끝난 뒤 사용 횟수(실패면 그대로의 값) |
| `text_length` | int | 질문·추리 글자 수 |
| `total_ms` | int | 백엔드가 요청을 받아 판정·저장을 마칠 때까지 |
| `backend_ms` | int | `total_ms − ai_ms`(네트워크 포함). AI 서버 시간이 없으면 null |
| `ai_ms` | int | AI 서버가 요청을 받아 답할 때까지(AI 서버가 잰 값) |
| `llm_ms` | int | 그중 OpenAI 호출(재시도 포함) |
| `model` | text | 예: `gpt-5-mini`, 가짜 판정기는 `fake` |
| `input_tokens`, `output_tokens`, `cached_tokens` | int | OpenAI `usage`. 가짜 판정기는 0 |
| `app_version` | text not null | 환경 변수 `APP_VERSION`(배포에서 git 커밋), 없으면 `dev` |
| `detail` | jsonb | 종류마다 다른 나머지 |

색인: `(at)`, `(client_id, at)`, `(type, at)`. ③에서 "이 기기의 오늘 질문 수", ⑤에서 "날짜별 집계"에 쓴다.

### 2. 이벤트 종류
| 종류 | 언제 | 채우는 칸 / `detail` |
|---|---|---|
| `game_started` | 새 판을 만들었을 때(이어하기·이미 있는 판 반환은 제외) | |
| `question_asked` | 질문 판정을 시도한 뒤(성공·실패 모두) | 시간·모델·토큰, `answer`, `text_length` |
| `guess_submitted` | 추리 판정을 시도한 뒤(성공·실패 모두) | 시간·모델·토큰, `answer`(closeness), `text_length`, `detail: {matched, total, bonus, story}` |
| `hint_used` | 힌트 버튼 | `detail: {hint: 열린 힌트 번호}` |
| `game_ended` | 상태가 PLAYING에서 바뀐 순간(질문·추리·포기 어디서든) | `detail: {status, grade, used, adjustedUsed, bonusSaved, keywordsFound, keywordTotal, bonusFound, bonusTotal}` — 판이 지워져도 다시 계산할 수 있게 원재료를 모두 남긴다 |
| `game_reset` | 초기화 성공 | `detail: {status: 지우기 전 상태, used}` |

- 판정 요청의 `outcome`:
  - `ok`: 판정과 저장 모두 성공.
  - `judge_error`: AI 서버가 오류를 냈거나 응답이 이상함(지금의 `JudgeException`).
  - `timeout`: AI 서버 응답이 백엔드 제한 시간(28초)을 넘김.
  - `conflict`: 판정은 됐는데 저장이 409로 실패. **토큰은 썼으므로 시간·토큰 칸을 채운다.**
- 규칙 위반(한도 초과, 끝난 판 등)으로 판정 전에 막힌 요청은 이벤트를 남기지 않는다(토큰도 시간도 쓰지 않았다).
- `game_ended`는 그 원인 이벤트(`question_asked` 등) 다음에 같은 요청 ID로 남긴다.

### 3. AI 서버 → 백엔드: 판정 지표
- `/judge/question`, `/judge/guess` 응답에 `metrics`를 더한다:
  `{"model": "gpt-5-mini", "aiMs": 4085, "llmMs": 4070, "inputTokens": 5210, "outputTokens": 38, "cachedTokens": 4096}`.
  - `aiMs`: 엔드포인트 진입부터 응답 직전까지. `llmMs`: `chat.completions.create` 앞뒤.
  - 토큰: `response.usage.prompt_tokens`, `completion_tokens`, `prompt_tokens_details.cached_tokens`(없으면 0).
  - 가짜 판정기: `model: "fake"`, 토큰 0, `llmMs: 0`.
- 백엔드 `Judge.Verdict`, `Judge.GuessVerdict`에 `Metrics metrics`(없으면 null)를 더한다. 예전 AI 서버가 `metrics`를 안 보내도 동작한다.
- 판정이 실패해도 **토큰을 쓴 만큼은 기록한다**("토큰을 쓴 판정이 빠지면 실패" 원칙).
  - AI 서버: OpenAI가 답한 뒤 형식 오류 등으로 실패하면 502 본문에 `{"detail": ..., "metrics": {...}}`를 함께 싣는다. OpenAI 호출 자체가 실패했으면 `metrics`의 토큰은 0, `llmMs`는 걸린 시간.
  - 백엔드: `AiJudgeClient`가 502 본문의 `metrics`를 읽어 `JudgeException`에 담는다. 본문을 못 읽으면 null.
  - 백엔드가 정상 응답을 받은 뒤 스스로 거절하는 경우(키워드 수 불일치, `story` 없음)도 이미 받은 `metrics`로 `judge_error` 이벤트를 남긴다.
  - 시간 초과는 AI 서버 응답이 없으므로 토큰을 알 수 없다(null). AI 서버 로그에는 남는다. 이 한계를 `DEVELOPMENT.md`에 적는다.

### 4. 요청 ID와 로그
- 백엔드 필터가 `/api/**` 요청마다 요청 ID를 정한다. 들어온 `X-Request-Id`가 `[A-Za-z0-9-]{8,64}`면 그대로, 아니면 새 UUID.
  MDC `requestId`에 넣고, 응답 헤더 `X-Request-Id`로 돌려주고, AI 서버 호출에 같은 헤더로 넘긴다.
- AI 서버는 미들웨어로 `X-Request-Id`를 읽어(없으면 새로 만듦) 그 요청의 모든 로그에 찍는다.
- **접근 로그(백엔드)**: `/api/**` 요청마다 한 줄. `method`, `path`(게임 id는 `{id}`로 바꿈), `status`, `ms`, `requestId`, `clientId`(있으면).
- **판정 로그(AI 서버)**: 지금 줄에 `request_id`, `ai_ms`, `llm_ms`, 토큰을 더한다. 판정 근거(`reason`)는 지금처럼 여기에만 남긴다.
- 형식
  - 백엔드: Spring Boot 내장 구조화 로그. 배포에서는 `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`로 JSON, 개발에서는 지금처럼 사람이 읽는 형식(패턴에 `requestId`만 더함).
  - AI 서버: 환경 변수 `LOG_FORMAT=json`이면 JSON 한 줄, 기본은 지금 형식 + 요청 ID.
- 로그에 남기지 않을 것: API 키, 쿠키 값(`clientId`는 기기 UUID라 남기되 `Set-Cookie` 헤더는 찍지 않는다).

### 5. 백엔드 안의 기록 방식
- `EventRecorder`(새 컴포넌트)가 `events`에 한 줄 쓴다. **자기 트랜잭션(REQUIRES_NEW)** 으로 쓰고, 실패하면 경고 로그만 남기고 삼킨다. 게임 요청을 깨뜨리지 않는다.
- 시간 재기와 기록은 `GameService`에서 한다(판정 앞뒤, 저장 앞뒤). 컨트롤러·필터는 요청 ID만 넘긴다.
- 쓰기는 동기 INSERT 한 번(판정 4초에 비해 무시할 만함). 비동기 큐는 만들지 않는다.

### 6. 개발 환경과 문서
- `DEVELOPMENT.md`: 이벤트 표의 뜻, 요청 ID로 로그 잇는 법, 자주 쓸 SQL 몇 개(오늘 질문 판정 p50/p95, 모델별 평균, 날짜별 기기 수와 토큰 합).
- `APP_VERSION`, `LOGGING_STRUCTURED_FORMAT_CONSOLE`, `LOG_FORMAT` 환경 변수 설명.

### 7. 테스트
- 백엔드(Testcontainers): 질문 성공 시 이벤트 한 줄과 칸 값(모델·토큰·시간이 0 이상, `backend_ms = total_ms − ai_ms`), 판정 실패는 `judge_error`, 시간 초과는 `timeout`, 저장 409는 `conflict`이면서 토큰이 남음, 해결·포기 시 `game_ended`, 초기화 뒤에도 이벤트가 남음, 이벤트 저장이 실패해도 질문 요청은 성공, 원문이 이벤트 어디에도 없음, 응답 헤더와 AI 요청에 `X-Request-Id`.
- AI 서버(pytest): 응답의 `metrics` 모양, 가짜 판정기 값, OpenAI `usage`가 없을 때 0, 요청 ID가 로그에 찍힘, `LOG_FORMAT=json`이면 JSON 한 줄.

## 검토 뒤 정한 것 (설계자 판단)
- 판정 실패에서도 토큰을 기록한다(위 3절). ③의 "하루 토큰"이 실제보다 적게 잡히지 않게 하려는 것이다. 시간 초과만 예외.
- `game_ended.detail`에는 등급만이 아니라 등급을 낸 원재료(사용 횟수, 보정 사용 횟수, 보너스 절감, 맞힌 핵심·보너스 수와 전체 수)를 모두 남긴다. 판이 지워진 뒤에도 등급 기준이 바뀌면 다시 계산할 수 있다.
- 힌트·포기 저장이 409로 실패하면 이벤트를 남기지 않는다(토큰도 판정 시간도 없다). 판정 요청의 409는 `conflict`로 남기고, 이때 `game_ended`는 남기지 않는다(저장되지 않았으므로).
- `text_length`는 앞뒤 공백을 뺀 글자 수(Java `String.length()`가 아니라 코드 포인트 수).

## 빌더에게 맡기는 것
- 접근 로그를 필터로 할지 인터셉터로 할지, 경로의 id 바꾸기 방식.
- `detail` 매핑 방식(JPA `@JdbcTypeCode(SqlTypes.JSON)` 또는 `JdbcClient`).
- 시간 초과 판별 방법(예외 원인이 `SocketTimeoutException`인지).
- 파이썬 JSON 로그 포매터 구현(표준 `logging`만으로).
- 커밋 단위와 순서.
