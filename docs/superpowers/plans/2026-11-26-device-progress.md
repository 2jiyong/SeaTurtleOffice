# 기기별 진행 저장 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 게임 진행을 메모리에서 PostgreSQL로 옮기고, 쿠키로 받은 기기 ID별로 시나리오마다 한 판을 저장·이어하기·초기화한다.

**구조:** `Game`은 규칙만 가진 도메인 클래스로 남기고, `GameStore`가 JPA 엔티티(`GameEntity`, `GameEntryEntity`)와 오가며 DB에 읽고 쓴다.
판정은 트랜잭션 밖에서 하고, 저장할 때 `@Version`으로 그사이 바뀐 판을 막는다. 기기 ID는 `ClientIdFilter`가 쿠키 `sto_client`로 붙인다.

**기술:** Spring Boot 4.2, Spring Data JPA(Hibernate), Flyway, PostgreSQL 17, Testcontainers, spring-boot-docker-compose / React + Vitest.

**설계 문서:** `docs/superpowers/specs/2026-11-26-device-progress-design.md`

## 전체 제약
- 쿠키: 이름 `sto_client`, 값 UUID, `HttpOnly`, `SameSite=Lax`, `Path=/`, `Max-Age` 1년(31536000초), `Secure`는 `game.cookie-secure`(기본 false). 요청마다 다시 심어 만료를 늘린다.
- `(client_id, scenario_slug)` 유일. 초기화(`DELETE`)는 판과 항목을 지운다.
- 남의 게임 id, 형식이 틀린 id, 시나리오가 사라진 판은 모두 404 `"게임을(를) 찾을 수 없습니다"`류 — 지금 `GameNotFoundException("게임")` 문구를 그대로 쓴다.
- 낙관적 잠금 실패는 409, 문구 `"다른 창에서 진행이 바뀌었습니다. 새로고침해 주세요."`
- 프런트 문구: 진행 중 `진행 중 (used/limit)`, 해결 `해결 · N점`, 실패·포기 `해결 못 함`. 404 문구 `기록을 찾을 수 없습니다.`
- 사용자에게 보이는 글, 커밋 메시지, 문서는 한국어. 커밋 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 백엔드 테스트는 Docker가 켜져 있어야 돈다(Testcontainers).

## 검토 초점
1. 쿠키 값이 UUID가 아닌 아무 문자열이면 새 ID를 준다 — Task 3 테스트.
2. 판정이 실패(502)하면 판이 바뀌지 않고 버전도 그대로라 바로 다시 질문할 수 있다 — Task 4 테스트.
3. 다른 기기가 내 판을 `DELETE`하면 404이고 내 판은 남는다 — Task 4 테스트.
4. 내 판 목록 요청이 실패해도 시나리오 목록은 뜬다(상태 표시만 빠짐) — Task 5 테스트.
5. 초기화 도중 요청이 실패하면 오류를 보여 주고 결과 페이지에 머문다 — Task 6 테스트.

---

### Task 1: PostgreSQL·Flyway 바탕 깔기

**Files:**
- Modify: `backend/build.gradle.kts`, `backend/src/main/resources/application.yml`, `DEVELOPMENT.md`
- Create: `compose.yaml`(저장소 루트), `backend/src/main/resources/db/migration/V1__games.sql`,
  `backend/src/test/java/com/seaturtle/office/TestcontainersConfiguration.java`
- Modify(테스트 3개에 `@Import(TestcontainersConfiguration.class)`): `GameFlowTests.java`, `JudgeTimeoutTests.java`, `ContentRepositoryTests.java`

**Interfaces:**
- Produces: 테이블 `games`, `game_entries`(아래). 테스트 설정 `TestcontainersConfiguration`(`@TestConfiguration(proxyBeanMethods = false)`, `@Bean @ServiceConnection PostgreSQLContainer postgres()` 이미지 `postgres:17`).

- [ ] **Step 1: 의존성 추가** — `spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`, `org.flywaydb:flyway-database-postgresql`,
  `runtimeOnly("org.postgresql:postgresql")`, `developmentOnly("org.springframework.boot:spring-boot-docker-compose")`,
  테스트에 `org.springframework.boot:spring-boot-testcontainers`, `org.testcontainers:testcontainers-postgresql`(Boot가 관리하는 버전), `org.testcontainers:testcontainers-junit-jupiter`.
  Boot 4.2의 실제 아티팩트 이름이 다르면 Boot 문서의 이름을 따른다.

