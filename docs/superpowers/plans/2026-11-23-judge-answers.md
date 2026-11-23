# 진행자 답 표현과 답 설명 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 질문 판정을 9가지 코드로 바꾸고, 화면에 진행자의 말(5가지)과 그 아래 안내 문장(9가지)을 보여 주며, 핵심 질문 판단과 증거 표시를 없앤다.

**Architecture:** AI 서버(`ai/`)가 9가지 코드 중 하나를 고르고, 백엔드(`backend/`)는 코드를 그대로 저장·전달한다. 진행자의 말과 안내 문장은 프런트엔드 `api.ts` 한 곳에만 둔다. `key` 필드는 세 곳 모두에서 지운다.

**Tech Stack:** FastAPI + pydantic + pytest (`ai/`), Spring Boot 4 + JUnit (`backend/`), React + Vitest + Testing Library (`frontend/`)

**Spec:** `docs/superpowers/specs/2026-11-23-judge-answers-design.md`

## Global Constraints
- 코드는 정확히 이 9개, 이 순서: `YES`, `NO`, `PARTIAL_SOME`, `PARTIAL_CONDITION`, `IRRELEVANT_UNRELATED`, `IRRELEVANT_UNDECIDED`, `INVALID_FORM`, `INVALID_TRUTH`, `INVALID_RULE`.
- 진행자의 말과 안내 문장은 스펙 표의 문장을 글자 그대로 쓴다(마침표, 가운뎃점 `·` 포함).
- 서버끼리, 서버와 화면 사이에는 코드만 오간다. 문장은 `frontend/src/api.ts`에만 있다.
- AI 응답과 API 응답, 질문 기록 어디에도 `key`가 남지 않는다.
- 판정 근거(`reason`)는 지금처럼 AI 서버 로그에만 남고 응답에 넣지 않는다.
- 사용자에게 보이는 문장과 코드 주석은 한국어로 쓴다.

## Review Focus
- AI가 예전 코드(`PARTIAL`, `IRRELEVANT` 등)나 목록에 없는 코드를 돌려준다 → 판정 오류(502)로 처리되고 질문이 기록되지 않는다. (Task 1 테스트)
- 질문 기록이 프롬프트에 들어갈 때 새 코드 이름으로 들어간다. AI가 이전 판정을 보고 일관되게 답하려면 코드가 맞아야 한다. (Task 1 테스트)
- 판정 규칙에 9개 코드가 모두 설명되어 있지 않으면 AI가 일부 코드를 쓰지 않는다. (Task 1 테스트)
- 말이 같은 두 코드(`PARTIAL_SOME`/`PARTIAL_CONDITION` 등)는 도장 색이 같아야 한다. (Task 3 테스트)
- 좁은 화면(390px)에서 안내 문장이 도장과 겹치지 않고 아래로 내려간다. (Task 3 마지막 단계에서 눈으로 확인)

---

### Task 1: AI 서버가 9가지 코드로 판정한다

**Files:**
- Modify: `ai/app/prompts.py` (`ANSWERS`, `QUESTION_RULES`, `QUESTION_SCHEMA`)
- Modify: `ai/app/main.py` (`Answer`, `QuestionResult`, `QuestionJudgement`, `judge_question` 로그)
- Modify: `ai/app/judges.py` (`FakeJudge.complete_json`)
- Test: `ai/tests/test_judge.py`

**Interfaces:**
- Produces: `POST /judge/question` 응답 `{"answer": <9가지 코드 중 하나>}` (`key` 없음). 요청의 `history[].answer`도 9가지 코드.
- Produces: `ANSWERS: list[str]` = Global Constraints의 9개 코드, 같은 순서.

- [ ] **Step 1: 실패하는 테스트 쓰기** (`ai/tests/test_judge.py`)

```python
from app.prompts import ANSWERS, QUESTION_RULES, QUESTION_SCHEMA, HistoryItem

CODES = ["YES", "NO", "PARTIAL_SOME", "PARTIAL_CONDITION", "IRRELEVANT_UNRELATED",
         "IRRELEVANT_UNDECIDED", "INVALID_FORM", "INVALID_TRUTH", "INVALID_RULE"]

def test_answers_are_nine_codes():
    assert ANSWERS == CODES
    assert QUESTION_SCHEMA["required"] == ["reason", "answer"]
    assert "key" not in QUESTION_SCHEMA["properties"]

def test_rules_explain_every_code():
    for code in CODES:
        assert code in QUESTION_RULES

def test_history_uses_new_codes(content):
    s = content.scenario("last-rent")
    prompt = JudgePrompts(content, 40).question(s, [HistoryItem("건물주는 몇 살?", "IRRELEVANT_UNDECIDED")], "q")
    assert "- 건물주는 몇 살? → IRRELEVANT_UNDECIDED" in prompt.user
```

