#!/bin/bash
# 배포 뒤 확인. 사용법: deploy/smoke.sh <사이트 주소> <앞 도메인 주소> <통계 주소> [--insecure]
#   예) deploy/smoke.sh https://www.seaturtleoffice.site https://seaturtleoffice.site https://stats.seaturtleoffice.site
#   로컬 시험) deploy/smoke.sh https://www.localhost https://localhost https://stats.localhost --insecure
# 판정은 하지 않는다(OpenAI 비용 없음). 마지막 검사가 1분 동안 이 IP의 판정 요청을 막는다.
set -uo pipefail
SITE=${1:?사이트 주소}; APEX=${2:?앞 도메인 주소}; STATS=${3:?통계 주소}
CURL=(curl -s --max-time 15)
[[ "${4:-}" == "--insecure" ]] && CURL+=(-k)
failed=0
check() { # 이름 조건(0=통과)
	if [[ $2 == 0 ]]; then echo "통과  $1"; else echo "실패  $1"; failed=1; fi
}

headers=$("${CURL[@]}" -D - -o /dev/null "$SITE/")
grep -q '^HTTP/[0-9.]* 200' <<<"$headers"; check "사이트 200" $?
grep -qi '^strict-transport-security:' <<<"$headers"; check "HSTS 헤더" $?
! grep -qi '^server:' <<<"$headers"; check "Server 헤더 숨김" $?

headers=$("${CURL[@]}" -D - -o /dev/null "$APEX/some/path")
grep -q '^HTTP/[0-9.]* 301' <<<"$headers" && grep -qi "^location: $SITE/some/path" <<<"$headers"; check "앞 도메인 → www 301" $?

headers=$("${CURL[@]}" -D - -o /dev/null "$SITE/api/info")
grep -q '^HTTP/[0-9.]* 200' <<<"$headers" && grep -qi '^cache-control: no-store' <<<"$headers"; check "/api/info 200 + no-store" $?

cookie=$("${CURL[@]}" -D - -o /dev/null "$SITE/api/games" | grep -i '^set-cookie: sto_client=')
grep -qi 'secure' <<<"$cookie" && grep -qi 'httponly' <<<"$cookie"; check "기기 쿠키 Secure·HttpOnly" $?

code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$STATS/script.js")
[[ $code == 200 ]]; check "통계 스크립트 200" $?

# 없는 판으로 판정 경로를 61번: 앞 60번은 백엔드가 4xx로 거절(판정 안 함), 61번째는 Caddy가 429로 막는다.
url="$SITE/api/games/00000000-0000-0000-0000-000000000000/questions"
last=""
for _ in $(seq 1 61); do
	last=$("${CURL[@]}" -X POST -H 'Content-Type: application/json' -d '{"question":"점검"}' -w '\n%{http_code}' "$url")
done
code=${last##*$'\n'}; body=${last%$'\n'*}
[[ $code == 429 ]] && grep -q '"detail"' <<<"$body"; check "판정 경로 요청 제한 429 + detail" $?

exit $failed
