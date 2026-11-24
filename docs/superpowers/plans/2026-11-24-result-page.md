# 결과 페이지 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 게임이 끝나면 신호 단절 연출을 보여 준 뒤 `#/game/{id}/result` 결과 페이지로 넘어간다. 결과 페이지에는 판정, 점수, [진상] [기록] 탭이 있다.

**구조:**
- 사건 기록 화면과 수신 기록 화면(사용 막대 포함)을 `GameRecords.tsx`로 떼어 낸다. `GamePage`와 새 `ResultPage`가 함께 쓴다.
- `App`이 주소로 두 페이지를 고른다.
- `GamePage`는 게임이 끝나면 `SignalCut` 연출을 띄우고, 연출이 끝나면 `onEnded`를 부른다.

**기술:** React, TypeScript, Vitest, Testing Library. 백엔드는 바꾸지 않는다.

**설계:** `docs/superpowers/specs/2026-11-24-result-page-design.md`

## 전체 제약
- 화면 글은 한국어다. 문구는 이 계획에 적힌 그대로 쓴다.
- 결과 머리 줄: `교신 종료 // 사건 기록 봉인`.
- 판정 이름:
  - SOLVED: `사건 해결`
  - FAILED, GAVE_UP: `미해결 종결`
- 보조 문구:
  - FAILED: `마지막 추리 실패`
  - GAVE_UP: `조사 중단`
  - SOLVED: 점수 계산 줄(`100 − 사용 n = s`, 보너스가 있으면 `100 − 사용 n + 보너스 b = s`)
- 탭은 `진상`, `기록` 두 개다. 처음에는 진상 탭이 열린다.
- 연출 문구는 `교신 종료`, 길이는 1600ms다.
- 브라우저가 `prefers-reduced-motion: reduce`이면 연출 없이 바로 넘어간다.
- 색:
  - 정답: 노란 빛, 기존 계시 색 변수.
  - 실패와 포기: 빨간 빛, 송신 단말 빨강.
- 주소 이동은 모두 `window.location.replace`로 한다. 끝난 게임 화면이 뒤로 가기 기록에 남지 않게 하기 위해서다.
- 진행 중 게임 화면의 모양과 동작, 그리고 기존 접근성 이름은 바꾸지 않는다.

## 검토 초점
1. **추리 응답에서 게임이 끝나면서 계시도 새로 열린 경우:** 계시창이 자동으로 뜨지 않고 연출로 넘어간다. 테스트는 작업 3에 둔다.
2. **움직임 줄이기 설정:** 연출 화면 없이 바로 `onEnded`가 불린다. 테스트는 작업 3에 둔다.
3. **진행 중인 게임의 결과 주소로 들어온 경우:** 게임 화면 주소로 돌려보낸다(`onResume`). 테스트는 작업 2에 둔다.
4. **만료된 게임의 결과 주소(404):** 오류 문구와 `목록으로` 버튼이 보인다. 테스트는 작업 2에 둔다.
5. **연출 도중에 페이지를 떠난 경우(로고 누름):** 타이머가 정리되어, 떠난 뒤에 `onEnded`가 불리지 않는다. 테스트는 작업 3에 둔다.

---

### Task 1: 공용 기록 화면 떼어 내기 (동작 변화 없음)

**파일:**
- 만들기: `frontend/src/GameRecords.tsx`
- 고치기: `frontend/src/GamePage.tsx`

**인터페이스 (만드는 것):**
- `export const CLOSENESS_LINES: Record<Closeness, string>`
  - `GamePage.tsx`에서 옮긴다.
- `export function CaseScreen({ game, label, children }: { game: GameView; label: string; children?: ReactNode })`
  - 지금의 `<aside className="case-device">…</aside>` 전체다.
  - 머리 글자는 `label`로 받는다. 게임은 `사건 기록 // 열람 중`, 결과는 `사건 기록 // 봉인`이다.
  - `children`은 `problem` 아래에 놓인다. 게임 화면의 포기 버튼 자리다.
  - 표지 대체 로직(`coverFailed`)도 이 안으로 옮긴다.