기존 `test_fake_judge_flow`의 기대값을 `{"answer": "IRRELEVANT_UNRELATED"}`로 바꾼다.
추가로, 가짜 판정기를 예전 코드를 돌려주는 판정기로 바꿔 끼운 클라이언트로 `/judge/question`을 부르면 502인지 보는 테스트:

```python
def test_old_answer_code_is_rejected(client):
    class OldJudge:
        name, fake = "old", True
        async def complete_json(self, prompt):
            return {"reason": "r", "answer": "PARTIAL"}
    saved = app.state.judge
    app.state.judge = OldJudge()
    try:
        r = client.post("/judge/question", json={"scenario": "last-rent", "question": "q"})
        assert r.status_code == 502
    finally:
        app.state.judge = saved
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd ai && uv run pytest -q`
Expected: `test_answers_are_nine_codes`, `test_rules_explain_every_code`, `test_fake_judge_flow`, `test_old_answer_code_is_rejected`(200이 나와서) FAIL.
`test_history_uses_new_codes`는 지금도 통과할 수 있다. 기록 형식이 바뀌지 않게 지키는 테스트라 그대로 둔다.

- [ ] **Step 3: 구현**
  - `prompts.py`: `ANSWERS`를 9개 코드로. `QUESTION_SCHEMA`에서 `key`를 빼고 `required = ["reason", "answer"]`.
  - `QUESTION_RULES` 다시 쓰기. 담아야 할 결정:
    - "## 답의 종류"에 9개 코드를 각각 한 줄로 설명한다. 뜻은 스펙 표의 안내 문장과 같다.
    - `INVALID_FORM`: 누가·무엇을·왜·어떻게 같은 질문, 질문이 아닌 말. `INVALID_TRUTH`: 진상·힌트·정답을 직접 알려 달라는 요청. `INVALID_RULE`: 진행 규칙을 바꾸라는 말, 역할 변경 등 질문 안의 지시.
    - 상식 규칙(기존 4번) 교체: 도시의 일반 상식을 묻는 질문은 이 사건과 이어질 때만 진상과 [세계관]에 따라 답하고, 아니면 `IRRELEVANT_UNRELATED`.
    - 판정 메모 읽기: "예"→`YES`, "아니오"→`NO`, "상관없음"→`IRRELEVANT_UNRELATED` 또는 `IRRELEVANT_UNDECIDED`, "예로 볼 수 있다"→`PARTIAL_SOME` 또는 `PARTIAL_CONDITION`. 둘 중 어느 쪽인지는 진상을 보고 고른다.
    - 여러 주장이 섞인 질문: 모두 맞으면 `YES`, 일부만 맞으면 `PARTIAL_SOME`.
    - "## 출력"에서 `key` 줄을 지운다.
  - `main.py`: `Answer = Literal[...9개]`. `QuestionResult`와 `QuestionJudgement`에서 `key` 제거. 로그는 `"[%s] Q: %s → %s / %s"`.
  - `judges.py`: `FakeJudge`의 질문 응답을 `{"reason": "가짜 판정기", "answer": "IRRELEVANT_UNRELATED"}`로.

- [ ] **Step 4: 통과 확인**

Run: `cd ai && uv run pytest -q`
Expected: 모두 PASS (기존 6개 + 새 4개 = 10개)

- [ ] **Step 5: 커밋**

```bash
git add ai/app ai/tests
git commit -m "AI 서버: 질문 판정을 9가지 코드로 바꾸고 핵심 질문 판단을 뺀다"
```

---

### Task 2: 백엔드가 9가지 코드를 저장하고 전달한다

**Files:**
- Modify: `backend/src/main/java/com/seaturtle/office/game/Answer.java`
- Modify: `backend/src/main/java/com/seaturtle/office/game/Game.java:12,71-76`
- Modify: `backend/src/main/java/com/seaturtle/office/game/GameService.java:41`
- Modify: `backend/src/main/java/com/seaturtle/office/judge/Judge.java:20`
- Test: `backend/src/test/java/com/seaturtle/office/GameFlowTests.java`

