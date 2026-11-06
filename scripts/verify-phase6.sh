#!/usr/bin/env bash
# Serial, isolated Phase 1–6 verification; never resets application data or journals.
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
exec 9>/tmp/vortex-phase6-verification.lock
flock -n 9 || { echo 'Another Phase 6 verification is running.' >&2; exit 1; }
artifacts="$(mktemp -d /tmp/vortex-phase6-verification-XXXXXX)"
printf 'Phase 6 evidence: %s\n' "$artifacts"
./scripts/dev-start.sh >"$artifacts/services.log" 2>&1
python3 scripts/phase6-preservation.py "$artifacts/before.json"
finish() {
  result=$?
  trap - EXIT
  python3 scripts/phase6-preservation.py "$artifacts/after.json" || result=1
  if ! cmp -s "$artifacts/before.json" "$artifacts/after.json"; then
    echo "Application/journal fingerprints changed; inspect $artifacts" >&2
    result=1
  fi
  printf 'Verification exit=%s; evidence=%s\n' "$result" "$artifacts"
  exit "$result"
}
trap finish EXIT
run() {
  local name="$1"; shift
  printf 'Running %s…\n' "$name"
  if ! "$@" >"$artifacts/$name.log" 2>&1; then
    tail -n 70 "$artifacts/$name.log"
    return 1
  fi
}
run frontend-typecheck npm --prefix frontend run typecheck
run frontend-tests npm --prefix frontend test
run frontend-build npm --prefix frontend run build
run backend-build env VORTEX_DB_INTEGRATION_TEST=false VORTEX_PHASE3_KAFKA_TEST=false VORTEX_KAFKA_INTEGRATION_TEST=false VORTEX_PHASE4_KAFKA_TEST=false VORTEX_PHASE6_TEST=false VORTEX_KAFKA_SMOKE_TEST=false ./gradlew --no-daemon --rerun-tasks :backend:build
mkdir "$artifacts/backend-default-xml"
cp backend/build/test-results/test/TEST-*.xml "$artifacts/backend-default-xml/"
run integration ./scripts/test-database.sh --phase6
mkdir "$artifacts/integration-xml"
cp backend/build/test-results/test/TEST-*.xml "$artifacts/integration-xml/"
run kafka-smoke env VORTEX_KAFKA_SMOKE_TEST=true ./gradlew --no-daemon --rerun-tasks :backend:test --tests com.vortex.diagnostics.KafkaBrokerSmokeTest
mkdir "$artifacts/smoke-xml"
cp backend/build/test-results/test/TEST-*.xml "$artifacts/smoke-xml/"
# The existing Python/Java Phase 3 contracts share diagnostic topics. Never parallelize them.
run simulator env VORTEX_SIMULATOR_KAFKA_TEST=true PYTHONPATH=simulator/src simulator/.venv/bin/python -m pytest -q simulator/tests
run whitespace git diff --check
