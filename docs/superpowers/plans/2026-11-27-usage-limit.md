# 남용 방지 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 판정 전에 동시 판정·하루 예산(2달러)·기기(200)·IP(600) 한도를 `events`로 검사해, 넘으면 AI 호출 없이 거절한다.

**구조:** `RequestIdFilter`가 IP 해시를 MDC `clientIpHash`에 넣고 `EventRecorder`가 `events.ip_hash`에 남긴다. `UsageLimiter`가 `GameService.ask/guess`의 규칙 확인 뒤·판정 앞에서 검사하고, 거절하면 `UsageLimitException`(429, `reason`)과 `limited` 이벤트. 프런트는 예산 429에 상단 안내를 켠다.

**기술:** Spring Boot 4.2, JdbcClient, Flyway, React + Vitest.

**설계 문서:** `docs/superpowers/specs/2026-11-27-usage-limit-design.md`

## 전체 제약
- 한도 기본값: 예산 2.0달러, 기기 200, IP 600, 하루 = Asia/Seoul 자정. 0 이하면 그 검사 끔. 단가(100만 토큰당): 입력 0.25, 캐시 입력 0.025, 출력 2.0.
- "판정 이벤트" = `type in ('question_asked','guess_submitted') and outcome <> 'limited'`. 비용 = `(input−cached)×입력 + cached×캐시 + output×출력`(null은 0).
- 429 `detail` 문구: 예산 "오늘 응시자와의 교신량이 모두 찼습니다. 내일 다시 시도해 주세요." / 기기·IP "오늘 이 단말의 교신 한도를 넘었습니다. 내일 다시 시도해 주세요." `ProblemDetail` 속성 `reason`: `budget|device|ip`.
- 동시 판정 409 문구: "이전 판정을 기다리는 중입니다." 이벤트 없음.
- IP 원문은 저장·로그하지 않는다. `ip_hash = HMAC-SHA256(IP_HASH_SECRET, ip)` hex 앞 32자. 기본 비밀 값 `dev-only`.
- 검사 쿼리가 실패하면 판정을 막지 않고 경고 로그.
- 커밋 메시지·문서 한국어. 커밋 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`와 `Claude-Session: https://claude.ai/code/session_01WuaYaKYEJdLS6sJHtr6ShQ`.
- 사용자의 `sea-turtle-office-postgres-1` 컨테이너는 멈추지 않는다. 개발 DB에 마이그레이션을 미리 적용하지 않는다.

## 검토 초점
1. 같은 판에 판정 두 건이 동시에 → 하나만 판정기를 부르고, 판정이 실패해도 판이 "판정 중"에 갇히지 않는다 — Task 2 테스트.
2. 어제(KST 23시) 이벤트는 오늘 한도에 안 들어간다(UTC 기준으로 세면 틀린다) — Task 2 테스트.
3. 한도에 막힌 `limited` 이벤트가 다시 한도를 채우지 않는다 — Task 2 테스트.
4. 검사 쿼리 실패(테이블 없음) → 판정은 된다 — Task 2 테스트.
5. 게임 도중 예산 429 → 상단 안내가 뜨고 입력 글이 남는다 — Task 4 테스트.

---

### Task 1: IP 해시 기록

**Files:** Create `backend/src/main/resources/db/migration/V3__events_ip.sql`, `backend/src/main/java/com/seaturtle/office/log/IpHasher.java`. Modify `log/RequestIdFilter.java`, `event/EventRecorder.java`, `application.yml`(`game.ip-hash-secret: ${IP_HASH_SECRET:dev-only}`). Test `IpHashTests.java`.

**Interfaces — Produces:** `IpHasher`(`@Component`): `String hash(String ip)`(null이면 null). `RequestIdFilter.MDC_IP = "clientIpHash"`. `events.ip_hash`.

