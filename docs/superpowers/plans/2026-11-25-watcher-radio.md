# 응시자 무전 연출과 이미지 자리 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 힌트가 열린 순서를 백엔드가 기록하고 이미지 파일을 내보내며, 화면은 오른쪽 기록지를 어두운 수신 화면으로 바꿔 질문·추리·계시를 순서대로 보여 주고, 새로 들어온 줄만 깨진 글자에서 맞춰지는 연출을 한다.

**Architecture:** `Game`이 사용 횟수가 10·20·30을 넘는 순간 `HintRecord(no, seq, text)`를 남긴다. `ScenarioImages`가 `scenarios/images/<slug>/`에서 `cover`·`hint-N` 파일을 찾고 주소를 만든다. 화면은 `seq` 순으로 늘어놓고, 처음 본 `seq`만 연출한다.

**Tech Stack:** Spring Boot 4 + JUnit + MockMvc, React + Vitest + Testing Library

**Spec:** `docs/superpowers/specs/2026-11-25-watcher-radio-design.md`

## Global Constraints
- 이미지 이름은 `cover`, `hint-1`, `hint-2`, `hint-3`만. 확장자는 `webp` → `png` → `jpg` 순으로 찾는다. 요청할 때마다 파일을 확인한다.
- 이미지 주소: `/api/scenarios/{slug}/images/{name}` (확장자 없이). 허용하지 않은 이름, 없는 시나리오, 없는 파일은 404.
- 화면 고정 이미지 자리: `frontend/public/backgrounds/room.webp`, `frontend/public/brand/logo.webp`, `frontend/public/covers/default.webp`. 없으면 지금 모습.
- 문구(그대로): 수신 기록 제목 `수신 기록`, 신호 `응시자와 연결됨`, 기다리는 표시 `수신 중`, 힌트 줄 이름 `계시 N`, 정답 `모두 맞혔구나.`
- 계속 깔리는 노이즈와 화면 흔들림은 쓰지 않는다. 일그러짐은 새로 들어온 줄에 한 번, `prefers-reduced-motion`이면 없음.
- 사용자에게 보이는 문장과 주석은 한국어.

## Review Focus
- 사용 8에서 추리(+5=13) → 계시 1이 그 추리 바로 뒤 `seq`로 기록 (Task 1 테스트)
- 사용 25에서 힌트 받기 → 30, 계시 3이 기록되고 계시 2는 이미 있던 자리 그대로 (Task 1 테스트)
- `../`가 섞인 이미지 이름 요청 → 404 (Task 1 테스트)
- 새로고침(처음 불러온 기록)에는 일그러짐 없음, 그 뒤 새로 온 줄만 (Task 2 테스트)
- 이미지 파일이 없을 때 이미지 요소가 아예 없음 (Task 2 테스트)

---

### Task 1: 백엔드 — 힌트 기록과 이미지

**Files:**
- Modify: `backend/src/main/java/com/seaturtle/office/game/Game.java`, `GameDtos.java`, `GameController.java`
- Create: `backend/src/main/java/com/seaturtle/office/content/ScenarioImages.java`
- Test: `backend/src/test/java/com/seaturtle/office/GameFlowTests.java`, Create: `backend/src/test/java/com/seaturtle/office/ScenarioImagesTests.java`

**Interfaces:**
- Produces: `record Game.HintRecord(int no, int seq, String text)`; `List<HintRecord> Game.hints()`. 사용 횟수가 바뀌는 세 곳(`addQuestion`, `addGuess`, `useHint`)에서 `openHints()`가 `used / HINT_STEP`(최대 `hintCount()`)까지 아직 없는 힌트를 `++seq`로 차례로 남긴다. `hintsOpen()`은 `hints().size()`.
- Produces: `@Component ScenarioImages(ContentProperties)`: `Optional<Path> find(String slug, String name)`, `String url(String slug, String name)`(파일이 없으면 `null`).
- Produces: `GameDtos.HintView(int no, int seq, String text, String image)`, `ScenarioSummary(..., String cover)`. `GameView.hints`는 `List<HintView>`. `GameView.of(Game, ScenarioImages)`, `ScenarioSummary.of(Scenario, ScenarioImages)`.
- Produces: `GET /api/scenarios/{slug}/images/{name}` → 파일(Content-Type은 확장자로), 아니면 404.