- `export function ReceiveLog({ game, busy, freshSeqs, pending, logRef, onOpenHint, onRequestHint, children })`
  - 지금의 `<div className="radio-unit">` 전체다. 머리, 사용 막대, 기록 목록이 들어간다.
  - `children`은 수신 기록 아래(`radio-unit` 안)에 놓인다. 게임 화면의 송신 단말 자리다.
  - 타입:
    - `freshSeqs: Set<number>`
    - `pending: { mode: 'question' | 'guess'; text: string } | null`
    - `logRef?: RefObject<HTMLOListElement | null>`
    - `onOpenHint: (index: number, el: HTMLElement) => void`
    - `onRequestHint?: () => void`
  - `onRequestHint`가 없으면 다음 계시 요청 눈금도 잠김으로 그린다.
  - `UsageBar`도 이 파일로 옮긴다(내보내지 않음).

- [ ] **1단계:** 위 인터페이스대로 옮긴다. `GamePage`는 이것들을 조합하고, 송신 단말 `form`과 포기 버튼을 `children`으로 넘긴다. 진상 보고서 `article`은 이 작업에서는 그대로 둔다.
- [ ] **2단계:** `cd frontend && npx vitest run && npx tsc -b && npm run lint`를 실행한다. 기존 42개 테스트가 그대로 통과해야 한다.
- [ ] **3단계:** 커밋한다. `git commit -m "게임 화면의 사건 기록과 수신 기록을 공용 컴포넌트로 떼어 낸다"`

### Task 2: 결과 페이지와 주소

**파일:**
- 만들기: `frontend/src/ResultPage.tsx`, `frontend/src/ResultPage.test.tsx`
- 고치기:
  - `frontend/src/App.tsx`
  - `frontend/src/GamePage.tsx`: 진상 `article`과 `VERDICT`를 지운다.
  - `frontend/src/GamePage.test.tsx`: 진상, 점수, 실패 도장 테스트 3개를 결과 페이지 테스트로 옮긴다.
  - `frontend/src/index.css`

**인터페이스:**
- 쓰는 것: 작업 1의 `CaseScreen`, `ReceiveLog`.
- 만드는 것:
  - `export function readRoute(hash: string): { gameId: string | null; view: 'play' | 'result' }`
    - `App.tsx`에 둔다.
    - 정규식은 `^#\/game\/([\w-]+)(\/result)?$`다.
  - `export default function ResultPage({ gameId, onExit, onResume }: { gameId: string; onExit: () => void; onResume: () => void })`

- [ ] **1단계: 실패하는 테스트를 쓴다** (`ResultPage.test.tsx`, api 모킹은 `GamePage.test.tsx`와 같은 방식)
  - **`정답이면 사건 해결, 점수, 계산 줄`**
    - SOLVED, used 23, score 87, bonusPoints 10일 때 다음이 보인다.
    - `교신 종료 // 사건 기록 봉인`, `사건 해결`, `87점`, `100 − 사용 23 + 보너스 10 = 87`
  - **`실패와 포기는 미해결 종결과 보조 문구`**
    - FAILED이면 `미해결 종결`, `마지막 추리 실패`, `0점`이 보인다.
    - GAVE_UP이면 `조사 중단`이 보인다.
  - **`처음에는 진상 탭: 진상, 핵심 키워드, 보너스`**
    - `tab` `진상`이 `aria-selected=true`다.
    - `청소부`, `보너스 1/2`, `맞힘`이 보인다.
    - 송신 단말(`textbox`)과 `포기하고 진상 보기`는 없다.
  - **`기록 탭은 사건 기록과 수신 기록을 함께, 읽기 전용으로`**
    - `기록` 탭을 누르면 문제 문장, `Q1` 질문, `수신 기록` 제목이 모두 보인다.
    - `textbox`가 없다.
    - `/계시 \d 요청/` 버튼이 없다.
  - **`기록 탭에서 열린 계시를 다시 본다`**
    - `계시 1` 버튼을 누르면 `dialog` `계시 1`이 뜬다.
  - **`진행 중인 게임이면 onResume`** (검토 초점 3)
  - **`만료된 게임은 오류와 목록으로`** (검토 초점 4)
    - api.game이 `ApiError(404)`를 던지면 `게임이 만료되었거나 서버가 다시 시작되었습니다.`와 `목록으로` 버튼이 보인다.
  - **`목록으로 버튼은 onExit`**
  - **`readRoute`** (`App.test.tsx` 새로 만들기)
    - `#/` → `{ gameId: null, view: 'play' }`
    - `#/game/abc` → `{ gameId: 'abc', view: 'play' }`
    - `#/game/abc/result` → `{ gameId: 'abc', view: 'result' }`