- [ ] **Step 2: `compose.yaml`** — 서비스 `postgres`, 이미지 `postgres:17`, `POSTGRES_DB/USER/PASSWORD` 모두 `seaturtle`, 포트 `5432:5432`, 이름 있는 볼륨 `pgdata`.
  `bootRun`은 `backend/`에서 돌므로 `application.yml`에 `spring.docker.compose.file: ../compose.yaml`을 둔다.

- [ ] **Step 3: `application.yml`**
  ```yaml
  spring:
    datasource:
      url: ${DB_URL:jdbc:postgresql://localhost:5432/seaturtle}
      username: ${DB_USER:seaturtle}
      password: ${DB_PASSWORD:seaturtle}
    jpa:
      open-in-view: false
      hibernate:
        ddl-auto: validate
  ```

- [ ] **Step 4: `V1__games.sql`**
  ```sql
  create table games (
    id uuid primary key,
    client_id uuid not null,
    scenario_slug text not null,
    status text not null,
    used int not null,
    seq int not null,
    keyword_found int[] not null,
    bonus_found int[] not null,
    version bigint not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (client_id, scenario_slug)
  );
  create table game_entries (
    id bigint generated always as identity primary key,
    game_id uuid not null references games(id) on delete cascade,
    seq int not null,
    kind text not null,
    no int,
    text text not null,
    answer text,
    correct boolean,
    closeness text,
    created_at timestamptz not null,
    unique (game_id, seq)
  );
  ```
  (설계의 PK(game_id, seq) 대신 대리키 + 유일 제약: JPA 매핑이 단순해진다. 뜻은 같다.)

- [ ] **Step 5: 테스트 3개에 `@Import(TestcontainersConfiguration.class)`를 달고 돌린다**
  Run: `cd backend && ./gradlew test`
  Expected: 기존 테스트 모두 PASS(엔티티가 아직 없어 `validate`가 볼 것이 없다). 로그에 Flyway `V1` 적용.

- [ ] **Step 6: `DEVELOPMENT.md`** — 구조 그림에 `backend ──▶ PostgreSQL` 추가. "게임 진행 상태는 백엔드 메모리에만 있다…사라진다" 문장을 지우고
  "게임 진행은 PostgreSQL에 저장된다. `bootRun`이 루트의 `compose.yaml`로 DB 컨테이너를 알아서 켠다(Docker 필요). 직접 띄우려면 `docker compose up -d`. 배포에서는 `DB_URL`, `DB_USER`, `DB_PASSWORD`로 넘긴다. 백엔드 테스트도 Docker가 있어야 돈다."를 넣는다.

- [ ] **Step 7: 커밋** — `git commit -m "진행 저장: PostgreSQL·Flyway와 테스트용 컨테이너를 깐다"`

---

### Task 2: `Game`을 DB에서 되살리고 `GameStore`를 DB로

**Files:**
- Modify: `backend/src/main/java/com/seaturtle/office/game/Game.java`, `GameStore.java`
- Create: `game/GameEntity.java`, `game/GameEntryEntity.java`, `game/GameEntityRepository.java`, `game/GameConflictException.java`
- Test: `backend/src/test/java/com/seaturtle/office/GameStoreTests.java`

**Interfaces:**
- Consumes: Task 1의 테이블.
- Produces:
  - `Game`: 생성자 `Game(String id, String clientId, Scenario scenario)`(새 판, version 0).
    `static Game restore(String id, String clientId, Scenario scenario, GameStatus status, int used, int seq, List<QuestionRecord> questions, List<GuessRecord> guesses, List<HintRecord> hints, Set<Integer> keywordFound, Set<Integer> bonusFound, long version)`.
    새 접근자 `clientId()`, `seq()`, `version()`. `synchronized`, `lastActive`, `touch()`는 없앤다. 규칙 메서드의 동작은 그대로.
  - `GameStore`(`@Component`):
    - `Game create(String clientId, Scenario scenario)` — 유일 제약에 걸리면 `DataIntegrityViolationException`을 그대로 던진다.
    - `Optional<Game> find(String clientId, String gameId)` — 주인이 다르거나, id가 UUID가 아니거나, 시나리오가 `ContentRepository`에 없으면 빈 값.
    - `Optional<Game> findByScenario(String clientId, String slug)`
    - `List<Game> list(String clientId)` — 시나리오가 사라진 판은 뺀다. 순서는 상관없음.
    - `void save(Game game)` — 읽었을 때의 `version()`과 DB 버전이 다르면 `GameConflictException`. 상태·수치·배열을 덮어쓰고, DB에 없는 seq의 항목만 추가한다.
    - `boolean delete(String clientId, String gameId)` — 지웠으면 true.
  - `GameConflictException extends RuntimeException`, 메시지는 전체 제약의 409 문구.
