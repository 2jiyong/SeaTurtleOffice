#!/bin/bash
# postgres 볼륨이 처음 만들어질 때 한 번만 돈다. Umami가 쓸 사용자와 DB를 만든다.
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
	-v pw="$UMAMI_DB_PASSWORD" <<'SQL'
create role umami login password :'pw';
create database umami owner umami;
SQL