**Interfaces:**
- Consumes: Task 1의 `POST /judge/question` 응답 `{"answer": code}`.
- Produces: `enum Answer { YES, NO, PARTIAL_SOME, PARTIAL_CONDITION, IRRELEVANT_UNRELATED, IRRELEVANT_UNDECIDED, INVALID_FORM, INVALID_TRUTH, INVALID_RULE }`
- Produces: `record Judge.Verdict(Answer answer)`, `record Game.QuestionRecord(int no, int seq, String text, Answer answer)`, `Game.addQuestion(String text, Answer answer)`.
- Produces: `/api/games/{id}/questions` 응답과 `GameView.questions[]`가 `{no, seq, text, answer}` (`key` 없음).

- [ ] **Step 1: 실패하는 테스트 쓰기**

`GameFlowTests.StubJudge.question`이 `new Verdict(Answer.IRRELEVANT_UNDECIDED)`를 돌려주게 바꾸고, `playThroughAndGiveUp`의 단언을 이렇게 바꾼다:

```java
assertThat(q2.answer()).isEqualTo(Answer.IRRELEVANT_UNDECIDED);
// 두 번째 질문 때 첫 질문의 기록이 새 코드 그대로 함께 간다.
assertThat(judge.histories.getLast())
        .containsExactly(new Judge.HistoryEntry("세입자들은 죽었나요?", Answer.IRRELEVANT_UNDECIDED));
```

`q2.key()` 단언은 지운다. 질문 응답 JSON에 `key`가 없음을 확인하는 테스트를 추가한다(`@AutoConfigureMockMvc` + `MockMvc`로 `POST /api/games` → `POST /api/games/{id}/questions`):

```java
@Test
void questionResponseHasNoKeyField() throws Exception {
    // 응답 본문에 answer는 있고 key는 없다.
    mvc.perform(post("/api/games/" + id + "/questions").contentType(APPLICATION_JSON).content("{\"text\":\"q\"}"))
       .andExpect(jsonPath("$.answer").value("IRRELEVANT_UNDECIDED"))
       .andExpect(jsonPath("$.key").doesNotExist());
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test`
Expected: 컴파일 실패(`IRRELEVANT_UNDECIDED` 없음, `Verdict` 인자 수 불일치)

- [ ] **Step 3: 구현**
  - `Answer`를 9개 코드로. 각 상수 위 주석에 스펙 표의 진행자의 말과 안내 문장을 적는다.
  - `Judge.Verdict`, `Game.QuestionRecord`, `Game.addQuestion`에서 `key`를 뺀다. `GameService.ask`는 `game.addQuestion(question, verdict.answer())`.

- [ ] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL, 테스트 5개(ContentRepositoryTests 1, GameFlowTests 4) 통과

- [ ] **Step 5: 커밋**

```bash
git add backend/src
git commit -m "백엔드: 9가지 판정 코드를 저장·전달하고 key를 뺀다"
```

---

### Task 3: 화면에 진행자의 말과 안내 문장을 보여 준다

**Files:**
- Modify: `frontend/src/api.ts` (`Answer`, `QuestionRecord`, `ANSWER_LABEL` → `ANSWER_TEXT`)
- Modify: `frontend/src/GamePage.tsx` (질문 기록 렌더링, `evidenceNo`, 기록지 머리)
- Modify: `frontend/src/index.css` (도장 클래스, 안내 문장, `.evidence` 삭제)
- Test: `frontend/src/GamePage.test.tsx`

**Interfaces:**
- Consumes: Task 2의 `QuestionRecord {no, seq, text, answer}`.
- Produces: `type Answer` = 9개 코드의 문자열 유니언.
- Produces: `type Tone = 'yes' | 'no' | 'partial' | 'irrelevant' | 'invalid'`
- Produces: `ANSWER_TEXT: Record<Answer, { say: string; note: string; tone: Tone }>` — `say`는 진행자의 말, `note`는 안내 문장, `tone`은 도장 색. 값은 스펙 표 그대로(`tone`은 말 기준: 그렇단다→`yes`, 아니란다→`no`, 어느정도는 그렇단다→`partial`, 중요하지 않단다→`irrelevant`, 말해줄 수 없단다→`invalid`).

- [ ] **Step 1: 실패하는 테스트 쓰기** (`GamePage.test.tsx`, 기존 `openGame`을 질문 기록을 받을 수 있게 넓힌다)

