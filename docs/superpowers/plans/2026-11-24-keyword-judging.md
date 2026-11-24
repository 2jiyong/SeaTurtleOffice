# 키워드 기반 정답 판정과 보너스 키워드 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 시나리오 정답 기준을 "핵심 키워드"로 바꾸고(필수 용어, 인정·불인정, 빠졌을 때 문장), 보너스 키워드를 더해, 추리 결과에는 빠진 키워드의 문장을, 진상 공개에는 맞힌 보너스를 보여 준다.

**Architecture:** 파이썬 AI 서버와 자바 백엔드가 같은 규칙으로 시나리오의 새 절을 읽는다. AI는 핵심·보너스 키워드 번호만 판정하고, 빠졌을 때 문장은 백엔드가 시나리오에서 꺼내 화면에 보낸다. 바꾸는 동안 옛 `## 핵심 포인트` 형식도 읽고, 마지막 작업에서 지운다.

**Tech Stack:** FastAPI + pydantic + pytest (`ai/`), Spring Boot 4 + JUnit + MockMvc (`backend/`), React + Vitest + Testing Library (`frontend/`)

**Spec:** `docs/superpowers/specs/2026-11-24-keyword-judging-design.md`

## Global Constraints
- 시나리오 절 이름은 정확히 `## 핵심 키워드`, `## 보너스 키워드`. 핵심 키워드 하위 줄 이름은 정확히 `필수 용어`, `인정`, `불인정`, `빠졌을 때`.
- 파싱 규칙(파이썬·자바 공통):
  - 핵심 키워드 시작 줄: `^\d+\.\s+\*\*(.+?)\*\*:\s*(.+)$` → 이름, 설명.
  - 그 아래 들여쓴 줄: `^\s+-\s+(필수 용어|인정|불인정|빠졌을 때):\s*(.*)$`. `필수 용어`·`인정`·`불인정`은 쉼표로 나눠 앞뒤 공백을 지운 목록, `빠졌을 때`는 문장 하나.
  - 보너스 줄: `^-\s+\*\*(.+?)\*\*:\s*(.+)$` → 이름, 설명.
  - 옛 형식 호환(Task 6에서 삭제): `## 핵심 키워드`가 없고 `## 핵심 포인트`가 있으면 목록 항목마다 이름=설명=그 문장, 나머지 목록은 빈 목록, 빠졌을 때 = `아직 빠진 것이 있구나.`, 보너스 0개.
- 빠졌을 때 문장은 AI 프롬프트에 넣지 않는다(판정에 필요 없고, AI가 문장을 쓰지 않는다).
- 정답 = 핵심 키워드 번호를 모두 충족. 보너스는 정답과 무관.
- AI 서버 `/judge/guess` 응답: `{matched: int[], total: int, bonus: int[], bonusTotal: int}`. 번호는 1부터, 중복·범위 밖은 서버가 버린다.
- 추리 결과에 보너스를 표시하지 않는다. 맞힌 보너스는 게임이 끝나기 전에는 화면에 보내지 않는다.
- 사용자에게 보이는 문장과 코드 주석은 한국어.

## Review Focus
- AI가 범위 밖 번호(0, 99)나 중복 번호를 돌려준다 → 버리고 센다. (Task 2 테스트)
- 핵심 키워드 하위 줄 순서가 섞이거나 `필수 용어` 줄이 없다 → 그래도 읽힌다, 필수 용어는 빈 목록. (Task 1 테스트)
- 게임 중(PLAYING) API 응답에 맞힌 보너스가 새어 나간다 → `reveal`이 null이고 추리 기록에도 보너스 정보가 없다. (Task 3 테스트)
- 여러 번 추리해서 보너스를 나눠 맞힌다 → 진상 공개 때 합쳐서 맞힘 표시. (Task 3 테스트)
- 빠졌을 때 문장이 문제에 없는 말이나 키워드 이름을 담는다 → 사람이 읽고 확인한다(Task 6의 사용자 승인 단계, 그리고 시나리오 테스트가 문장에 키워드 이름·필수 용어가 들어 있지 않은지 기계적으로 확인).

