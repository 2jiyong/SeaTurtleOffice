#!/bin/bash
# (내 컴퓨터에서) 운영 사용량 보기: deploy/usage.sh [일수]   기본 7일, 1~366
# 서버 DB에 읽기 쿼리(deploy/usage.sql)만 보낸다. 방문자 수·페이지 그래프는 Umami(stats.)에서.
set -euo pipefail
cd "$(dirname "$0")/.."
DAYS=${1:-7}
[[ $DAYS =~ ^[0-9]+$ ]] && (( DAYS >= 1 && DAYS <= 366 )) || { echo "일수는 1~366 사이 숫자로 주세요: $DAYS"; exit 1; }
HOST=${USAGE_HOST:-ubuntu@www.seaturtleoffice.site}
KEY=${USAGE_KEY:-$HOME/.ssh/seaturtle-office.pem}

ssh -i "$KEY" -o BatchMode=yes -o StrictHostKeyChecking=accept-new "$HOST" \
	"cd /opt/seaturtle && docker compose -f compose.prod.yaml exec -T postgres psql -U seaturtle -d seaturtle -q -P footer=off -v days=$DAYS" \
	< deploy/usage.sql