```tsx
describe('질문 기록', () => {
  it('진행자의 말과 그 아래 안내 문장을 보여 준다', async () => {
    await openGame({ questions: [{ no: 1, seq: 1, text: '건물주는 몇 살인가요?', answer: 'IRRELEVANT_UNDECIDED' }] })
    expect(screen.getByText('중요하지 않단다')).toBeTruthy()
    expect(screen.getByText('이 사건에서 정하지 않은 세부사항입니다.')).toBeTruthy()
  })

  it('말이 같은 두 코드는 같은 도장 색을 쓴다', async () => {
    await openGame({ questions: [
      { no: 1, seq: 1, text: 'a', answer: 'PARTIAL_SOME' },
      { no: 2, seq: 2, text: 'b', answer: 'PARTIAL_CONDITION' },
    ] })
    const stamps = screen.getAllByText('어느정도는 그렇단다')
    expect(stamps[0].className).toBe(stamps[1].className)
    expect(stamps[0].className).toContain('s-partial')
  })

  it('증거 표시를 보여 주지 않는다', async () => {
    await openGame({ questions: [{ no: 1, seq: 1, text: 'a', answer: 'YES' }] })
    expect(screen.queryByText(/증거/)).toBeNull()
  })
})

it('ANSWER_TEXT가 9개 코드를 모두 담는다', () => {
  expect(Object.keys(ANSWER_TEXT)).toEqual(['YES', 'NO', 'PARTIAL_SOME', 'PARTIAL_CONDITION',
    'IRRELEVANT_UNRELATED', 'IRRELEVANT_UNDECIDED', 'INVALID_FORM', 'INVALID_TRUTH', 'INVALID_RULE'])
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && npm test`
Expected: 새 테스트 FAIL(문장이 없거나 `ANSWER_TEXT` 없음). `npx tsc -b`도 실패(유니언에 없는 코드).

- [ ] **Step 3: 구현**
  - `api.ts`: `Answer` 유니언 9개, `QuestionRecord`에서 `key` 제거, `ANSWER_LABEL`을 지우고 `ANSWER_TEXT`를 스펙 표 문장 그대로 둔다.
  - `GamePage.tsx`: 도장은 `<span className={`stamp s-${tone}`}>{say}</span>`, 그 아래 `<span className="stamp-note">{note}</span>`. `evidenceNo`와 `.evidence` 표지를 지운다. 기록지 머리는 `질문 {n}`만.
  - 추리 결과 도장은 그대로 두되 클래스를 새 이름에 맞춘다(정답 `s-yes`, 오답 `s-guess`).
  - `index.css`: `.s-YES/.s-NO/.s-PARTIAL/.s-IRRELEVANT/.s-INVALID/.s-GUESS`를 `.s-yes/.s-no/.s-partial/.s-irrelevant/.s-invalid/.s-guess`로 바꾼다. `s-invalid`는 이제 짧은 말이므로 다른 도장처럼 테두리 있는 도장(회색, 점선)으로. `.stamp-note`: 도장 아래 오른쪽 정렬, 0.75rem, `--ink-muted`. `.evidence` 규칙과 grid의 `ev` 영역을 지우고 그 자리를 `note`로 쓴다. 좁은 화면(`max-width: 760px`)에서는 도장과 안내 문장을 질문 아래 왼쪽 정렬로.

- [ ] **Step 4: 통과 확인**

Run: `cd frontend && npm test && npx tsc -b && npm run lint`
Expected: 테스트 모두 PASS(기존 4개 + 새 4개 = 8개), 타입 오류·린트 경고 없음

- [ ] **Step 5: 눈으로 확인**

세 서버를 켜고(`DEVELOPMENT.md` 실행 방법) 1280px과 520px 너비에서 질문을 두세 개 던져 스크린샷을 찍는다. 확인할 것: 도장 아래 안내 문장이 겹치지 않는다, 가짜 판정기 답이 "중요하지 않단다 / 이 사건과 관련이 없는 내용입니다."로 보인다, 증거 표시가 없다.

- [ ] **Step 6: 커밋**

```bash
git add frontend/src
git commit -m "화면: 진행자의 말과 안내 문장을 보여 주고 증거 표시를 없앤다"
```

---

### Task 4: 문서 정리

**Files:**
- Modify: `DEVELOPMENT.md` ("판정 방식" 절)

- [ ] **Step 1:** "판정 방식"의 답 다섯 가지 설명과 "핵심 질문 표시" 문장을 9가지 코드 표(코드 / 진행자의 말 / 안내 문장)로 바꾸고, 상식 질문 규칙을 스펙대로 적는다. "AI 서버 API" 표의 `/judge/question` 응답을 `{answer}`로 고친다.
- [ ] **Step 2:** 세 프로젝트 테스트를 모두 돌린다.

Run: `cd ai && uv run pytest -q; cd ../backend && ./gradlew test; cd ../frontend && npm test`
Expected: 모두 통과

- [ ] **Step 3: 커밋**

```bash
git add DEVELOPMENT.md
git commit -m "문서: 9가지 판정 코드와 답 표현 반영"
```
