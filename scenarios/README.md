# 시나리오

바다거북스프 시나리오를 둔다. 시나리오 1개 = 파일 1개(`<slug>.md`).

## frontmatter
- `id`: `scenario.<slug>`
- `title`: 시나리오 제목
- `difficulty`: easy / normal / hard
- `era`: 배경 시대 ID (`world/base/08-eras.md`)
- `works`: lobotomy / ruina / limbus / other
- `uses`: 근거가 된 상세 항목 ID 목록 (`world/detail`). 판정 때 함께 불러온다.
- `updated`: YYYY-MM-DD

## 본문
1. **문제**: 플레이어에게 보여 주는 짧은 이야기. 진상은 숨긴다.
2. **진상**: 전말. AI가 예/아니오를 판정하는 기준이다. 플레이어에게 보여 주지 않는다.
3. **핵심 포인트**: 정답으로 인정할 최소 조건.
4. **판정 메모**: 헷갈리기 쉬운 질문과 답(예 / 아니오 / 상관없음).
5. **힌트**: 약한 것부터 3단계.

진상은 `world/`에 정리된 사실만으로 만든다. 새 설정을 지어내지 않는다.

## 예비 시나리오
탈락하거나 보류한 시나리오는 `reserve/`에 둔다. 자세한 규칙은 `reserve/README.md`를 본다.