- [ ] **Step 1: 실패하는 테스트**
  - `hintRecordedAtTheMomentItOpens` (`GameFlowTests`): 질문 8번(seq 1~8) → 추리 오답(seq 9, 사용 13) → `game.hints()`가 `[HintRecord(1, 10, 힌트1)]`. 이어서 질문 2번(seq 11·12, 사용 15)·힌트 받기(사용 20) → 계시 2가 `seq 13`. 
  - `hintButtonKeepsEarlierHintPositions`: 질문 25번 → 계시 1·2가 각각 10·20번째 질문 바로 뒤 `seq`(11, 22); 힌트 받기 → 계시 3 `seq 28`, 앞의 둘은 그대로.
  - `gameViewHintsCarrySeq` (MockMvc): 질문 10번 뒤 `$.hints[0].no == 1`, `$.hints[0].seq == 11`, `$.hints[0].image` null.
  - `ScenarioImagesTests` (`@TempDir`에 `scenarios/images/boys-order/cover.png`, `hint-2.webp`를 만든 `ContentProperties`로): `find("boys-order","cover")`는 png 경로, `find("boys-order","hint-1")`은 비어 있음, `find("boys-order","../x")`와 `find("../..","cover")`는 비어 있음, `url(...)`은 `/api/scenarios/boys-order/images/cover`.
  - `imageEndpoint` (MockMvc, 실제 저장소에는 그림이 없으므로): `GET /api/scenarios/boys-order/images/cover` → 404, `.../images/..%2F..%2Fsecret` → 404.
- [ ] **Step 2: 실패 확인** — `cd backend && ./gradlew test` / 컴파일 실패(`HintRecord`, `ScenarioImages` 없음)
- [ ] **Step 3: 구현.** slug는 `ContentRepository.scenario(slug)`가 있는 것만, name은 허용 목록만 받아 경로 이동을 막는다. 컨트롤러는 `ResponseEntity<Resource>`(`MediaTypeFactory`로 Content-Type).
- [ ] **Step 4: 통과 확인** — `cd backend && ./gradlew test` / BUILD SUCCESSFUL
- [ ] **Step 5: 커밋** — `git commit -m "백엔드: 힌트가 열린 순서 기록, 시나리오 이미지 내보내기"`

---

### Task 2: 화면 — 수신 기록, 계시, 연출, 이미지 자리

**Files:**
- Modify: `frontend/src/api.ts`, `frontend/src/GamePage.tsx`, `frontend/src/ScenarioList.tsx`, `frontend/src/App.tsx`, `frontend/src/index.css`
- Create: `frontend/src/Transmission.tsx` (깨진 글자에서 맞춰지는 글자 하나), `frontend/src/WatcherEye.tsx` (눈 로고)
- Test: `frontend/src/GamePage.test.tsx`

**Interfaces:**
- Consumes: Task 1의 `hints: {no, seq, text, image}[]`, `scenario.cover`.
- Produces: `<Transmission text={string} animate={boolean} className? />` — `animate`가 참이면 0.8초 동안 무작위 글자에서 한 글자씩 맞춰지고 끝나면 `glitch` 클래스로 빨강·파랑 어긋남 1회, 거짓이거나 움직임 줄이기면 바로 `text`.

- [ ] **Step 1: 실패하는 테스트**

```tsx
it('계시를 질문 사이 seq 자리에 "계시 N"으로 보여 준다', async () => {
  await openGame({ used: 11,
    questions: [{ no: 1, seq: 1, text: 'q1', answer: 'NO' }, { no: 2, seq: 3, text: 'q2', answer: 'YES' }],
    hints: [{ no: 1, seq: 2, text: '첫 계시', image: null }] })
  const rows = screen.getAllByRole('listitem').map((li) => li.textContent ?? '')
  expect(rows.findIndex((t) => t.includes('계시 1'))).toBe(1)
  expect(screen.getByText('첫 계시')).toBeTruthy()
})

it('힌트 이미지가 있으면 계시 안에, 없으면 이미지 요소가 없다', async () => {
  await openGame({ hints: [{ no: 1, seq: 1, text: 'a', image: '/api/scenarios/s/images/hint-1' }, { no: 2, seq: 2, text: 'b', image: null }] })
  const imgs = screen.getAllByRole('img', { name: /계시/ })
  expect(imgs).toHaveLength(1)
  expect(imgs[0].getAttribute('src')).toBe('/api/scenarios/s/images/hint-1')
})

it('처음 불러온 기록은 연출 없이, 새로 온 답만 연출한다', async () => {
  const user = userEvent.setup()
  await openGame({ questions: [{ no: 1, seq: 1, text: 'q1', answer: 'NO' }] })
  expect(document.querySelectorAll('.transmitting')).toHaveLength(0)
  vi.mocked(api.ask).mockResolvedValue({ ...game, used: 2, questions: [
    { no: 1, seq: 1, text: 'q1', answer: 'NO' }, { no: 2, seq: 2, text: 'q2', answer: 'YES' }] })
  await user.type(screen.getByRole('textbox', { name: '질문' }), 'q2{Enter}')
  await vi.waitFor(() => expect(document.querySelectorAll('.transmitting')).toHaveLength(1))
})

it('수신 기록 머리와 정답 문구', async () => {
  await openGame({ guesses: [{ seq: 1, text: 't', matched: 3, total: 3, correct: true, missing: [] }] })
  expect(screen.getByText('수신 기록')).toBeTruthy()
  expect(screen.getByText('응시자와 연결됨')).toBeTruthy()
  expect(screen.getByText('모두 맞혔구나.')).toBeTruthy()
})

it('사건 서류에 대표 이미지가 있으면 보여 준다', async () => {
  await openGame({ scenario: { ...game.scenario, cover: '/api/scenarios/s/images/cover' } })
  expect(screen.getByRole('img', { name: '사건 표지' }).getAttribute('src')).toBe('/api/scenarios/s/images/cover')
})
```

