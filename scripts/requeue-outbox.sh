#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
if (( $# != 1 )) || [[ ! "$1" =~ ^[1-9][0-9]*$ ]]; then
  printf 'Usage: %s <failed-outbox-id>\nRepair the cause before explicitly requeuing the same FAILED row.\n' "$0" >&2
  exit 2
fi

docker compose exec -T postgres sh -c \
  'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -v row_id="$1"' sh "$1" <<'SQL'
UPDATE vortex.outbox
SET status = 'PENDING', attempts = 0, next_attempt_at = now(), claimed_until = NULL, last_error = NULL
WHERE outbox_id = :'row_id'::bigint AND status = 'FAILED'
RETURNING outbox_id, event_id, aggregate_type, aggregate_id, aggregate_sequence, status;
SQL