- [ ] **2단계:** `npx vitest run src/ResultPage.test.tsx src/App.test.tsx`를 실행한다. 모듈이 없어서 실패해야 한다.
- [ ] **3단계: `ResultPage`를 만든다.**
  - 상태는 `game`, `loadError`, `tab: 'truth' | 'records'`, `openHint`다.
  - 불러온 게임이 PLAYING이면 `onResume()`을 부른다.
  - 머리 구성:
    - `.result-head.solved` 또는 `.result-head.closed`
    - 머리 줄
    - 판정 `h1`
    - 점수
    - 보조 문구
    - `role="tablist"` 탭 두 개
    - `목록으로` 버튼
  - 진상 탭은 지금 `article.reveal`의 진상, 핵심 키워드, 보너스, `reveal-meta`를 옮긴 것이다. `다른 사건 고르기` 버튼은 빼고 머리의 `목록으로`로 대신한다.
  - 기록 탭은 `<section className="game">` 안에 다음을 둔다.
    - `CaseScreen`(`label="사건 기록 // 봉인"`)
    - `<div className="board"><ReceiveLog … freshSeqs={new Set()} pending={null} busy={false} /></div>`
    - `onRequestHint`는 넘기지 않는다.
  - `RevelationDialog`는 `openHint`로 띄운다.
- [ ] **4단계: `App`에서 주소를 나눈다.**
  - `readRoute(window.location.hash)`의 결과에 따라 화면을 고른다.
    - `view === 'result'`이면 `ResultPage`를 띄우고 `onResume={() => location.replace('#/game/' + id)}`를 넘긴다.
    - 아니면 `GamePage`를 띄운다.
  - `GamePage`에서 진상 `article`과 `VERDICT`를 지운다. 옮긴 테스트 3개도 `GamePage.test.tsx`에서 지운다.
- [ ] **5단계: CSS를 쓴다.**
  - `.result-head`: 송신 단말과 같은 어두운 화면 느낌이다.
    - `.solved`는 계시 노랑 테두리와 빛.
    - `.closed`는 빨강 테두리와 빛.
  - `.result-tabs`: 송신 단말의 질문·추리 버튼과 같은 모양이다.
  - 진상 패널은 기존 `.reveal` 스타일을 그대로 쓴다.
- [ ] **6단계:** `npx vitest run && npx tsc -b && npm run lint`를 실행한다. 모두 통과해야 한다.
- [ ] **7단계:** 커밋한다. `git commit -m "결과 페이지: 판정과 점수, 진상 탭과 기록 탭"`

### Task 3: 신호 단절 연출과 넘어가기

**파일:**
- 만들기: `frontend/src/SignalCut.tsx`
- 고치기: `frontend/src/GamePage.tsx`, `frontend/src/App.tsx`, `frontend/src/GamePage.test.tsx`, `frontend/src/index.css`

**인터페이스:**
- 만드는 것:
  - `export default function SignalCut({ solved, onDone }: { solved: boolean; onDone: () => void })`
    - 1600ms 뒤에 `onDone`을 부르고, 사라질 때 타이머를 정리한다.
    - 움직임 줄이기 설정이면 바로 `onDone`을 부른다.
    - `role="status"`, 글자는 `교신 종료`다.
    - `Transmission.tsx`의 움직임 줄이기 판별 함수를 내보내서 함께 쓴다.
  - `GamePage` props에 `onEnded: () => void`를 더한다.
  - `App`은 `onEnded={() => location.replace('#/game/' + id + '/result')}`를 넘긴다.