기존 테스트의 `hints: ['h1']` 같은 문자열 목록은 `{ no, seq, text, image: null }`로, 기본 `game.scenario`에 `cover: null`을 더한다. 기존 "질문 응답으로 받은 게임 상태" 테스트의 `findByText('첫 힌트')`는 그대로 통과해야 한다.
- [ ] **Step 2: 실패 확인** — `cd frontend && npm test` / 새 테스트 FAIL, `npx tsc -b` 실패
- [ ] **Step 3: 구현.**
  - 수신 기록: 시안 `code-made-elements.html`의 1번에서 노이즈 캔버스와 흔들림을 뺀 모양. 질문·추리·계시를 `seq` 순으로. 처음 불러온 `seq` 집합을 기억해 두고, 그 뒤 새로 생긴 줄만 `animate`.
  - 답 도장 대신 `<Transmission>`(진행자 말, 색은 기존 `tone`). 안내 문장은 연출이 끝난 뒤 보인다(`animate`가 거짓이면 바로).
  - 기다리는 줄: `수신 중`(깨진 글자 깜빡임, 움직임 줄이기면 깜빡임 없음).
  - 계시 줄: 왼쪽 이름 `계시 N`, 오른쪽 글자 `<Transmission>`, 이미지가 있으면 그 아래 `<img alt="계시 N 이미지">`.
  - 추리 정답: `모두 맞혔구나.`(초록 `<Transmission>`), 오답: `핵심 n/m` + 빠졌을 때 문장.
  - 결과 도장: 시안 2번 모양(해결 초록, 실패 빨강, 미해결 종결 회색).
  - 왼쪽 사건 서류: 노란 힌트 메모 삭제. `scenario.cover` 또는 `/covers/default.webp`(불러오기 실패하면 숨김)를 `<img alt="사건 표지">`로. 사건 목록 카드에도 같은 표지(작게).
  - 배경: `body`에 `/backgrounds/room.webp`를 어둡게 덮어 깐다(없으면 지금 배경색만). 머리말 로고: `/brand/logo.webp`를 `<img>`로 시도하고 실패하면 지금 SVG.
- [ ] **Step 4: 통과 확인** — `cd frontend && npm test && npx tsc -b && npm run lint`
- [ ] **Step 5: 눈으로 확인** — 진행 중 게임(계시 1개 포함)에서 질문 하나를 보내 연출이 그 줄에만 나오는지, 새로고침하면 연출 없이 그대로인지. 1280px·520px 스크린샷.
- [ ] **Step 6: 커밋** — `git commit -m "화면: 응시자 무전 수신 기록, 계시, 이미지 자리"`

---

### Task 3: 문서와 정리

**Files:** Modify: `DEVELOPMENT.md`, `scenarios/README.md`, `docs/backlog.md`, `.gitignore`

- [ ] **Step 1:** `scenarios/README.md`에 "이미지" 절(폴더, 이름, 확장자, 크기 권장 500KB 안쪽, 진상이 그림으로 새지 않게). `DEVELOPMENT.md`에 이미지 자리 표와 이미지 API. `docs/backlog.md` 2번·4번에 "연출·이미지 자리 완료, 그림 만들기는 남음". `.gitignore`에 `.superpowers/`.
- [ ] **Step 2:** 세 프로젝트 테스트 — `cd ai && uv run pytest -q && cd ../backend && ./gradlew test && cd ../frontend && npm test`
- [ ] **Step 3: 커밋** — `git commit -m "문서: 이미지 자리와 응시자 연출"`