---

### Task 1: 파이썬이 새 시나리오 절을 읽고 판정 자료에 넣는다 (「마지막 월세」 시범 전환)

**Files:**
- Modify: `scenarios/last-rent.md` (핵심 포인트 → 핵심 키워드·보너스 키워드, 진상·판정 메모에 월세 체납 곁가지)
- Modify: `ai/app/content.py` (`Scenario.key_points` → `keywords`, `bonuses`, 파서)
- Modify: `ai/app/prompts.py` (`_materials`)
- Test: `ai/tests/test_judge.py`

**Interfaces:**
- Produces: `@dataclass(frozen=True) class Keyword: name: str; description: str; required: list[str]; accept: list[str]; reject: list[str]; missing: str`
- Produces: `@dataclass(frozen=True) class Bonus: name: str; description: str`
- Produces: `Scenario.keywords: list[Keyword]`, `Scenario.bonuses: list[Bonus]` (`key_points` 삭제)
- Produces: `parse_keywords(section: str | None) -> list[Keyword]`, `parse_bonuses(section: str | None) -> list[Bonus]` (모듈 함수, `content.py`)

- [ ] **Step 1: 「마지막 월세」 바꾸기.** 스펙의 "시나리오 형식" 예시(빠졌을 때 문장 포함)를 그대로 `## 핵심 포인트` 자리에 넣는다. 진상 둘째 문단 앞에 "세입자들은 1년 넘게 월세를 밀리고 있었고, 건물주는 그 일로 오래 앙심을 품었다."를 넣고, 판정 메모에 `- 세입자들이 월세를 밀렸나요? → 예.`와 `- 건물주는 세입자들에게 원한이 있었나요? → 예.`를 더한다. 기존 판정 메모의 `- 건물주는 세입자들을 미워했나요? → 상관없음.`은 새 곁가지와 모순되므로 `→ 예. 월세를 오래 밀렸다.`로 고친다.

- [ ] **Step 2: 실패하는 테스트 쓰기**

```python
def test_parses_keywords_and_bonuses(content):
    s = content.scenario("last-rent")
    assert [k.name for k in s.keywords] == ["거주구역 등록 만료", "청소부", "건물주가 노림"]
    assert s.keywords[0].required == ["거주구역"]
    assert s.keywords[2].required == []
    assert "밤의 괴물" in s.keywords[1].reject
    assert s.keywords[1].missing == "건물이 어떻게 하룻밤 사이에 비었는지가 빠져 있구나."
    assert [b.name for b in s.bonuses] == ["월세 체납", "증거가 남지 않음"]

def test_old_format_still_reads(content):
    s = content.scenario("boys-order")  # Task 6 전까지 옛 형식
    assert len(s.keywords) == 3 and s.bonuses == []
    assert s.keywords[0].missing == "아직 빠진 것이 있구나."

def test_keyword_lines_in_any_order():
    ks = parse_keywords("1. **가**: 설명\n   - 빠졌을 때: 문장.\n   - 인정: a, b\n")
    assert ks[0].accept == ["a", "b"] and ks[0].required == [] and ks[0].missing == "문장."

def test_materials_carry_keywords_not_missing_lines(content):
    m = JudgePrompts(content, 40).materials("last-rent")
    assert "## [핵심 키워드]" in m and "## [보너스 키워드]" in m
    assert "필수 용어: 청소부" in m and "불인정:" in m
    assert "빠졌을 때" not in m and "## [핵심 포인트]" not in m
```

`test_every_scenario_is_complete`의 `s.key_points` 단언을 `s.keywords`로 바꾼다.

