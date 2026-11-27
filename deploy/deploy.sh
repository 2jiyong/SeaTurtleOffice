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

compose up -d --build
# 두 서버는 world/·scenarios/를 시작할 때만 읽는다. 내용만 바뀌면 이미지가 그대로라 다시 뜨지 않으므로 늘 다시 띄운다.
compose up -d --force-recreate --no-build backend ai
# 옛 이미지 정리. 로컬 시험(ENV_FILE)에서는 내 컴퓨터의 다른 이미지를 건드리지 않게 건너뛴다.
if [[ -z "${ENV_FILE:-}" ]]; then docker image prune -f > /dev/null; fi
compose ps
