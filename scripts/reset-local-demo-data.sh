#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

printf 'This removes every table and local demo row from the vortex schema. The Docker volume is preserved.\n'
printf 'Type RESET VORTEX DATABASE to continue: '
IFS= read -r confirmation
if [[ "$confirmation" != "RESET VORTEX DATABASE" ]]; then
  printf 'Reset cancelled.\n'
  exit 1
fi

if [[ -z "$(docker compose ps -q postgres)" ]]; then
  printf 'PostgreSQL is not running. Start it with scripts/dev-start.sh.\n' >&2
  exit 1
fi

docker compose exec -T postgres sh -c 'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
DROP SCHEMA IF EXISTS vortex CASCADE;
CREATE SCHEMA vortex;
SQL

printf 'The vortex schema was cleared. Restart the backend to reapply Flyway migrations and seed data.\n'