- [ ] **Step 3: 실패 확인** — Run: `cd ai && uv run pytest -q` / Expected: 새 테스트 FAIL (`Scenario`에 `keywords` 없음)
- [ ] **Step 4: 구현.** Global Constraints의 파싱 규칙대로 `parse_keywords`, `parse_bonuses`를 쓰고 `_load_scenarios`가 쓰게 한다(옛 형식 호환 포함). `_materials`는 `## [핵심 포인트]` 대신 아래 모양으로 쓴다(빠졌을 때 줄 제외, 빈 목록 줄 생략):
  ```
  ## [핵심 키워드]
  1. 거주구역 등록 만료: 건물의 거주구역 ...
     - 필수 용어: 거주구역
     - 인정: ...
     - 불인정: ...
  ## [보너스 키워드]
  1. 월세 체납: ...
  ```
- [ ] **Step 5: 통과 확인** — Run: `cd ai && uv run pytest -q` / Expected: 모두 PASS
- [ ] **Step 6: 커밋** — `git add scenarios/last-rent.md ai && git commit -m "AI 서버: 시나리오의 핵심 키워드·보너스 키워드를 읽어 판정 자료에 넣는다"`

---

### Task 2: 파이썬 추리 판정이 키워드 번호를 돌려준다

**Files:**
- Modify: `ai/app/prompts.py` (`GUESS_RULES`, `GUESS_SCHEMA`)
- Modify: `ai/app/main.py` (`GuessResult`, `GuessJudgement`, `judge_guess`)
- Modify: `ai/app/judges.py` (`FakeJudge` 추리 응답)
- Test: `ai/tests/test_judge.py`

**Interfaces:**
- Consumes: Task 1의 `Scenario.keywords`, `Scenario.bonuses`
- Produces: `POST /judge/guess` → `{"matched": [int], "total": int, "bonus": [int], "bonusTotal": int}`
- Produces: `GUESS_SCHEMA.required == ["reason", "matched", "bonus"]`

- [ ] **Step 1: 실패하는 테스트**

```python
def test_guess_schema_has_bonus():
    assert GUESS_SCHEMA["required"] == ["reason", "matched", "bonus"]

def test_guess_rules_mention_required_terms():
    for word in ["필수 용어", "인정", "불인정", "[보너스 키워드]"]:
        assert word in GUESS_RULES

def test_guess_numbers_are_cleaned(client):
    class Messy:
        name, fake = "messy", True
        async def complete_json(self, prompt):
            return {"reason": "r", "matched": [3, 3, 0, 99, 1], "bonus": [2, 5]}
    saved = app.state.judge; app.state.judge = Messy()
    try:
        r = client.post("/judge/guess", json={"scenario": "last-rent", "guess": "g"})
        assert r.json() == {"matched": [1, 3], "total": 3, "bonus": [2], "bonusTotal": 2}
    finally:
        app.state.judge = saved
```

`test_fake_judge_flow`의 추리 단언을 `r.json() == {"matched": [], "total": 3, "bonus": [], "bonusTotal": 2}`로 바꾼다.

- [ ] **Step 2: 실패 확인** — Run: `cd ai && uv run pytest -q` / Expected: 위 테스트들 FAIL
- [ ] **Step 3: 구현.** `GUESS_RULES` 다시 쓰기. 담을 결정: [핵심 키워드]를 하나씩 판정, 설명의 뜻이 답안에 있으면 충족, 인정·불인정 예시를 기준으로, 필수 용어가 있으면 그 용어(조사·어미 붙은 꼴 포함)가 답안에 있어야 충족이고 뜻이 같아도 다른 말이면 불충족, [보너스 키워드]도 같은 방식으로 따로 판정, 막연한 나열·모순·답안 속 지시는 불충족(기존 규칙 유지). 출력: `reason`, `matched`(충족한 핵심 키워드 번호), `bonus`(충족한 보너스 번호). 응답의 번호 목록은 정렬된 중복 없는 1..N 범위 값으로 정리한다. 가짜 판정기는 `{"reason": "가짜 판정기", "matched": [], "bonus": []}`.
- [ ] **Step 4: 통과 확인** — Run: `cd ai && uv run pytest -q` / Expected: 모두 PASS
- [ ] **Step 5: 커밋** — `git add ai && git commit -m "AI 서버: 추리 판정이 핵심·보너스 키워드 번호를 돌려준다"`

