#!/bin/bash
# DB 백업: 게임 DB와 Umami DB를 날짜별 파일로 남기고 14일 지난 것은 지운다.
# 서버 cron이 매일 04:00에 돈다(bootstrap.sh가 등록). 복원은 DEVELOPMENT.md의 "백업과 복원".
set -euo pipefail
cd "$(dirname "$0")/.."
BACKUP_DIR=${BACKUP_DIR:-/opt/seaturtle-backups}
mkdir -p "$BACKUP_DIR"
compose() { docker compose -f compose.prod.yaml ${ENV_FILE:+--env-file "$ENV_FILE"} "$@"; }

day=$(date +%F)
for db in seaturtle umami; do
	out="$BACKUP_DIR/$day-$db.dump"
	# 다 받은 뒤에 이름을 바꿔서, 중간에 실패하면 반쪽짜리 파일이 남지 않게 한다.
	compose exec -T postgres pg_dump -U seaturtle -Fc "$db" > "$out.part"
	mv "$out.part" "$out"
	echo "$(date '+%F %T') 백업 $out ($(du -h "$out" | cut -f1))"
done
find "$BACKUP_DIR" -name '*.dump' -mtime +14 -delete
