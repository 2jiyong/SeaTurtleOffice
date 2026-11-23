# 개발 가이드

## 구조
- `backend/` — Spring Boot 4.2 (Java 25, Gradle). 시작할 때 `world/`, `scenarios/`의 마크다운을 읽는다.
- `frontend/` — React + Vite + TypeScript. `/api` 요청은 개발 서버가 백엔드(8787)로 넘긴다.
- 게임 진행 상태는 서버 메모리에만 있다(DB 없음). 서버를 다시 켜면 진행 중인 게임은 사라진다.

## 실행
백엔드(포트 8787):

```bash
cd backend && OPENAI_API_KEY=sk-... ./gradlew bootRun
```

키가 없으면 가짜 판정기로 켜진다(모든 질문에 "상관없음"). 화면 상단에 안내가 뜬다.

프런트엔드(포트 5173):

```bash
cd frontend && npm install && npm run dev
```

## 판정기(LLM) 설정
| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `LLM_PROVIDER` | `auto` | `auto`(키가 있으면 openai), `openai`, `fake` |
| `OPENAI_API_KEY` | | |
| `OPENAI_MODEL` | `gpt-5-mini` | |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | OpenAI 호환 서버도 쓸 수 있다 |
| `OPENAI_REASONING_EFFORT` | `low` | 추론 모델이 아니면 빈 값으로 둔다 |

다른 공급자를 붙이려면 `llm/LlmClient`를 구현하고 `LlmConfig`에 등록한다.

## 판정 방식
- 질문: 베이스 세계관 전체 + 시나리오 `uses`의 상세 항목 + 시나리오(문제·진상·핵심 포인트·판정 메모)를 프롬프트에 넣는다.
  답은 `YES`(예), `NO`(아니오), `PARTIAL`(부분적으로 예), `IRRELEVANT`(상관없음), `INVALID`(예/아니오로 답할 수 없음) 중 하나이고,
  핵심 포인트에 닿는 질문이면 "핵심 질문" 표시를 붙인다.
- 정답 제출: 답안 하나가 핵심 포인트를 모두 충족하면 정답이다. 플레이어에게는 몇 개를 맞혔는지만 보여 준다.
- 판정은 JSON 스키마로 받으므로 자유 문장이 플레이어에게 새지 않는다. 판정 근거(`reason`)는 서버 로그에만 남는다.

## 테스트
```bash
cd backend && ./gradlew test
```
모든 시나리오가 읽히는지, `uses`의 ID가 모두 `world/`에 있는지도 검사한다.