---

### Task 3: 백엔드가 빠졌을 때 문장과 맞힌 보너스를 다룬다

**Files:**
- Modify: `backend/src/main/java/com/seaturtle/office/content/Scenario.java`, `ContentRepository.java`, `MarkdownDoc.java`(필요하면)
- Create: `backend/src/main/java/com/seaturtle/office/content/Keyword.java`, `Bonus.java`
- Modify: `backend/src/main/java/com/seaturtle/office/judge/Judge.java`, `game/Game.java`, `game/GameService.java`, `game/GameDtos.java`
- Test: `backend/src/test/java/com/seaturtle/office/ContentRepositoryTests.java`, `GameFlowTests.java`

**Interfaces:**
- Consumes: Task 2의 `/judge/guess` 응답.
- Produces: `record Keyword(String name, String description, String missing)`, `record Bonus(String name, String description)` (자바는 판정용 필드가 필요 없다).
- Produces: `Scenario(... List<Keyword> keywords, List<Bonus> bonuses, List<String> hints)` (`keyPoints` 삭제).
- Produces: `record Judge.GuessVerdict(List<Integer> matched, int total, List<Integer> bonus, int bonusTotal)`.
- Produces: `record Game.GuessRecord(int seq, String text, int matched, int total, boolean correct, List<String> missing)`; `Game.addGuess(String text, List<Integer> matched, List<Integer> bonus)`; 게임은 맞힌 보너스 번호를 모은다.
- Produces: `GameDtos.Reveal(String truth, List<KeywordView> keywords, List<BonusView> bonuses)`, `KeywordView(String name, String description)`, `BonusView(String name, String description, boolean found)`.

- [ ] **Step 1: 실패하는 테스트**
  - `ContentRepositoryTests`: 「마지막 월세」의 `keywords` 이름 3개와 둘째 키워드 `missing`, `bonuses` 이름 2개. 옛 형식 「꼬마의 부탁」은 키워드 3개·보너스 0개. 기존 `keyPoints` 단언은 `keywords`로.
  - `GameFlowTests.StubJudge.guess`: `"개수 다름"` → `new GuessVerdict(List.of(1), 5, List.of(), 2)`; `"정답"` → `(List.of(1,2,3), 3, List.of(1), 2)`; 그 밖 → `(List.of(1,3), 3, List.of(2), 2)`.
  - 새 테스트 `wrongGuessShowsMissingSentences`: `"모르겠어요"` 추리 뒤 `game.guesses().getFirst().missing()`이 `["건물이 어떻게 하룻밤 사이에 비었는지가 빠져 있구나."]`.
  - 새 테스트 `bonusHiddenUntilEndThenMerged` (MockMvc): 오답 추리(보너스 2) → `GET /api/games/{id}`에서 `$.reveal`이 null이고 `$.guesses[0].bonus`가 없다. 이어서 `"정답"` 추리(보너스 1) → `$.reveal.bonuses[0].found == true`, `$.reveal.bonuses[1].found == true`, `$.reveal.keywords[0].name == "거주구역 등록 만료"`.
  - 기존 `guessIsRejectedWhenAiServerKeyPointCountDiffers`는 그대로 통과해야 한다. 보너스 개수가 다를 때도 같은 오류인지 확인하는 단언을 하나 더한다(`"보너스 다름"` → `(List.of(), 3, List.of(), 9)`).