- 엔티티 매핑: 배열 칸은 `@JdbcTypeCode(SqlTypes.ARRAY) Integer[]` 또는 `List<Integer>`. `GameEntity.entries`는 `@OneToMany(mappedBy = "game", cascade = ALL, orphanRemoval = true) @OrderBy("seq")`. `version`은 `@Version`.
  `kind`는 `QUESTION` / `GUESS` / `HINT`. `save`는 `@Transactional`; 엔티티 버전을 `game.version()`과 직접 비교해 다르면 예외(JPA의 `OptimisticLockException`이 나도 같은 예외로 바꾼다).

- [ ] **Step 1: 실패하는 테스트 작성** (`@SpringBootTest @Import(TestcontainersConfiguration.class)`, 테스트마다 `String client = UUID.randomUUID().toString()`, 시나리오는 `content.scenario("boys-order").orElseThrow()`)
  - `roundTripKeepsEverything`: create → `addQuestion("q1", Answer.YES)` ×10(힌트 1 열림) → `addGuess("g", List.of(1,3), List.of(1))` → save → `find` 결과의 `questions()`, `guesses()`, `hints()`, `used()`, `seq()`, `keywordFound()`, `bonusFound()`, `status()`가 저장 전과 같다(`hints().getFirst().text()`도 같음).
  - `otherClientCannotFind`: 다른 UUID로 `find` → empty, `"not-a-uuid"`로 `find` → empty.
  - `duplicateCreateFails`: 같은 client·시나리오로 두 번 `create` → 둘째가 `DataIntegrityViolationException`.
  - `staleSaveConflicts`: 같은 판을 두 번 `find`해 a, b → a에 질문 추가 후 save → b에 질문 추가 후 save는 `GameConflictException`, DB의 질문 수는 1.
  - `missingScenarioIsHidden`: `JdbcClient`로 `scenario_slug = 'gone'`인 행을 직접 넣고 `list(client)`와 `find`에 안 나온다.
  - `outOfRangeKeywordsAreIgnoredOnReveal`: `Game.restore(... status SOLVED, keywordFound {1,2,3,9}, bonusFound {1,7} ...)`로 만든 판의 `GameDtos.GameView.of(game, images).reveal()`에서 `keywords().size()`가 시나리오 핵심 수와 같고 예외가 없다.
  - `deleteRemovesEntries`: 질문이 있는 판을 `delete` → true, `game_entries`에 그 game_id 행 0개. 다른 client로 `delete` → false.

- [ ] **Step 2: 실행해 실패 확인** — Run: `cd backend && ./gradlew test --tests GameStoreTests` / Expected: 컴파일 실패(`restore`, `find` 등 없음).

- [ ] **Step 3: `Game` 수정, 엔티티·리포지토리·`GameStore` 구현** — 메모리 맵, `evictIdle()`, `@Scheduled`를 없앤다. `BackendApplication`의 `@EnableScheduling`도 다른 `@Scheduled`가 없으므로 지운다.
  `GameProperties`에서 `idleTtl`을 지우고 `boolean cookieSecure`로 바꾼다. `application.yml`의 `game.idle-ttl`을 지우고 `cookie-secure: ${COOKIE_SECURE:false}`를 넣는다.
  이 단계에서 `GameService`는 컴파일만 되게 임시로 맞춘다(실제 변경은 Task 4).

- [ ] **Step 4: 실행해 통과 확인** — Run: `./gradlew test --tests GameStoreTests` / Expected: PASS 7개.

- [ ] **Step 5: 커밋** — `git commit -m "진행 저장: 게임을 DB에 쓰고 되살린다"`

---

### Task 3: 기기 ID 쿠키

**Files:**
- Create: `backend/src/main/java/com/seaturtle/office/game/ClientIdFilter.java`
- Test: `backend/src/test/java/com/seaturtle/office/ClientIdFilterTests.java`