- [ ] **1단계: 실패하는 테스트를 쓴다** (`GamePage.test.tsx`, 가짜 타이머 사용)
  - **`정답 추리 응답이 오면 교신 종료 연출 뒤 onEnded`**
    - 추리 응답이 SOLVED다.
    - 응답 직후에는 `status` `교신 종료`가 보이고, `onEnded`는 아직 불리지 않는다.
    - 1600ms가 지나면 `onEnded`가 1번 불린다.
  - **`포기해도 같은 연출 뒤 onEnded`**
  - **`이미 끝난 게임을 열면 연출 없이 바로 onEnded`**
    - 불러온 게임이 FAILED이면 `교신 종료`가 없고 `onEnded`가 바로 불린다.
  - **`게임이 끝나면서 계시가 새로 열려도 계시창은 뜨지 않는다`** (검토 초점 1)
    - 기존 테스트 `원래 초점 자리가 사라졌으면…`은 이 상황(SOLVED와 새 계시)을 쓰고 있다.
    - 그래서 그 테스트는 PLAYING인 채 계시가 열리는 상황으로 바꾼다. 초점이 돌아가는 동작은 그대로 확인한다.
  - **`움직임 줄이기면 연출 화면 없이 바로 onEnded`** (검토 초점 2)
    - `window.matchMedia`를 reduce로 모킹한다.
  - **`연출 도중 화면이 사라지면 onEnded를 부르지 않는다`** (검토 초점 5)
    - 연출이 뜬 뒤 `unmount`한다.
    - 1600ms가 지나도 `onEnded`는 0번이다.
- [ ] **2단계:** `npx vitest run src/GamePage.test.tsx`를 실행한다. 새 테스트가 실패해야 한다.
- [ ] **3단계: 구현한다.**
  - `GamePage`에 `ending` 상태를 둔다.
  - `receive()`에서 `next.status !== 'PLAYING'`이면 계시창을 자동으로 띄우지 않고 `ending`을 켠다.
  - 처음 불러온 게임이 끝난 상태이면 effect에서 `onEnded()`를 부른다.
  - `ending`이면 `<SignalCut solved={status === 'SOLVED'} onDone={onEnded} />`를 띄운다.
- [ ] **4단계: CSS를 쓴다.** `.signal-cut`은 화면 전체를 덮는 고정 층이다(`position: fixed`, `inset: 0`, `z-index`는 계시창 위). 순서는 다음과 같다.
  - 0~0.6초: 잡음 줄무늬가 커지며 화면이 흔들린다.
  - 0.6~1.0초: 가운데 가로선으로 접히며 검게 닫힌다(옛 화면이 꺼지는 모양).
  - 1.0~1.6초: `교신 종료`가 깜빡이며 뜬다.
  - 색은 `.solved`면 노랑, 아니면 빨강이다.
  - `prefers-reduced-motion`에서는 애니메이션을 끈다.
- [ ] **5단계:** `npx vitest run && npx tsc -b && npm run lint`를 실행한다. 모두 통과해야 한다.
- [ ] **6단계:** 커밋한다. `git commit -m "게임이 끝나면 신호 단절 연출 뒤 결과 페이지로 넘어간다"`

### Task 4: 실제 화면 확인과 문서

- [ ] **1단계:** 실제 브라우저(기존 CDP 스크립트 방식)로 다음을 찍어서 겹침과 넘침을 확인한다.
  - 정답 결과의 진상 탭
  - 정답 결과의 기록 탭
  - 포기 결과
  - 연출 중간 프레임
  - 좁은 화면(390px)의 결과 페이지
- [ ] **2단계:** `DEVELOPMENT.md`의 화면 설명에 결과 페이지 주소와 흐름을 한 줄 더한다.
- [ ] **3단계:** 커밋한다. `git commit -m "결과 페이지 문서"`
