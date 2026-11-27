#!/bin/bash
# 서버에서 새 버전을 올린다: deploy/deploy.sh
# main을 받아 이미지를 서버에서 빌드하고 다시 띄운다. 백엔드가 다시 뜨는 10~30초 동안 끊긴다.
set -euo pipefail
cd "$(dirname "$0")/.."
compose() { docker compose -f compose.prod.yaml ${ENV_FILE:+--env-file "$ENV_FILE"} "$@"; }

if [[ -z "${SKIP_PULL:-}" ]]; then git pull --ff-only; fi
APP_VERSION=$(git rev-parse --short HEAD)
export APP_VERSION
echo "배포 버전: $APP_VERSION"

# 2GB 서버라 한 번에 하나씩 빌드한다(동시에 하면 메모리가 모자라 스왑으로 느려진다).
for service in backend ai caddy; do compose build "$service"; done
# 두 서버는 world/·scenarios/를 시작할 때만 읽는다. 내용만 바뀌면 이미지가 그대로라 다시 뜨지 않으므로 늘 다시 띄운다.
# 빌드를 먼저 끝냈으니 여기서 한 번만 다시 뜬다.
compose up -d --force-recreate --no-build backend ai
compose up -d --no-build
# 옛 이미지와 1주일 지난 빌드 캐시 정리. 로컬 시험(ENV_FILE)에서는 내 컴퓨터의 다른 이미지를 건드리지 않게 건너뛴다.
if [[ -z "${ENV_FILE:-}" ]]; then
	docker image prune -f > /dev/null
	docker builder prune -f --filter until=168h > /dev/null
fi
compose ps