**Interfaces:**
- Consumes: `GameProperties.cookieSecure()`.
- Produces: `ClientIdFilter extends OncePerRequestFilter`(`@Component`), `/api/`로 시작하는 요청에만 동작.
  `public static final String COOKIE = "sto_client"`, `public static final String ATTRIBUTE = "clientId"`.
  요청 속성 `ATTRIBUTE`에 UUID 문자열(소문자 표준형)을 넣는다. 컨트롤러는 `@RequestAttribute(ClientIdFilter.ATTRIBUTE) String clientId`로 받는다.

- [ ] **Step 1: 실패하는 테스트 작성** (`MockHttpServletRequest`/`Response`와 `MockFilterChain`으로 필터만 돌린다)
  - `issuesCookieWhenMissing`: 쿠키 없이 `/api/scenarios` → 응답 `Set-Cookie`에 `sto_client=<UUID>`, `HttpOnly`, `SameSite=Lax`, `Path=/`, `Max-Age=31536000`이 있고 `Secure`는 없다. 요청 속성 값과 쿠키 값이 같다.
  - `keepsValidCookieAndRefreshes`: `sto_client=1b4e28ba-2fa1-11d2-883f-0016d3cca427` → 속성이 그 값, `Set-Cookie`가 같은 값으로 다시 나간다.
  - `replacesInvalidCookie`: `sto_client=hello` → 속성은 `hello`가 아닌 새 UUID.
  - `secureWhenConfigured`: `cookieSecure=true`로 만든 필터 → `Set-Cookie`에 `Secure`.
  - `skipsNonApi`: `/index.html` → `Set-Cookie` 없음.

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests ClientIdFilterTests` / Expected: 컴파일 실패.
- [ ] **Step 3: 구현** — `ResponseCookie`로 헤더를 만든다. UUID 판별은 `UUID.fromString` 후 다시 문자열로 바꿔 원래와 같은지 비교.
- [ ] **Step 4: 통과 확인** — Expected: PASS 5개.
- [ ] **Step 5: 커밋** — `git commit -m "진행 저장: 기기마다 sto_client 쿠키를 준다"`

---

### Task 4: 주인 확인, 이어하기, 초기화 API

**Files:**
- Modify: `game/GameService.java`, `game/GameController.java`, `game/GameDtos.java`, `game/ApiExceptionHandler.java`
- Modify(테스트 옮기기): `backend/src/test/java/com/seaturtle/office/GameFlowTests.java`
- Modify: `DEVELOPMENT.md`(API 표가 있으면 백엔드 API 추가분)

**Interfaces:**
- Consumes: Task 2의 `GameStore`, Task 3의 `ClientIdFilter.ATTRIBUTE`.
- Produces:
  - `GameService`:
    - `record Started(Game game, boolean created)`, `Started start(String clientId, String slug)` — 있으면 그 판(created=false). 없으면 만들고, `DataIntegrityViolationException`이면 `findByScenario`로 다시 찾아 돌려준다.
    - `Game get(String clientId, String id)` — 없으면 `GameNotFoundException("게임")`.
    - `List<Game> list(String clientId)`
    - `Game ask(String clientId, String id, String question)`, `Game guess(...)`, `Game hint(String clientId, String id)`, `Game giveUp(String clientId, String id)` — 모두 읽기 → 규칙 확인 → (질문·추리는 트랜잭션 없이 판정) → 도메인 변경 → `store.save` 후 그 판을 돌려준다.
    - `void reset(String clientId, String id)` — 지운 게 없으면 `GameNotFoundException("게임")`.
  - `GameDtos.MyGame(String id, String scenario, GameStatus status, int used, int limit, int score)` + `static MyGame of(Game)`.
  - API: `POST /api/games` 201(새 판) / 200(이미 있음), `GET /api/games` → `List<MyGame>`, `DELETE /api/games/{id}` → 204. 나머지 경로 그대로, 모두 `clientId` 기준.
  - `ApiExceptionHandler`: `GameConflictException` → 409, 메시지 그대로.

- [ ] **Step 1: 기존 `GameFlowTests`를 새 시그니처로 옮긴다** — 필드 `String client = UUID.randomUUID().toString()`, MockMvc 요청에 `.cookie(new Cookie(ClientIdFilter.COOKIE, client))`.
  서비스가 매번 새 `Game`을 돌려주므로 `game.status()` 대신 돌려받은 값이나 `games.get(client, id)`로 확인한다. `ask`가 `Game`을 돌려주므로 `q1.no()`는 `questions().getLast().no()`로.

- [ ] **Step 2: 새 테스트 작성**
  - `stateSurvivesReload`: 질문 2개, 힌트 1, 추리 1 뒤 `games.get(client, id)`의 기록이 같다.
  - `startReturnsSameGameForSameScenario`: `start` 두 번 → 같은 id, 둘째 `created()`는 false. MockMvc로 첫 요청 201, 둘째 200.
  - `otherDeviceGets404`: 다른 쿠키로 `GET`, 질문, `DELETE /api/games/{id}` 모두 404이고, 내 판은 그대로 있다.
  - `myGamesListsStatus`: 하나는 질문 3개 진행 중, 하나는 포기 → `GET /api/games`에 두 항목, `used`, `status`, `limit`(40), `score`.
  - `resetThenStartGivesFreshGame`: 질문 뒤 `DELETE` 204 → `POST /api/games` 201, 새 id, 질문 0.
  - `staleWriteGives409`: 판정기 스텁이 질문 `"느린"`을 받으면 판정 도중(스텁 안에서) 같은 판을 `games.giveUp(client, id)` 한다 → 원래 질문 요청은 409와 409 문구, 판은 `GAVE_UP`이고 질문 0.
  - `judgeErrorKeepsVersion`(기존 `judgeErrorDoesNotSpend`를 확장): 판정 실패 뒤 `used` 0, 이어서 정상 질문이 성공한다.
  - `requestWithoutCookieStartsFresh`: 쿠키 없이 `POST /api/games` → 201, 응답에 `Set-Cookie: sto_client`.

- [ ] **Step 3: 실패 확인** — Run: `./gradlew test --tests GameFlowTests` / Expected: 새 테스트 FAIL 또는 컴파일 실패.
- [ ] **Step 4: 서비스·컨트롤러·DTO·예외 처리 구현**
- [ ] **Step 5: 전체 통과 확인** — Run: `./gradlew test` / Expected: 모두 PASS.
- [ ] **Step 6: 실제로 켜 보기** — `./gradlew bootRun`(DB 컨테이너가 자동으로 뜬다) 뒤
  `curl -i -c /tmp/c -b /tmp/c -X POST localhost:8080/api/games -H 'Content-Type: application/json' -d '{"scenario":"boys-order"}'` → 201과 `Set-Cookie`.
  백엔드를 껐다 켜고 같은 쿠키로 `GET /api/games` → 방금 판이 있다.
- [ ] **Step 7: 커밋** — `git commit -m "진행 저장: 기기별로 판을 찾고 이어하고 초기화한다"`

---

### Task 5: 메인 페이지 상태 표시와 이어하기

**Files:**
- Modify: `frontend/src/api.ts`, `frontend/src/ScenarioList.tsx`, `frontend/src/App.tsx`, `frontend/src/index.css`
- Test: `frontend/src/ScenarioList.test.tsx`, `frontend/src/api.test.ts`

**Interfaces:**
- Consumes: Task 4의 API.
- Produces:
  - `api.ts`: `export interface MyGame { id: string; scenario: string; status: GameStatus; used: number; limit: number; score: number }`.
    `api.myGames(): Promise<MyGame[]>`(`GET /games`), `api.reset(id: string): Promise<void>`(`DELETE /games/{id}`).
    `request`에 `method`를 고를 수 있게 하고, 204면 본문을 읽지 않고 `undefined`를 돌려준다.
  - `export function progressLabel(g: MyGame): string` — `PLAYING` → `진행 중 (12/40)`, `SOLVED` → `해결 · 87점`, `FAILED`/`GAVE_UP` → `해결 못 함`.
  - `ScenarioList` 속성 `onOpen: (game: GameView) => void`(기존 `onStarted` 대체).
  - `App`: `onOpen`이 `status === 'PLAYING'`이면 `#/game/{id}`, 아니면 `#/game/{id}/result`.

