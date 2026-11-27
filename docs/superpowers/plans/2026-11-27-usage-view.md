# 5단계 사용량 보기 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** `deploy/usage.sh [일수]`로 서버 DB의 사용량 표 네 개(오늘·날짜별·버전별·사건별)를 본다.
**Architecture:** SQL 파일 하나를 SSH로 서버 postgres 컨테이너의 psql에 흘려 보낸다. 테스트는 일회용 postgres 컨테이너에서.
**Tech Stack:** bash, psql(postgres 17), docker.
**Spec:** `docs/superpowers/specs/2026-11-27-usage-view-design.md`

## Global Constraints
- 한국어 출력·문서·커밋. 읽기 전용 쿼리만. 단가 0.25 / 0.025 / 2.0, 예산 2.00. 날짜 경계 Asia/Seoul 자정.
- 테스트 컨테이너 이름 `sto-usage-test`, 끝나면 그 컨테이너만 지운다. 개발 DB 컨테이너는 건드리지 않는다.

## Review Focus
1. 한국 시간 자정 직전(전날 23:59 KST) 이벤트가 오늘로 세지지 않는가 — 테스트 픽스처.
2. 토큰이 null인 이벤트(limited, hint)가 비용 합을 null로 만들지 않는가 — 테스트.
3. 이벤트가 하나도 없는 날·기간에도 오류 없이 0이 나오는가 — 테스트(빈 DB 실행).
4. `days`에 숫자가 아닌 값이 들어오면 SQL에 그대로 들어가지 않는가 — usage.sh 검사.

### Task 1: 사용량 쿼리와 명령
**Files:** Create `deploy/usage-test.sh`, `deploy/usage.sql`, `deploy/usage.sh`; Modify `DEVELOPMENT.md`
- [ ] 1: `usage-test.sh` 작성 — postgres:17 컨테이너 → V1~V3 적용 → 빈 DB에서 usage.sql 실행 성공(오늘 행 `0|0|0|0|0|0|0|0.00|2.00`) → 픽스처 넣고 기대 행 검사:
  - 오늘 `2|2|2|1|1|1|1|1.06|2.00`
  - 날짜별 오늘 `…|2|4|1.06|2000|2900|1|1`, 어제 `…|1|1|0.25|9000|9000|0|0`
  - 버전별 `v1|m|4|2500|…`
  - 사건별 `s1|2|1|50%|3`
- [ ] 2: 실행해 실패 확인(usage.sql 없음).
- [ ] 3: `usage.sql` 작성(psql 변수 `days`), `usage.sh` 작성(`days`는 1~366 정수만).
- [ ] 4: 테스트 통과. 운영 서버에 `deploy/usage.sh` 실제 실행.
- [ ] 5: 문서, 커밋 `사용량: 터미널에서 보는 사용량 표(deploy/usage.sh)`.
