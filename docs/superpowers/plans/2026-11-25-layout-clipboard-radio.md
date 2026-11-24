# 화면 배치: 클립보드 사건 기록과 무전기 조작판 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사건 기록과 수신 기록을 50:50으로 바꾸고, 사건 기록을 클립보드로, 입력칸을 수신 기록을 감싼 무전기 몸체의 조작판으로 바꾼다. 수신 기록 화면 안쪽은 그대로 둔다.

**Architecture:** `GamePage.tsx`의 구조(클립보드 감싸개, 무전기 몸체, 조작판)와 `index.css`만 바꾼다. 서버·API·규칙은 그대로.

**Tech Stack:** React + Vitest + Testing Library

**Spec:** `docs/superpowers/specs/2026-11-25-layout-clipboard-radio-design.md`

## Global Constraints
- 수신 기록 안쪽(`.transcript.radio` 안의 스타일과 연출)은 바꾸지 않는다. 예외: 좁아진 폭에서 답이 질문 아래로 내려가는 배치.
- 접근성 이름 유지: 탭 `질문하기`·`추리 제출`, 입력칸 `질문`·`추리`. 화면에 보이는 버튼 글자는 `질문`·`추리`.
- 문구(그대로): `힌트 요청 (사용 N으로)`, `포기하고 진상 보기`, `송신 ▶`, `수신 대기`, `추리는 사용 5를 씁니다.`, `이 추리는 횟수를 쓰지 않습니다.`, `질문을 모두 썼습니다. 마지막 추리 기회가 한 번 남았습니다.`
- 좁은 화면(760px 이하)은 위아래로 쌓는다.

## Review Focus
- 넓은 화면에서 사건 기록과 수신 기록 폭이 거의 같다 (스크린샷 확인)
- 좁아진 수신 기록에서 긴 질문과 긴 답(어느정도는 그렇단다)이 겹치지 않는다 (스크린샷 확인)
- 답을 기다리는 동안 송신 버튼이 `수신 대기`로 바뀌고 눌리지 않는다 (테스트)

---

### Task 1: 클립보드와 무전기 조작판

**Files:**
- Modify: `frontend/src/GamePage.tsx`, `frontend/src/index.css`
- Test: `frontend/src/GamePage.test.tsx`

- [ ] **Step 1: 실패하는 테스트**
  - 기존 테스트의 `힌트 받기 (사용 N으로)` 이름을 `힌트 요청 (사용 N으로)`로 바꾼다(테스트 3곳).
  - 새 테스트 `송신 버튼은 기다리는 동안 수신 대기로 바뀌고 눌리지 않는다`: `api.ask`를 끝나지 않는 요청으로 두고 질문을 보낸 뒤 `screen.getByRole('button', { name: '수신 대기' })`가 `disabled`.
  - 새 테스트 `조작판 버튼 글자는 질문·추리, 탭 이름은 그대로`: `getByRole('tab', { name: '질문하기' }).textContent === '질문'`, `getByRole('tab', { name: '추리 제출' }).textContent === '추리'`, `getByRole('button', { name: '송신 ▶' })`.
- [ ] **Step 2: 실패 확인** — `cd frontend && npm test`
- [ ] **Step 3: 구현.** `.game` 격자를 `1fr 1fr`로. 사건 기록 `aside.case`를 `.clipboard`(판, 집게) 안의 서류로. 힌트 버튼 글자 `힌트 요청 (사용 N으로)`. `.board`를 `.radio-unit`(금속 몸체)로 감싸고 `.composer`를 조작판으로: 탭 버튼(`aria-label`로 기존 이름 유지, 글자는 `질문`/`추리`), 녹색 액정 textarea, `송신 ▶` 버튼(기다리는 동안 `수신 대기`, disabled). 안내 줄은 조작판 아래. 좁아진 수신 기록에서 `.entry`가 좁으면 답이 질문 아래로(컨테이너 쿼리 또는 `.transcript.radio .entry` 격자를 두 줄로).
- [ ] **Step 4: 통과 확인** — `cd frontend && npm test && npx tsc -b && npm run lint`
- [ ] **Step 5: 눈으로 확인** — 진행 중 게임을 1280px·520px로 찍어 Review Focus 두 항목 확인.
- [ ] **Step 6: 커밋** — `git commit -m "화면: 클립보드 사건 기록과 무전기 조작판(50:50)"`

---

### Task 2: A1 송신 단말과 계시창

**Files:**
- Modify: `frontend/index.html`(Black Han Sans, Nanum Gothic Coding), `frontend/src/GamePage.tsx`, `frontend/src/index.css`
- Create: `frontend/src/RevelationDialog.tsx`
- Test: `frontend/src/GamePage.test.tsx`

**Interfaces:**
- Produces: `<RevelationDialog hints={HintRecord[]} index={number} onIndex={(i)=>void} onClose={()=>void} />` — `role="dialog"`, `aria-modal="true"`, 이름 `계시 N`, 그림은 `alt="계시 N 이미지"`, 버튼 `닫기`, 열린 계시 버튼들.

- [ ] **Step 1: 실패하는 테스트**
  - `계시는 수신 기록 줄에 없고 막대에서 연다`: 힌트 1개가 열린 게임 → `.log`에 `계시 1` 글자가 없다. 막대의 `계시 1` 버튼을 누르면 `getByRole('dialog', { name: '계시 1' })` 안에 계시 문장. `닫기`를 누르면 창이 사라진다. Esc로도 닫힌다.
  - `다음 계시 눈금으로 힌트를 요청한다`: `nextHintAt: 20`, 힌트 1개 → `계시 2 요청 (사용 20으로)` 버튼을 누르면 `api.hint` 호출. 잠긴 `계시 3`은 `disabled`.
  - `계시창 그림`: 그림이 있는 계시를 열면 창 안에 `img`(이름 `계시 1 이미지`), 없는 계시는 `img` 없음.
  - `새로 열린 계시는 계시창이 자동으로 뜬다`: `api.ask`가 힌트 1개가 새로 열린 게임을 돌려주면 `findByRole('dialog', { name: '계시 1' })`. 처음 불러온 게임에 이미 있던 계시는 창이 없다.
  - `클립보드에 힌트 요청 버튼이 없다`: `queryByRole('button', { name: /힌트 요청/ })` 가 null.
  - `송신 단말 머리와 명령조 문구`: `송신 단말 // CH-07`, 질문 입력칸 placeholder `예나 아니오로 답할 수 있는 질문을 입력하라`, `모든 송신은 기록된다`.
  - 기존 테스트 정리: 힌트 버튼을 찾던 테스트(`힌트 요청 (사용 N으로)`)는 막대의 `계시 N 요청 (사용 M으로)`로, `openGame`이 기다리는 요소는 `송신 ▶` 버튼으로, 계시를 수신 기록 줄 순서로 확인하던 테스트와 계시 그림 테스트는 계시창 테스트로 바꾼다.
- [ ] **Step 2: 실패 확인** — `cd frontend && npm test`
- [ ] **Step 3: 구현** (설계 문서 "추가 결정"대로)
- [ ] **Step 4: 통과 확인** — `cd frontend && npm test && npx tsc -b && npm run lint`
- [ ] **Step 5: 눈으로 확인** — 진행 중 게임 1280px·520px, 계시창 열린 상태 스크린샷
- [ ] **Step 6: 커밋** — `git commit -m "화면: 송신 단말 조작판, 사용 막대에서 여는 계시창"`