- [ ] **Step 1: 실패하는 테스트 작성**
  - `api.test.ts`: `reset`이 `DELETE /api/games/g1`을 보내고 204에서 오류 없이 끝난다. `progressLabel` 세 경우의 문구.
  - `ScenarioList.test.tsx`:
    - `myGames`가 `[{id:'g1', scenario:'boys-order', status:'SOLVED', used:13, limit:40, score:87}]`면 `열 개의 손목` 줄에 `해결 · 87점`, `일주일 연수` 줄에는 상태 글이 없다.
    - 진행 중이면 `진행 중 (12/40)`, 포기면 `해결 못 함`.
    - 끝난 판의 줄을 누르면 `start` 응답(status `SOLVED`)이 `onOpen`으로 넘어간다.
    - 머리글이 `미해결 1건`(시나리오 2개 중 1개 해결).
    - `myGames`가 실패해도 두 시나리오가 다 보이고 오류 문구는 없다.
- [ ] **Step 2: 실패 확인** — Run: `cd frontend && npm test` / Expected: 새 테스트 FAIL.
- [ ] **Step 3: 구현** — 상태 글은 `archive-row-head` 안에 `<span className={'archive-progress ' + kind}>`(kind: `playing`/`solved`/`unsolved`). 색은 지금 결과 페이지의 해결/미해결 색 변수를 쓴다.
  "미해결 N건"은 `SOLVED`가 아닌 시나리오 수.