- [ ] **Step 2: 실패 확인** — Run: `cd backend && ./gradlew test` / Expected: 컴파일 실패(`keywords`, 새 `GuessVerdict` 없음)
- [ ] **Step 3: 구현.** 파싱 규칙은 Global Constraints와 같다(자바는 이름·설명·빠졌을 때만 읽는다, 옛 형식 호환 포함). `GameService.guess`: 핵심·보너스 개수를 각각 시나리오와 대조하고 다르면 `JudgeException`. `Game.addGuess`가 범위 안 번호만 쓰고, 빠진 키워드의 `missing`을 키워드 순서대로 담고, 보너스 번호를 모은다. `GameView.of`가 끝난 게임에서만 `Reveal`을 만든다.
- [ ] **Step 4: 통과 확인** — Run: `cd backend && ./gradlew test` / Expected: BUILD SUCCESSFUL
- [ ] **Step 5: 커밋** — `git add backend && git commit -m "백엔드: 빠졌을 때 문장을 보내고 맞힌 보너스는 끝날 때만 공개한다"`

---

### Task 4: 화면이 빠졌을 때 문장과 보너스 공개를 보여 준다

**Files:**
- Modify: `frontend/src/api.ts` (`GuessRecord.missing`, `GameView.reveal`)
- Modify: `frontend/src/GamePage.tsx` (추리 기록, 진상 공개)
- Modify: `frontend/src/index.css` (필요한 만큼)
- Test: `frontend/src/GamePage.test.tsx`

**Interfaces:**
- Consumes: Task 3의 `GuessRecord {seq, text, matched, total, correct, missing}`, `reveal {truth, keywords:[{name, description}], bonuses:[{name, description, found}]}`.

- [ ] **Step 1: 실패하는 테스트**

```tsx
it('오답 추리 아래에 빠졌을 때 문장을 보여 주고 보너스는 보여 주지 않는다', async () => {
  await openGame({ guesses: [{ seq: 1, text: 't', matched: 2, total: 3, correct: false,
    missing: ['건물주가 무엇을 바랐는지가 빠져 있구나.'] }] })
  expect(screen.getByText('핵심 2/3')).toBeTruthy()
  expect(screen.getByText('건물주가 무엇을 바랐는지가 빠져 있구나.')).toBeTruthy()
  expect(screen.queryByText(/보너스/)).toBeNull()
})

it('진상 공개에서 핵심 키워드와 맞힌 보너스를 보여 준다', async () => {
  vi.mocked(api.game).mockResolvedValue({ ...game, status: 'SOLVED', reveal: { truth: '진상',
    keywords: [{ name: '청소부', description: '청소부가 쓸어 갔다.' }],
    bonuses: [{ name: '월세 체납', description: 'd1', found: true }, { name: '증거가 남지 않음', description: 'd2', found: false }] } })
  render(<GamePage gameId="g1" onExit={() => {}} />)
  expect(await screen.findByText('청소부')).toBeTruthy()
  expect(screen.getByText('보너스 1/2')).toBeTruthy()
  expect(screen.getByText('맞힘')).toBeTruthy()
})
```

- [ ] **Step 2: 실패 확인** — Run: `cd frontend && npm test` / Expected: 새 테스트 FAIL, `npx tsc -b` 실패
- [ ] **Step 3: 구현.** 오답 추리 도장 아래에 `missing` 문장을 `stamp-note` 모양으로 한 줄씩. 진상 공개의 "핵심 포인트" 목록을 "핵심 키워드"(이름 굵게 + 설명)로 바꾸고, 보너스가 있으면 "보너스 키워드" 절 제목 옆에 `보너스 n/m`, 맞힌 항목에 `맞힘` 표시. 보너스가 없는 시나리오는 절을 숨긴다.
- [ ] **Step 4: 통과 확인** — Run: `cd frontend && npm test && npx tsc -b && npm run lint` / Expected: 모두 통과
- [ ] **Step 5: 커밋** — `git add frontend/src && git commit -m "화면: 빠졌을 때 문장과 진상 공개의 보너스 표시"`

---

### Task 5: 규격과 스킬 문서를 새 형식으로

**Files:**
- Modify: `scenarios/README.md`, `.claude/skills/scenario-make/SKILL.md`, `DEVELOPMENT.md`

