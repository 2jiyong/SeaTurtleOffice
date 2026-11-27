#!/bin/bash
# 점검 안내 켜기·끄기. 서버에서: deploy/maintenance.sh on | off | status
# 켜면 사이트의 모든 요청(화면, /api)이 503 점검 안내를 받는다. Caddy를 다시 띄우지 않고 바로 바뀐다.
# 백엔드·AI 서버는 그대로 돈다. 아예 멈추려면 켠 뒤 docker compose -f compose.prod.yaml stop backend ai
# (deploy.sh로 다시 올려도 점검 안내는 off 할 때까지 그대로다.)
set -euo pipefail
cd "$(dirname "$0")/.."
flag=maintenance/on

case ${1:-status} in
	on) touch "$flag" && echo "점검 안내 켬" ;;
	off) rm -f "$flag" && echo "점검 안내 끔" ;;
	status) if [[ -e $flag ]]; then echo "점검 중"; else echo "운영 중"; fi ;;
	*) echo "사용법: $0 on | off | status" >&2; exit 1 ;;
esac