- [ ] **Step 4: 통과 확인** — Run: `npm test && npm run lint && npm run build` / Expected: 모두 성공.
- [ ] **Step 5: 커밋** — `git commit -m "진행 저장: 메인 페이지에 사건별 진행 상태를 보이고 끝난 사건은 결과로 연다"`

---

### Task 6: 결과 페이지 초기화, 409와 404 처리

**Files:**
- Modify: `frontend/src/ResultPage.tsx`, `frontend/src/GamePage.tsx`, `frontend/src/App.tsx`
- Test: `frontend/src/ResultPage.test.tsx`, `frontend/src/GamePage.test.tsx`

**Interfaces:**
- Consumes: `api.reset`, `api.start`, `ConfirmDialog`.
- Produces: `ResultPage` 새 속성 `onRestarted: (gameId: string) => void`. `App`은 `replaceRoute('#/game/' + id)`를 넘긴다.

- [ ] **Step 1: 실패하는 테스트 작성**
  - `ResultPage.test.tsx`:
    - 결과 막대에 `초기화` 버튼 → 누르면 확인창(제목 `사건 기록 초기화`, 본문 `이 사건의 질문, 추리, 계시 기록이 모두 지워지고 처음부터 다시 시작합니다.`, 버튼 `초기화`/`취소`).
    - 확인 → `api.reset('g1')`, `api.start(<시나리오 slug>)` 순서로 부르고 `onRestarted('g2')`.
    - 취소 → 아무 요청도 없다.
    - `reset`이 실패하면 오류 문구가 보이고 `onRestarted`는 불리지 않는다.
    - 기존 `만료된 게임은 오류와 목록으로`의 기대 문구를 `기록을 찾을 수 없습니다.`로.
  - `GamePage.test.tsx`:
    - 404 문구 `기록을 찾을 수 없습니다.`
    - 질문 요청이 `ApiError('다른 창에서 진행이 바뀌었습니다. 새로고침해 주세요.', 409)`로 실패하면 그 문구가 보이고, `api.game`을 다시 불러 화면이 새 상태(질문 수)로 바뀌며, 입력칸에 보낸 글이 남아 있다.
    - 다시 불러온 판이 끝난 상태면 `onEnded`가 불린다.
- [ ] **Step 2: 실패 확인** — Run: `npm test` / Expected: 새 테스트 FAIL.
- [ ] **Step 3: 구현** — 초기화 버튼은 `목록으로` 옆 `btn`. 409면 `submit`/`act`의 오류 처리에서 `api.game`으로 다시 불러 `setGame`(끝났으면 `onEnded()`).
- [ ] **Step 4: 통과 확인** — Run: `npm test && npm run lint && npm run build` / Expected: 모두 성공.
- [ ] **Step 5: 화면에서 확인** — AI 서버, 백엔드, 프런트를 켜고: 질문 몇 개 → 목록으로 → `진행 중` 표시 → 다시 들어가 기록 확인 → 포기 → 목록에 `해결 못 함` → 들어가면 결과 페이지 → 초기화 → 빈 게임. 백엔드를 다시 켜도 목록 상태가 남는다.
- [ ] **Step 6: 커밋** — `git commit -m "진행 저장: 결과 페이지에서 사건을 초기화하고 다른 창의 변경을 알린다"`