- [ ] **Step 1:** `scenarios/README.md` 본문 3번을 "핵심 키워드"(하위 줄 4종, 빠졌을 때 규칙: 문제에 나온 것만, 키워드 이름·세계관 용어·진상에만 있는 사실 금지, 진행자 말투)와 "보너스 키워드"(1~2개, 곁가지 두 종류, 새 곁가지는 진상·판정 메모에도)로 바꾼다. 스펙의 「마지막 월세」 예시를 형식 예로 붙인다.
- [ ] **Step 2:** `scenario-make` 스킬: "핵심 포인트"를 "핵심 키워드"로 모두 바꾸고, 보너스 곁가지 설계 단계와 "곁가지를 빼도 정답은 그대로인가" 검사, "빠졌을 때 문장이 힌트가 되지 않는가" 검사를 더한다. 4번 "비팬 테스트"를 "팬 테스트: 필수 용어는 세계관을 아는 팬이 질문 과정에서 떠올릴 수 있는 용어인가"로 바꾼다.
- [ ] **Step 3:** `DEVELOPMENT.md` "판정 방식"의 정답 제출 설명과 AI 서버 API 표의 `/judge/guess` 응답을 고친다.
- [ ] **Step 4: 커밋** — `git add scenarios/README.md .claude/skills/scenario-make/SKILL.md DEVELOPMENT.md && git commit -m "문서: 시나리오 핵심 키워드·보너스 키워드 규격"`

---

### Task 6: 나머지 12개 시나리오 전환 (사용자 승인 필요)

**Files:**
- Modify: `scenarios/*.md` 중 `last-rent.md`를 뺀 12개
- Modify: `ai/app/content.py`, `backend/.../ContentRepository.java` (옛 형식 호환 삭제)
- Test: `ai/tests/test_judge.py`, `backend/.../ContentRepositoryTests.java`

- [ ] **Step 1: 초안 만들기.** 12개 각각의 핵심 키워드(필수 용어, 인정, 불인정, 빠졌을 때)와 보너스 1~2개(새 곁가지는 진상·판정 메모에 넣을 문장까지)를 Task 5의 규격대로 초안으로 쓴다. 곁가지의 세계관 근거는 `uses`에 있는 문서 안에서만 찾는다.
- [ ] **Step 2: 사용자 승인 (멈춤).** 초안 12개를 한 번에 보여 주고 승인받는다. 고칠 곳은 고친 뒤 다시 보여 준다. 승인 전에는 파일을 고치지 않는다.
- [ ] **Step 3: 실패하는 테스트**

```python
def test_every_scenario_uses_new_format(content):
    for s in content.scenarios.values():
        assert s.keywords and 1 <= len(s.bonuses) <= 2, s.slug
        for k in s.keywords:
            assert k.missing and k.missing != "아직 빠진 것이 있구나.", (s.slug, k.name)
            assert k.name not in k.missing, (s.slug, k.name)
            for term in k.required:
                assert term not in k.missing, (s.slug, term)
```
자바 `ContentRepositoryTests.everyScenarioIsComplete`에 "보너스 1~2개" 단언을 더한다.
- [ ] **Step 4: 실패 확인** — Run: `cd ai && uv run pytest -q` / Expected: 옛 형식 시나리오 때문에 FAIL
- [ ] **Step 5: 승인된 초안을 12개 파일에 반영하고, 파이썬·자바의 옛 형식 호환 코드와 `test_old_format_still_reads`, 자바의 옛 형식 단언을 지운다.**
- [ ] **Step 6: 통과 확인** — Run: `cd ai && uv run pytest -q && cd ../backend && ./gradlew test && cd ../frontend && npm test` / Expected: 모두 통과
- [ ] **Step 7: 커밋** — `git add scenarios ai backend && git commit -m "시나리오 12개를 핵심 키워드·보너스 키워드 형식으로 전환"`