- [ ] Step 1: 테스트 — `hashIsStableAndNotRaw`(같은 입력 같은 32자 hex, 원문 미포함, 다른 IP는 다름), `differentSecretDifferentHash`, `eventStoresIpHash`(MockMvc `with(r -> { r.setRemoteAddr("203.0.113.7"); return r; })`로 질문 → 이벤트 `ip_hash`가 `hasher.hash("203.0.113.7")`, 어떤 칸에도 `203.0.113.7` 없음).
- [ ] Step 2: 실패 확인 `./gradlew test --tests IpHashTests` → 컴파일 실패.
- [ ] Step 3: 구현(`V3`: `alter table events add column ip_hash text; create index events_ip_at_idx on events (ip_hash, at);`).
- [ ] Step 4: `./gradlew test` 통과.
- [ ] Step 5: 커밋 "남용 방지: 이벤트에 IP 해시를 남긴다".

### Task 2: 한도 검사

**Files:** Create `backend/src/main/java/com/seaturtle/office/limit/LimitProperties.java`(`@ConfigurationProperties("limits")`: `double dailyBudgetUsd, int devicePerDay, int ipPerDay, ZoneId zone, Price price`; `record Price(double input, double cachedInput, double output)`), `limit/UsageLimiter.java`, `limit/UsageLimitException.java`. Modify `game/GameService.java`, `game/ApiExceptionHandler.java`, `application.yml`. Test `UsageLimitTests.java`.

**Interfaces — Produces:**
- `UsageLimitException(String reason)` — `reason()`, 메시지는 전체 제약 문구.
- `UsageLimiter`: `void check(String clientId, String ipHash)`(넘으면 `UsageLimitException`; DB 오류는 경고 후 통과), `double spentTodayUsd()`, `boolean budgetExhausted()`, `AutoCloseable enter(String gameId)`(이미 판정 중이면 `IllegalStateException("이전 판정을 기다리는 중입니다.")` — 기존 핸들러가 409), `OffsetDateTime todayStart()`.
- `GameService.ask/guess`: 규칙 확인 → `limiter.check(...)`(거절 시 `limited` 이벤트 기록 후 던짐) → `try (var slot = limiter.enter(game.id())) { 판정·저장·기록 }`.
- `ApiExceptionHandler`: `UsageLimitException` → 429, `detail`, 속성 `reason`.

- [ ] Step 1: 테스트(`@SpringBootTest`, 스텁 판정기가 호출 수를 센다, 이벤트는 `JdbcClient`로 직접 넣는 도우미 `insertJudged(client, ipHash, at, input, cached, output, outcome)`; 한도는 `@TestPropertySource(properties = {"limits.device-per-day=3", "limits.ip-per-day=5", "limits.daily-budget-usd=0.01"})`로 작게)
  - `deviceLimit`: 이 기기 판정 이벤트 3개 → 질문 429 `reason device`, 판정기 0회, `limited` 이벤트 1개(`detail.reason device`).
  - `ipLimit`: 다른 기기들로 같은 `ip_hash` 5개 → 새 기기의 MockMvc 질문(같은 remoteAddr) 429 `reason ip`.
  - `budgetLimit`: 다른 기기 이벤트 하나(input 100000, cached 0, output 0 → 0.025달러 > 0.01) → 429 `reason budget`, 문구 예산.
  - `yesterdayNotCounted`: KST 어제 23:30(= UTC 오늘 14:30 전날)에 기기 이벤트 3개 → 질문 성공.
  - `limitedNotCounted`: `limited` 3개 → 질문 성공.
  - `concurrentJudgingRejected`: 판정 중 스텁 안에서 같은 판으로 `games.ask` → `IllegalStateException`(409), 판정기 1회.
  - `slotReleasedAfterFailure`: 판정 실패(`"오류"`) 뒤 같은 판 질문 성공.
  - `checkFailureDoesNotBlock`: `alter table events rename to events_off` → 질문 성공(finally에서 되돌림).
  - `hintAndGiveUpNotLimited`: 기기 한도 채운 뒤 힌트·포기 성공.
  - `budgetMath`: `spentTodayUsd()`가 (100000−40000)×0.25e-6 + 40000×0.025e-6 + 1000×2e-6 = 0.018 (오차 1e-9).
