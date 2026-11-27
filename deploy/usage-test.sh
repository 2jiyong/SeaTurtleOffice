#!/bin/bash
# deploy/usage.sql 시험: 일회용 postgres 컨테이너에 마이그레이션(V1~)과 정해 둔 이벤트를 넣고 숫자를 맞춰 본다.
# 사용법: deploy/usage-test.sh   (도커 필요. 끝나면 시험 컨테이너만 지운다)
set -uo pipefail
cd "$(dirname "$0")/.."
NAME=sto-usage-test
docker rm -f "$NAME" > /dev/null 2>&1
trap 'docker rm -f "$NAME" > /dev/null 2>&1' EXIT
docker run -d --name "$NAME" -e POSTGRES_USER=seaturtle -e POSTGRES_PASSWORD=t -e POSTGRES_DB=seaturtle postgres:17 > /dev/null
until docker exec "$NAME" pg_isready -h 127.0.0.1 -U seaturtle > /dev/null 2>&1; do sleep 1; done

psql() { docker exec -i "$NAME" psql -U seaturtle -d seaturtle -v ON_ERROR_STOP=1 -q "$@"; }
for f in backend/src/main/resources/db/migration/V*.sql; do psql < "$f" > /dev/null || { echo "마이그레이션 실패: $f"; exit 1; }; done
# 표 머리 없이 |로 나눈 줄만 받아 비교한다.
usage() { psql -A -t -F "|" -v days=7 -v tagged=1 < deploy/usage.sql; }

failed=0
expect() { # 이름 기대줄(정규식) 출력
	if grep -Eqx "$2" <<<"$3"; then echo "통과  $1"; else echo "실패  $1 (기대: $2)"; failed=1; fi
}

# 1) 빈 DB: 오류 없이 0
out=$(usage) || { echo "실패  빈 DB에서 실행 오류"; exit 1; }
expect "빈 DB 오늘 0" "오늘\|0\|0\|0\|0\|0\|0\|0\|0\.000\|2\.00" "$out"
expect "빈 DB 날짜별 7일" "$(printf '%s' "$(grep -c '^일|' <<<"$out")")" "7"

# 2) 정해 둔 이벤트. t0 = 오늘 한국 시간 자정.
psql > /dev/null <<'SQL'
create temp table k as select (date_trunc('day', now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul') t0;
insert into events (at, type, client_id, scenario, outcome, total_ms, llm_ms, model, input_tokens, cached_tokens, output_tokens, app_version, detail)
select t0 + o, type, c::uuid, s, outcome, ms, ms, 'm', i, ca, ou, 'v1', d::jsonb from k, (values
  -- 기기 A: 새 판, 질문 2(0.405달러씩), 추리 1(토큰 없음), 해결(사용 3)
  (interval '1 hour', 'game_started',   'aaaaaaaa-0000-0000-0000-000000000000', 's1', 'ok',          null, null, null, null, null),
  (interval '1 hour', 'question_asked', 'aaaaaaaa-0000-0000-0000-000000000000', 's1', 'ok',          1000, 1000000, 200000, 100000, null),
  (interval '1 hour', 'question_asked', 'aaaaaaaa-0000-0000-0000-000000000000', 's1', 'ok',          3000, 1000000, 200000, 100000, null),
  (interval '1 hour', 'guess_submitted','aaaaaaaa-0000-0000-0000-000000000000', 's1', 'ok',          2000, null, null, null, null),
  (interval '1 hour', 'game_ended',     'aaaaaaaa-0000-0000-0000-000000000000', 's1', 'ok',          null, null, null, null, '{"status":"SOLVED","used":3}'),
  -- 기기 B: 새 판, 판정 오류 1(0.25달러), 한도초과 1(토큰 없음), 힌트, 포기
  (interval '2 hour', 'game_started',   'bbbbbbbb-0000-0000-0000-000000000000', 's1', 'ok',          null, null, null, null, null),
  (interval '2 hour', 'question_asked', 'bbbbbbbb-0000-0000-0000-000000000000', 's1', 'judge_error', 500,  1000000, 0, 0, null),
  (interval '2 hour', 'question_asked', 'bbbbbbbb-0000-0000-0000-000000000000', 's1', 'limited',     null, null, null, null, null),
  (interval '2 hour', 'hint_used',      'bbbbbbbb-0000-0000-0000-000000000000', 's1', 'ok',          null, null, null, null, null),
  (interval '2 hour', 'game_ended',     'bbbbbbbb-0000-0000-0000-000000000000', 's1', 'ok',          null, null, null, null, '{"status":"GAVE_UP","used":5}'),
  -- 기기 C: 어제 23:59 한국 시간(오늘로 세면 안 된다), 질문 1(0.25달러)
  (interval '-1 minute', 'question_asked', 'cccccccc-0000-0000-0000-000000000000', 's2', 'ok',      9000, 1000000, 0, 0, null)
) v(o, type, c, s, outcome, ms, i, ca, ou, d);
SQL
out=$(usage) || { echo "실패  실행 오류"; exit 1; }
today=$(TZ=Asia/Seoul date +%m-%d)
yesterday=$(TZ=Asia/Seoul date -v-1d +%m-%d 2> /dev/null || TZ=Asia/Seoul date -d yesterday +%m-%d)
expect "오늘 요약" "오늘\|2\|2\|2\|1\|1\|1\|1\|1\.060\|2\.00" "$out"
expect "날짜별 오늘" "일\|$today\|2\|4\|1\.060\|2000\|2900\|1\|1" "$out"
expect "날짜별 어제(자정 경계)" "일\|$yesterday\|1\|1\|0\.250\|9000\|9000\|0\|0" "$out"
expect "버전별" "버전\|v1\|m\|4\|2500\|[0-9]+\|2500\|[0-9]+\|[0-9]+" "$out"
expect "사건별" "사건\|s1\|2\|1\|50%\|3" "$out"

[[ $failed == 0 ]] || { echo "--- 실제 출력"; echo "$out"; }
exit $failed
