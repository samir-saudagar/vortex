#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

phase3_kafka=false
phase6=false
test_selectors=(--tests com.vortex.persistence.PersistenceFoundationIntegrationTest
  --tests com.vortex.messaging.Phase3PersistenceIntegrationTest
  --tests com.vortex.workflow.Phase4WorkflowIntegrationTest
  --tests com.vortex.workflow.Phase4MigrationIntegrationTest)
case "${1:-}" in
  '') ;;
  --phase6-only)
    phase6=true
    test_selectors=(--tests com.vortex.workflow.Phase6ReliabilityIntegrationTest)
    ;;
  --with-kafka|--phase6)
    if [[ "$1" == --phase6 ]]; then
      phase6=true
      test_selectors+=(--tests com.vortex.workflow.Phase6ReliabilityIntegrationTest)
    fi
    phase3_kafka=true
    test_selectors+=(--tests com.vortex.messaging.Phase3KafkaIntegrationTest
      --tests com.vortex.persistence.KafkaTopicPolicyIntegrationTest
      --tests com.vortex.workflow.Phase4KafkaIntegrationTest)
    ;;
  *) printf 'Usage: %s [--with-kafka|--phase6|--phase6-only]\n' "$0" >&2; exit 2 ;;
esac
if (( $# > 1 )); then
  printf 'Usage: %s [--with-kafka|--phase6|--phase6-only]\n' "$0" >&2
  exit 2
fi

if [[ -z "$(docker compose ps -q postgres)" ]]; then
  printf 'PostgreSQL is not running. Start it with scripts/dev-start.sh.\n' >&2
  exit 1
fi

db_user="$(docker compose exec -T postgres sh -c 'printf %s "$POSTGRES_USER"')"
db_password="$(docker compose exec -T postgres sh -c 'printf %s "$POSTGRES_PASSWORD"')"
test_db="vortex_test_$(date -u +%Y%m%d%H%M%S)_$$"
cleanup() {
  docker compose exec -T postgres dropdb --if-exists --username "$db_user" "$test_db" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

docker compose exec -T postgres createdb --username "$db_user" "$test_db"
DATABASE_URL="jdbc:postgresql://localhost:5432/${test_db}?currentSchema=vortex" \
POSTGRES_USER="$db_user" \
POSTGRES_PASSWORD="$db_password" \
VORTEX_DB_INTEGRATION_TEST=true \
VORTEX_PHASE3_KAFKA_TEST="$phase3_kafka" \
VORTEX_KAFKA_INTEGRATION_TEST="$phase3_kafka" \
VORTEX_PHASE4_KAFKA_TEST="$phase3_kafka" \
VORTEX_PHASE6_TEST="$phase6" \
  ./gradlew --no-daemon --rerun-tasks :backend:test "${test_selectors[@]}"