- [ ] Step 2: 실패 확인 → 컴파일 실패.
- [ ] Step 3: 구현(쿼리는 `todayStart()` 이상만; 기기·IP는 `count(*)`).
- [ ] Step 4: `./gradlew test` 통과(기존 `EventTests`·`GameFlowTests`는 기본 한도라 영향 없음).
- [ ] Step 5: 커밋 "남용 방지: 판정 전에 동시 판정·하루 예산·기기·IP 한도를 검사한다".

### Task 3: 예산 상태 알리기

**Files:** Modify `game/GameController.java`(`ServerInfo`에 `boolean budgetExhausted`), `limit/UsageLimiter.java`(50·80·100% 처음 넘을 때 `log.warn("오늘 판정 비용 {}% 도달: {}달러 / {}달러")`, 날짜별로 한 번). Test `UsageLimitTests`에 추가.

- [ ] Step 1: 테스트 — `infoShowsBudgetExhausted`(예산 넘는 이벤트 넣고 `GET /api/info` → `budgetExhausted true`, 금액 키 없음), `warnsOncePerThreshold`(`OutputCaptureExtension`, 예산 80% 넘는 이벤트 뒤 질문 두 번 → "80%" 줄 한 번).
- [ ] Step 2: 실패 확인. Step 3: 구현. Step 4: `./gradlew test` 통과.
- [ ] Step 5: 커밋 "남용 방지: 예산 소진 여부를 알리고 단계별 경고를 남긴다".

### Task 4: 화면

**Files:** Modify `frontend/src/api.ts`(`ApiError`에 `reason?: string`, `ServerInfo.budgetExhausted`, 예산 429를 받으면 `window.dispatchEvent(new Event('budget-exhausted'))`), `frontend/src/App.tsx`(상단 안내: `info.budgetExhausted` 또는 이벤트 수신 시 `<p className="banner">오늘 응시자와의 교신량이 모두 찼습니다. 내일 다시 시도해 주세요.</p>`). Tests `api.test.ts`, `ScenarioList.test.tsx`(App 렌더 테스트가 여기 있음) 또는 새 `App.test.tsx`.

- [ ] Step 1: 테스트 — `api.test`: 429 본문 `{detail, reason: 'budget'}` → `ApiError.status 429`, `reason 'budget'`, `budget-exhausted` 이벤트 발생; `reason 'device'`면 이벤트 없음. `App.test`: `info.budgetExhausted`면 안내; 아니어도 `budget-exhausted` 이벤트 뒤 안내. `GamePage.test`: 질문이 429(`device`)로 실패 → 문구 보임, 입력 글 남음, `api.game` 다시 안 부름.
- [ ] Step 2: `npm test` 실패 확인. Step 3: 구현. Step 4: `npm test && npm run lint && npm run build`.
- [ ] Step 5: 커밋 "남용 방지: 한도에 걸리면 화면에 알린다".

### Task 5: 문서

- [ ] `DEVELOPMENT.md`에 "사용량 제한" 절: 한도·단가 표와 환경 변수(`LIMIT_DAILY_BUDGET_USD`, `LIMIT_DEVICE_PER_DAY`, `LIMIT_IP_PER_DAY`, `IP_HASH_SECRET`), `limited` 이벤트, 검사 실패 시 통과, 배포에서 `SERVER_FORWARD_HEADERS_STRATEGY=native`(Caddy 뒤), 오늘 쓴 금액 SQL. 이벤트 표에 `ip_hash`, `outcome limited`.
- [ ] `cd backend && ./gradlew test -q && cd ../frontend && npm test` 통과 후 커밋 "남용 방지: 개발 문서에 사용량 제한".
